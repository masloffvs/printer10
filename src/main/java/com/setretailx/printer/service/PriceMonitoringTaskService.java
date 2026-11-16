package com.setretailx.printer.service;

import com.setretailx.printer.config.AppConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setretailx.printer.service.setretailx.dto.ProductDto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persistent scheduler and workflows for discovery of products with changing prices. */
public final class PriceMonitoringTaskService implements AutoCloseable {
    public static final String DISCOVERY_TASK_ID = "price_candidate_discovery";
    public static final String SNAPSHOT_TASK_ID = "price_snapshot";
    public static final String SALES_TASK_ID = "sales_history_sync";
    public static final String DISCOVERY_TASK_NAME = "Поиск товаров с изменением цен";
    public static final String SNAPSHOT_TASK_NAME = "Снимки и сравнение цен";
    public static final String SALES_TASK_NAME = "История продаж из чеков";
    public static final int SALES_INITIAL_LOOKBACK_DAYS = 6;
    private static final int SALES_CHECK_LIMIT = 40;
    private static final int SALES_MAX_QUERIES = 10_000;
    public static final int DEFAULT_INTERVAL_SECONDS = 3600;
    public static final int CANDIDATE_TTL_SECONDS = 12 * 60 * 60;
    public static final int PRODUCT_BATCH_SIZE = 100;
    private static final int SHELF = 12;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path path;
    private final SetRetailXConnector connector;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "price-monitor-tasks");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();

    /** Uses the same per-user database file as the Python task scheduler. */
    public PriceMonitoringTaskService(SetRetailXConnector connector) {
        this(connector, AppConfig.tasksDatabasePath());
    }

    public PriceMonitoringTaskService(SetRetailXConnector connector, Path path) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        try {
            initialize();
        } catch (SQLException error) {
            throw new IllegalStateException("Не удалось открыть хранилище фоновых задач: " + this.path, error);
        }
    }

    public Path databasePath() { return path; }

    /** Polls due tasks every five seconds. Only one task is run at a time. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(this::pollDueTask, 0, 5, TimeUnit.SECONDS);
        }
    }

    public List<TaskInfo> listTasks() throws SQLException {
        List<TaskInfo> result = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM long_tasks ORDER BY name")) {
            while (rows.next()) result.add(taskInfo(rows));
        }
        return result;
    }

    public void configure(String taskId, boolean enabled, int intervalSeconds) throws SQLException {
        int interval = Math.max(60, Math.min(intervalSeconds, 7 * 24 * 3600));
        double now = nowSeconds();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            acquireWriteLock(connection);
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT last_started_at FROM long_tasks WHERE task_id=?")) {
                select.setString(1, taskId);
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) throw new SQLException("Неизвестная задача: " + taskId);
                    double next = now + interval;
                    double lastStarted = row.getDouble(1);
                    if (!row.wasNull()) next = Math.max(next, lastStarted + interval);
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE long_tasks SET enabled=?, interval_seconds=?, next_run_at=? WHERE task_id=?")) {
                        update.setInt(1, enabled ? 1 : 0);
                        update.setInt(2, interval);
                        if (enabled) update.setDouble(3, next); else update.setNull(3, java.sql.Types.REAL);
                        update.setString(4, taskId);
                        update.executeUpdate();
                    }
                }
            }
            connection.commit();
        }
    }

    /** Runs now while retaining the scheduled next run, matching Python force_claim behavior. */
    public boolean runNow(String taskId) {
        if (executor.isShutdown() || !running.get() || !isTaskRunning.compareAndSet(false, true)) return false;
        try {
            TaskInfo task = forceClaim(taskId, nowSeconds());
            if (task == null) {
                isTaskRunning.set(false);
                return false;
            }
            executor.execute(() -> executeTask(task));
            return true;
        } catch (SQLException error) {
            isTaskRunning.set(false);
            throw new IllegalStateException("Не удалось запустить задачу", error);
        }
    }

    private final AtomicBoolean isTaskRunning = new AtomicBoolean();

    public List<PriceChange> recentChanges(int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 1000));
        List<PriceChange> result = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT marking, name, barcode, detected_at, old_prices_json, new_prices_json "
                        + "FROM price_changes ORDER BY detected_at DESC LIMIT ?")) {
            statement.setInt(1, bounded);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new PriceChange(rows.getString("marking"), rows.getString("name"),
                            rows.getString("barcode"), rows.getDouble("detected_at"),
                            rows.getString("old_prices_json"), rows.getString("new_prices_json")));
                }
            }
        }
        return result;
    }

    /** Returns recorded changes for one marking after the shelf label was last updated. */
    public List<PriceChange> priceChangesSince(String marking, double afterSeconds) throws SQLException {
        List<PriceChange> result = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT marking, name, barcode, detected_at, old_prices_json, new_prices_json "
                        + "FROM price_changes WHERE marking=? AND detected_at>? ORDER BY detected_at DESC")) {
            statement.setString(1, marking);
            statement.setDouble(2, afterSeconds);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new PriceChange(rows.getString("marking"), rows.getString("name"),
                            rows.getString("barcode"), rows.getDouble("detected_at"),
                            rows.getString("old_prices_json"), rows.getString("new_prices_json")));
                }
            }
        }
        return result;
    }

    /** Returns every price observation retained for one product, newest first. */
    public List<PriceSnapshot> priceHistory(String marking) throws SQLException {
        List<PriceSnapshot> result = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT name, barcode, captured_at, prices_json FROM price_snapshot_history "
                        + "WHERE marking=? ORDER BY captured_at DESC, id DESC")) {
            statement.setString(1, marking);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new PriceSnapshot(rows.getString("name"), rows.getString("barcode"),
                        rows.getDouble("captured_at"), rows.getString("prices_json")));
            }
        }
        return result;
    }

    /** Daily sales totals for one exact SetRetailX article, newest day first. */
    public List<SalesDay> salesHistory(String marking) throws SQLException {
        List<SalesDay> result = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT CAST(strftime('%s', date(sold_at, 'unixepoch', 'localtime')) AS INTEGER) AS day, SUM(quantity) AS units, "
                        + "SUM(cost) AS revenue, COUNT(DISTINCT check_id) AS checks "
                        + "FROM sales_positions WHERE item=? GROUP BY day ORDER BY day DESC")) {
            statement.setString(1, marking);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new SalesDay(rows.getLong("day"), rows.getDouble("units"),
                        rows.getDouble("revenue"), rows.getInt("checks")));
            }
        }
        return result;
    }

    /** Individual receipt lines for one article, including the receipt and position timestamps. */
    public List<SalesPosition> salesPositions(String marking) throws SQLException {
        List<SalesPosition> result = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT check_id, check_number, sold_at, date_commit, name, item, barcode, quantity, unit, price, cost, discount "
                        + "FROM sales_positions WHERE item=? ORDER BY sold_at DESC, position_order DESC LIMIT 2000")) {
            statement.setString(1, marking);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new SalesPosition(rows.getLong("check_id"),
                        rows.getString("check_number"), rows.getDouble("sold_at"), rows.getDouble("date_commit"), rows.getString("name"),
                        rows.getString("item"), rows.getString("barcode"), rows.getDouble("quantity"),
                        rows.getString("unit"), rows.getDouble("price"), rows.getDouble("cost"),
                        rows.getDouble("discount")));
            }
        }
        return result;
    }

    public List<TaskRun> taskRuns(String taskId, int limit) throws SQLException {
        List<TaskRun> result = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id, task_id, started_at, finished_at, status, result FROM task_runs "
                        + "WHERE task_id=? ORDER BY started_at DESC LIMIT ?")) {
            statement.setString(1, taskId);
            statement.setInt(2, Math.max(1, Math.min(limit, 1000)));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new TaskRun(rows.getLong("id"), rows.getString("task_id"),
                        rows.getDouble("started_at"), nullableDouble(rows, "finished_at"),
                        rows.getString("status"), rows.getString("result")));
            }
        }
        return result;
    }

    private void pollDueTask() {
        if (!isTaskRunning.compareAndSet(false, true)) return;
        try {
            TaskInfo task = claimDue(nowSeconds());
            if (task != null) executeTask(task);
            else isTaskRunning.set(false);
        } catch (Exception error) {
            isTaskRunning.set(false);
            // Keep the scheduler alive; the task's persisted status is updated by executeTask.
        }
    }

    private void executeTask(TaskInfo task) {
        double startedAt = nowSeconds();
        try {
            String result = switch (task.taskId()) {
                case DISCOVERY_TASK_ID -> runCandidateDiscovery();
                case SALES_TASK_ID -> runSalesHistory();
                default -> runPriceSnapshots();
            };
            finish(task.taskId(), startedAt, "Успешно", result);
        } catch (Throwable error) {
            Throwable cause = unwrap(error);
            String result = cause.getClass().getSimpleName() + ": "
                    + (cause.getMessage() == null ? cause.toString() : cause.getMessage());
            try { finish(task.taskId(), startedAt, "Ошибка", result); }
            catch (SQLException finishError) { cause.addSuppressed(finishError); }
        } finally {
            isTaskRunning.set(false);
        }
    }

    private String runCandidateDiscovery() {
        List<String> markings = connector.getNearlyPriceChanging(SHELF).join();
        int count = recordCandidates(markings, nowSeconds());
        return "Получено артикулов: " + count + "; активных сейчас: "
                + activeCandidates(nowSeconds()).size() + "; срок новых — 12 часов";
    }

    private String runPriceSnapshots() {
        double now = nowSeconds();
        List<String> active = activeCandidates(now);
        List<ProductDto> products = new ArrayList<>();
        for (int start = 0; start < active.size(); start += PRODUCT_BATCH_SIZE) {
            products.addAll(connector.getProducts(
                    active.subList(start, Math.min(start + PRODUCT_BATCH_SIZE, active.size())), SHELF).join());
        }
        SnapshotCounts counts = recordSnapshots(products, active, now);
        return "Активных артикулов: " + active.size() + "; снимков цен: " + counts.snapshots()
                + "; новых изменений: " + counts.changes()
                + "; ответов с неизвестным ProductVO.marking: " + counts.unmatched();
    }

    private String runSalesHistory() {
        Instant until = Instant.now();
        Instant since = salesSyncWatermark();
        if (since == null) since = until.minusSeconds(SALES_INITIAL_LOOKBACK_DAYS * 24L * 60 * 60);
        else since = since.minusSeconds(60 * 60); // overlap an hour to cover late-arriving receipts
        // SearchArgumentVO date values have second precision.
        since = Instant.ofEpochSecond(since.getEpochSecond());
        until = Instant.ofEpochSecond(until.getEpochSecond());
        List<JsonNode> checks = new ArrayList<>();
        LinkedHashSet<Long> listedIds = new LinkedHashSet<>();
        ArrayDeque<CheckRange> ranges = new ArrayDeque<>();
        ranges.add(new CheckRange(since, until));
        int queryCount = 0;
        while (!ranges.isEmpty()) {
            if (++queryCount > SALES_MAX_QUERIES) {
                throw new IllegalStateException("findChecks превысил безопасный предел запросов; watermark не обновлён.");
            }
            CheckRange range = ranges.removeFirst();
            List<JsonNode> page = checkRows(connector.findChecks(range.from(), range.to(), SALES_CHECK_LIMIT).join());
            if (page.size() >= SALES_CHECK_LIMIT) {
                long midpoint = range.from().getEpochSecond()
                        + Duration.between(range.from(), range.to()).getSeconds() / 2;
                Instant split = Instant.ofEpochSecond(midpoint);
                if (!split.isAfter(range.from()) || !split.isBefore(range.to())) {
                    throw new IllegalStateException("findChecks достиг лимита " + SALES_CHECK_LIMIT
                            + " для минимального временного интервала; watermark не обновлён.");
                }
                // The server accepts whole-second dates and may include both ends; duplicate IDs are deduplicated.
                ranges.addFirst(new CheckRange(split, range.to()));
                ranges.addFirst(new CheckRange(range.from(), split));
                continue;
            }
            for (JsonNode summary : page) {
                if (listedIds.add(checkId(summary))) checks.add(summary);
            }
        }
        int loaded = 0;
        int positions = 0;
        for (JsonNode summary : checks) {
            long id = checkId(summary);
            if (id <= 0 || isSalesCheckProcessed(id)) continue;
            JsonNode response = connector.findCheck(id).join();
            JsonNode fullCheck = response.path("result");
            if (fullCheck.isObject()) {
                for (String property : List.of("check", "receipt", "data")) {
                    if (fullCheck.path(property).isObject()) { fullCheck = fullCheck.path(property); break; }
                }
            }
            if (!fullCheck.isObject()) throw new IllegalStateException("findCheck не вернул чек " + id);
            positions += recordSalesCheck(fullCheck);
            loaded++;
        }
        saveSalesSyncWatermark(until);
        return "Период: " + since + " — " + until + "; найдено чеков: "
                + checks.size() + "; новых чеков: " + loaded + "; позиций сохранено: " + positions;
    }

    private record CheckRange(Instant from, Instant to) { }

    private Instant salesSyncWatermark() {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT value FROM sales_sync_state WHERE state_key='last_successful_until'")) {
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? Instant.ofEpochMilli((long) (row.getDouble(1) * 1000)) : null;
            }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    private void saveSalesSyncWatermark(Instant until) {
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "INSERT INTO sales_sync_state(state_key, value) VALUES ('last_successful_until', ?) "
                        + "ON CONFLICT(state_key) DO UPDATE SET value=excluded.value")) {
            update.setDouble(1, until.toEpochMilli() / 1000d);
            update.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    private boolean isSalesCheckProcessed(long checkId) {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM sales_checks WHERE check_id=?")) {
            query.setLong(1, checkId);
            try (ResultSet row = query.executeQuery()) { return row.next(); }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    /** Saves a whole check atomically, so a retry can never duplicate only part of its lines. */
    private int recordSalesCheck(JsonNode check) {
        long checkId = check.path("id").asLong(0);
        if (checkId <= 0) throw new IllegalStateException("В чеке отсутствует корректный id.");
        double soldAt = epochSeconds(check.path("date"), nowSeconds());
        JsonNode lines = check.path("positions");
        if (!lines.isArray()) throw new IllegalStateException("В чеке " + checkId + " отсутствует positions.");
        int saved = 0;
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insertCheck = connection.prepareStatement(
                    "INSERT OR IGNORE INTO sales_checks(check_id, check_number, sold_at) VALUES (?, ?, ?)")) {
                insertCheck.setLong(1, checkId);
                insertCheck.setString(2, text(check.path("number")));
                insertCheck.setDouble(3, soldAt);
                if (insertCheck.executeUpdate() == 0) { connection.commit(); return 0; }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO sales_positions(check_id, position_order, check_number, sold_at, name, item, barcode, "
                            + "quantity, unit, price, cost, discount, date_commit) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                    JsonNode line = lines.get(lineIndex);
                    insert.setLong(1, checkId);
                    insert.setInt(2, lineIndex);
                    insert.setString(3, text(check.path("number")));
                    insert.setDouble(4, soldAt);
                    insert.setString(5, text(line.path("name")));
                    insert.setString(6, text(line.path("item")));
                    insert.setString(7, text(line.path("barcode")));
                    insert.setDouble(8, decimal(line.path("quantity")));
                    insert.setString(9, text(line.path("unit")));
                    insert.setDouble(10, decimal(line.path("price")));
                    insert.setDouble(11, decimal(line.path("cost")));
                    insert.setDouble(12, decimal(line.path("discount")));
                    insert.setDouble(13, epochSeconds(line.path("dateCommit"), soldAt));
                    insert.addBatch();
                    saved++;
                }
                insert.executeBatch();
            }
            connection.commit();
            return saved;
        } catch (SQLException error) { throw new IllegalStateException("Не удалось сохранить чек " + checkId, error); }
    }

    private static List<JsonNode> checkRows(JsonNode response) {
        JsonNode result = response == null ? null : response.path("result");
        if (result == null || result.isMissingNode() || result.isNull()) {
            throw new IllegalStateException("findChecks не вернул result; синхронизация остановлена без отметки об успехе.");
        }
        JsonNode rows = result;
        if (result.isArray() && result.size() == 2) {
            if (result.get(1).isArray()) rows = result.get(1);
            else if (result.get(0).isArray()) rows = result.get(0);
        }
        if (rows.isObject()) {
            for (String property : List.of("checks", "list", "items", "result", "data")) {
                JsonNode nested = rows.path(property);
                if (nested.isArray()) { rows = nested; break; }
            }
        }
        if (!rows.isArray()) throw new IllegalStateException("findChecks вернул неожиданный формат списка.");
        List<JsonNode> checks = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isObject()) throw new IllegalStateException("findChecks содержит строку неожиданного формата.");
            checkId(row); // Fail rather than advance the watermark past an unidentifiable receipt.
            checks.add(row);
        }
        return checks;
    }

    private static long checkId(JsonNode summary) {
        JsonNode id = summary.path("id");
        if (id.isMissingNode() || id.isNull() || id.asText().isBlank()) id = summary.path("checkId");
        long value = id.asLong(0);
        if (value <= 0) throw new IllegalStateException("В строке findChecks отсутствует корректный id чека.");
        return value;
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("").trim();
    }

    private static double decimal(JsonNode node) {
        String value = text(node).replace("\u00a0", "").replace("\u202f", "").replace(',', '.').trim();
        if (value.isEmpty()) return 0;
        try { return new BigDecimal(value).doubleValue(); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static double epochSeconds(JsonNode node, double fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback;
        if (node.isNumber()) {
            double value = node.asDouble();
            return value > 100_000_000_000d ? value / 1000d : value;
        }
        String value = text(node);
        if (value.isEmpty()) return fallback;
        try {
            double numeric = Double.parseDouble(value);
            return numeric > 100_000_000_000d ? numeric / 1000d : numeric;
        } catch (NumberFormatException ignored) { }
        try { return Instant.parse(value).toEpochMilli() / 1000d; }
        catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toEpochSecond(); }
        catch (DateTimeParseException ignored) { return fallback; }
    }

    private int recordCandidates(List<String> rawMarkings, double now) {
        LinkedHashSet<String> markings = new LinkedHashSet<>();
        if (rawMarkings != null) for (String raw : rawMarkings) {
            if (raw != null && !raw.trim().isEmpty()) markings.add(raw.trim());
        }
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            acquireWriteLock(connection);
            List<String> expired = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT marking FROM price_candidates WHERE expires_at<=?")) {
                query.setDouble(1, now);
                try (ResultSet rows = query.executeQuery()) { while (rows.next()) expired.add(rows.getString(1)); }
            }
            for (String marking : expired) {
                deleteByMarking(connection, "price_candidates", marking);
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO price_candidates (marking, first_seen_at, expires_at) VALUES (?, ?, ?)")) {
                for (String marking : markings) {
                    insert.setString(1, marking);
                    insert.setDouble(2, now);
                    insert.setDouble(3, now + CANDIDATE_TTL_SECONDS);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
            return markings.size();
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    private List<String> activeCandidates(double now) {
        List<String> result = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT marking FROM price_candidates WHERE expires_at>? ORDER BY first_seen_at")) {
            statement.setDouble(1, now);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(rows.getString(1)); }
            return result;
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    private SnapshotCounts recordSnapshots(List<ProductDto> products, List<String> activeMarkings, double now) {
        var active = new LinkedHashSet<>(activeMarkings);
        int snapshots = 0, changes = 0, unmatched = 0;
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            acquireWriteLock(connection);
            for (ProductDto product : products) {
                if (product == null || product.marking() == null || product.marking().trim().isEmpty()) continue;
                String marking = product.marking().trim();
                if (!active.contains(marking)) { unmatched++; continue; }
                String prices = canonicalPricesJson(product);
                String name = firstNonEmpty(product.fullName(), product.name());
                String barcode = firstNonEmpty(product.barCode(), product.barcode());
                try (PreparedStatement history = connection.prepareStatement(
                        "INSERT INTO price_snapshot_history (marking, name, barcode, prices_json, captured_at) "
                                + "VALUES (?, ?, ?, ?, ?)")) {
                    history.setString(1, marking); history.setString(2, name); history.setString(3, barcode);
                    history.setString(4, prices); history.setDouble(5, now); history.executeUpdate();
                }
                String previous = null;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT prices_json FROM price_snapshots WHERE marking=?")) {
                    query.setString(1, marking);
                    try (ResultSet row = query.executeQuery()) { if (row.next()) previous = row.getString(1); }
                }
                if (previous != null && !previous.equals(prices)) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO price_changes (marking, name, barcode, detected_at, old_prices_json, new_prices_json) "
                                    + "VALUES (?, ?, ?, ?, ?, ?)")) {
                        insert.setString(1, marking); insert.setString(2, name); insert.setString(3, barcode);
                        insert.setDouble(4, now); insert.setString(5, previous); insert.setString(6, prices);
                        insert.executeUpdate();
                    }
                    changes++;
                }
                try (PreparedStatement upsert = connection.prepareStatement(
                        "INSERT INTO price_snapshots (marking, name, barcode, prices_json, captured_at) VALUES (?, ?, ?, ?, ?) "
                                + "ON CONFLICT(marking) DO UPDATE SET name=excluded.name, barcode=excluded.barcode, "
                                + "prices_json=excluded.prices_json, captured_at=excluded.captured_at")) {
                    upsert.setString(1, marking); upsert.setString(2, name); upsert.setString(3, barcode);
                    upsert.setString(4, prices); upsert.setDouble(5, now); upsert.executeUpdate();
                }
                snapshots++;
            }
            connection.commit();
            return new SnapshotCounts(snapshots, changes, unmatched);
        } catch (SQLException | JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    private static String canonicalPricesJson(ProductDto product) throws JsonProcessingException {
        // Match Python json.dumps(..., ensure_ascii=False, sort_keys=True), including key order
        // and separators, so snapshots remain comparable across both implementations.
        return "{\"prices99Map\": " + canonicalPriceMapJson(product.prices99Map())
                + ", \"pricesMap\": " + canonicalPriceMapJson(product.pricesMap()) + "}";
    }

    private static String canonicalPriceMapJson(Map<String, JsonNode> prices) throws JsonProcessingException {
        Map<String, String> entries = new LinkedHashMap<>();
        if (prices != null) prices.forEach((key, value) -> {
            if (value == null || !value.isObject()) return;
            JsonNode numberNode = value.get("number");
            String number = numberNode == null || numberNode.isNull() ? key : numberNode.asText(key);
            JsonNode valueNode = value.get("value");
            String rawPrice = valueNode == null || valueNode.isNull() ? "" : valueNode.asText();
            try {
                java.math.BigDecimal decimal = new java.math.BigDecimal(rawPrice).stripTrailingZeros();
                rawPrice = decimal.signum() == 0 ? "0" : decimal.toPlainString();
            } catch (NumberFormatException ignored) { }
            entries.put(number, rawPrice);
        });
        StringBuilder result = new StringBuilder("{");
        boolean first = true;
        for (var entry : entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            if (!first) result.append(", ");
            first = false;
            result.append(JSON.writeValueAsString(entry.getKey())).append(": ")
                    .append(JSON.writeValueAsString(entry.getValue()));
        }
        return result.append('}').toString();
    }

    private TaskInfo claimDue(double now) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            acquireWriteLock(connection);
            TaskInfo task;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT * FROM long_tasks WHERE enabled=1 AND (next_run_at IS NULL OR next_run_at<=?) "
                            + "ORDER BY next_run_at, task_id LIMIT 1")) {
                query.setDouble(1, now);
                try (ResultSet row = query.executeQuery()) { task = row.next() ? taskInfo(row) : null; }
            }
            if (task != null) markStarted(connection, task, now, now + task.intervalSeconds());
            connection.commit();
            return task;
        }
    }

    private TaskInfo forceClaim(String taskId, double now) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            acquireWriteLock(connection);
            TaskInfo task;
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM long_tasks WHERE task_id=?")) {
                query.setString(1, taskId);
                try (ResultSet row = query.executeQuery()) { task = row.next() ? taskInfo(row) : null; }
            }
            if (task != null) markStarted(connection, task, now, now + task.intervalSeconds());
            connection.commit();
            return task;
        }
    }

    private static void markStarted(Connection connection, TaskInfo task, double now, double next) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE long_tasks SET next_run_at=?, last_started_at=?, last_status='Выполняется', last_result='' WHERE task_id=?")) {
            update.setDouble(1, next); update.setDouble(2, now); update.setString(3, task.taskId()); update.executeUpdate();
        }
    }

    private void finish(String taskId, double startedAt, String status, String result) throws SQLException {
        double finishedAt = nowSeconds();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE long_tasks SET last_finished_at=?, last_status=?, last_result=? WHERE task_id=?")) {
                statement.setDouble(1, finishedAt); statement.setString(2, status); statement.setString(3, result);
                statement.setString(4, taskId); statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO task_runs (task_id, started_at, finished_at, status, result) VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, taskId); statement.setDouble(2, startedAt); statement.setDouble(3, finishedAt);
                statement.setString(4, status); statement.setString(5, result); statement.executeUpdate();
            }
            connection.commit();
        }
    }

    private void initialize() throws SQLException {
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException error) { throw new SQLException("Не удалось создать каталог " + path.getParent(), error); }
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS long_tasks (task_id TEXT PRIMARY KEY, name TEXT NOT NULL, "
                    + "enabled INTEGER NOT NULL DEFAULT 1, interval_seconds INTEGER NOT NULL, next_run_at REAL, "
                    + "last_started_at REAL, last_finished_at REAL, last_status TEXT NOT NULL DEFAULT 'Ожидает запуска', "
                    + "last_result TEXT NOT NULL DEFAULT '')");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS price_candidates (marking TEXT PRIMARY KEY, "
                    + "first_seen_at REAL NOT NULL, expires_at REAL NOT NULL, source TEXT NOT NULL DEFAULT 'NearlyPriceChanging')");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS price_snapshots (marking TEXT PRIMARY KEY, "
                    + "name TEXT NOT NULL DEFAULT '', barcode TEXT NOT NULL DEFAULT '', prices_json TEXT NOT NULL, captured_at REAL NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS price_snapshot_history (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "marking TEXT NOT NULL, name TEXT NOT NULL DEFAULT '', barcode TEXT NOT NULL DEFAULT '', "
                    + "prices_json TEXT NOT NULL, captured_at REAL NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_price_snapshot_history_marking_time "
                    + "ON price_snapshot_history(marking, captured_at DESC)");
            // Preserve the latest observation already available when upgrading an existing database.
            statement.executeUpdate("INSERT INTO price_snapshot_history (marking, name, barcode, prices_json, captured_at) "
                    + "SELECT s.marking, s.name, s.barcode, s.prices_json, s.captured_at FROM price_snapshots s "
                    + "WHERE NOT EXISTS (SELECT 1 FROM price_snapshot_history h "
                    + "WHERE h.marking=s.marking AND h.captured_at=s.captured_at AND h.prices_json=s.prices_json)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS price_changes (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "marking TEXT NOT NULL, name TEXT NOT NULL DEFAULT '', barcode TEXT NOT NULL DEFAULT '', detected_at REAL NOT NULL, "
                    + "old_prices_json TEXT NOT NULL, new_prices_json TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS task_runs (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "task_id TEXT NOT NULL, started_at REAL NOT NULL, finished_at REAL, status TEXT NOT NULL, "
                    + "result TEXT NOT NULL DEFAULT '')");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_task_runs_task_started ON task_runs(task_id, started_at DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_price_changes_detected ON price_changes(detected_at DESC)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS sales_checks (check_id INTEGER PRIMARY KEY, "
                    + "check_number TEXT NOT NULL DEFAULT '', sold_at REAL NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS sales_positions (check_id INTEGER NOT NULL "
                    + "REFERENCES sales_checks(check_id) ON DELETE CASCADE, position_order INTEGER NOT NULL DEFAULT 0, "
                    + "check_number TEXT NOT NULL DEFAULT '', sold_at REAL NOT NULL, name TEXT NOT NULL DEFAULT '', "
                    + "item TEXT NOT NULL DEFAULT '', barcode TEXT NOT NULL DEFAULT '', quantity REAL NOT NULL DEFAULT 0, "
                    + "unit TEXT NOT NULL DEFAULT '', price REAL NOT NULL DEFAULT 0, cost REAL NOT NULL DEFAULT 0, "
                    + "discount REAL NOT NULL DEFAULT 0, date_commit REAL NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY(check_id, position_order))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sales_positions_item_time "
                    + "ON sales_positions(item, sold_at DESC)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS sales_sync_state (state_key TEXT PRIMARY KEY, value REAL NOT NULL)");
            statement.executeUpdate("UPDATE long_tasks SET task_id='" + DISCOVERY_TASK_ID + "', name='"
                    + DISCOVERY_TASK_NAME + "' WHERE task_id='price_change_monitor' AND NOT EXISTS "
                    + "(SELECT 1 FROM long_tasks WHERE task_id='" + DISCOVERY_TASK_ID + "')");
            insertTask(connection, DISCOVERY_TASK_ID, DISCOVERY_TASK_NAME);
            insertTask(connection, SNAPSHOT_TASK_ID, SNAPSHOT_TASK_NAME);
            insertTask(connection, SALES_TASK_ID, SALES_TASK_NAME);
            statement.executeUpdate("UPDATE long_tasks SET enabled=0, next_run_at=NULL, "
                    + "last_status='Отключена (устаревшая задача)' WHERE task_id='price_change_monitor'");
            statement.executeUpdate("UPDATE long_tasks SET last_status='Прервано при закрытии приложения', "
                    + "last_result='Предыдущий запуск не завершился.' WHERE last_status='Выполняется'");
        }
    }

    private static void insertTask(Connection connection, String id, String name) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO long_tasks (task_id, name, enabled, interval_seconds, last_status) "
                        + "VALUES (?, ?, 1, ?, 'Ожидает запуска')")) {
            insert.setString(1, id); insert.setString(2, name); insert.setInt(3, DEFAULT_INTERVAL_SECONDS);
            insert.executeUpdate();
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout = 15000"); }
        return connection;
    }

    private static TaskInfo taskInfo(ResultSet row) throws SQLException {
        return new TaskInfo(row.getString("task_id"), row.getString("name"), row.getInt("enabled") != 0,
                row.getInt("interval_seconds"), nullableDouble(row, "next_run_at"),
                nullableDouble(row, "last_started_at"), nullableDouble(row, "last_finished_at"),
                row.getString("last_status"), row.getString("last_result"));
    }

    private static Double nullableDouble(ResultSet row, String column) throws SQLException {
        double value = row.getDouble(column);
        return row.wasNull() ? null : value;
    }

    private static void deleteByMarking(Connection connection, String table, String marking) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE marking=?")) {
            delete.setString(1, marking); delete.executeUpdate();
        }
    }

    /** SQLite's first write reserves the writer lock, matching Python's BEGIN IMMEDIATE transactions. */
    private static void acquireWriteLock(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE long_tasks SET task_id=task_id WHERE 0");
        }
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.isEmpty() ? first : second == null ? "" : second;
    }

    private static double nowSeconds() { return System.currentTimeMillis() / 1000.0; }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }

    @Override public void close() {
        running.set(false);
        executor.shutdownNow();
    }

    public record TaskInfo(String taskId, String name, boolean enabled, int intervalSeconds,
                           Double nextRunAt, Double lastStartedAt, Double lastFinishedAt,
                           String lastStatus, String lastResult) { }
    public record PriceChange(String marking, String name, String barcode, double detectedAt,
                              String oldPricesJson, String newPricesJson) { }
    public record PriceSnapshot(String name, String barcode, double capturedAt, String pricesJson) { }
    public record SalesDay(long dayEpochSeconds, double units, double revenue, int checks) { }
    public record SalesPosition(long checkId, String checkNumber, double soldAt, double dateCommit, String name, String item,
                                String barcode, double quantity, String unit, double price, double cost,
                                double discount) { }
    public record TaskRun(long id, String taskId, double startedAt, Double finishedAt,
                          String status, String result) { }
    private record SnapshotCounts(int snapshots, int changes, int unmatched) { }
}
