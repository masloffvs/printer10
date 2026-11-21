package com.setretailx.printer.service;

import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.repository.SalesPointRepository;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
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
    public static final int DEFAULT_INTERVAL_SECONDS = 3600;
    public static final int CANDIDATE_TTL_SECONDS = 12 * 60 * 60;
    public static final int PRODUCT_BATCH_SIZE = 100;
    private static final int SHELF = 12;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path path;
    private final SetRetailXConnector connector;
    private final SalesPointRepository salesPoints;
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
        this.salesPoints = new SalesPointRepository(this.path);
        try {
            migrate();
        } catch (SQLException error) {
            throw new IllegalStateException("Не удалось открыть хранилище фоновых задач: " + this.path, error);
        }
    }

    public Path databasePath() { return path; }

    /** Parent API access to the configured connector; the connector itself rejects child use. */
    SetRetailXConnector connector() { return connector; }

    public void migrate() throws SQLException { initialize(); }

    /** Polls due tasks every five seconds. Only one task is run at a time. */
    public void start() {
        if (AppConfig.nodeRole() != AppConfig.NodeRole.PARENT) return;
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
        requireParentForMonitoring();
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
        requireParentForMonitoring();
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

    private static void requireParentForMonitoring() {
        if (AppConfig.nodeRole() != AppConfig.NodeRole.PARENT)
            throw new IllegalStateException("Мониторинг цен выполняется только на родительском ПК.");
    }

    private final AtomicBoolean isTaskRunning = new AtomicBoolean();

    public List<PriceChange> recentChanges(int limit) throws SQLException {
        return recentChanges(limit, "");
    }

    /** Returns the newest matching price changes across the full history, up to the requested display limit. */
    public List<PriceChange> recentChanges(int limit, String query) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 1000));
        String normalizedQuery = query == null ? "" : query.trim();
        String foldedQuery = normalizedQuery.toLowerCase(java.util.Locale.ROOT);
        List<PriceChange> result = new ArrayList<>();
        String sql = "SELECT id, marking, name, barcode, detected_at, old_prices_json, new_prices_json "
                + "FROM price_changes ORDER BY detected_at DESC"
                + (normalizedQuery.isEmpty() ? " LIMIT ?" : "");
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (normalizedQuery.isEmpty()) statement.setInt(1, bounded);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String marking = rows.getString("marking");
                    String name = rows.getString("name");
                    String barcode = rows.getString("barcode");
                    if (!foldedQuery.isEmpty() && !containsIgnoreCase(marking, foldedQuery)
                            && !containsIgnoreCase(name, foldedQuery) && !containsIgnoreCase(barcode, foldedQuery)) continue;
                    result.add(new PriceChange(rows.getLong("id"), marking, name, barcode, rows.getDouble("detected_at"),
                            rows.getString("old_prices_json"), rows.getString("new_prices_json")));
                    if (result.size() >= bounded) break;
                }
            }
        }
        return result;
    }

    private static boolean containsIgnoreCase(String value, String foldedQuery) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(foldedQuery);
    }

    /** Returns all recorded price changes on or after the supplied timestamp, newest first. */
    public List<PriceChange> changesSince(double afterSeconds) throws SQLException {
        List<PriceChange> result = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id, marking, name, barcode, detected_at, old_prices_json, new_prices_json "
                        + "FROM price_changes WHERE detected_at>=? ORDER BY detected_at DESC")) {
            statement.setDouble(1, afterSeconds);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new PriceChange(rows.getLong("id"), rows.getString("marking"), rows.getString("name"),
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
                "SELECT id, marking, name, barcode, detected_at, old_prices_json, new_prices_json "
                        + "FROM price_changes WHERE marking=? AND detected_at>? ORDER BY detected_at DESC")) {
            statement.setString(1, marking);
            statement.setDouble(2, afterSeconds);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new PriceChange(rows.getLong("id"), rows.getString("marking"), rows.getString("name"),
                            rows.getString("barcode"), rows.getDouble("detected_at"),
                            rows.getString("old_prices_json"), rows.getString("new_prices_json")));
                }
            }
        }
        return result;
    }

    /** Recent change events for articles currently present on one point, with per-point processing state. */
    public List<SalesPointPriceChange> salesPointChanges(long pointId, double afterSeconds) throws SQLException {
        List<SalesPointPriceChange> result = new ArrayList<>();
        String sql = "SELECT c.id,c.marking,c.name,c.barcode,c.detected_at,c.old_prices_json,c.new_prices_json,"
                + "i.name AS point_item_name,s.processed_at FROM price_changes c "
                + "JOIN sales_point_items i ON i.point_id=? AND i.article=c.marking COLLATE NOCASE "
                + "LEFT JOIN sales_point_price_change_status s ON s.change_id=c.id AND s.point_id=i.point_id "
                + "WHERE c.detected_at>=? ORDER BY c.detected_at DESC,c.id DESC";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, pointId);
            statement.setDouble(2, afterSeconds);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    double processedAt = rows.getDouble("processed_at");
                    Double processed = rows.wasNull() ? null : processedAt;
                    result.add(new SalesPointPriceChange(rows.getLong("id"), pointId, rows.getString("marking"),
                            rows.getString("point_item_name"), rows.getString("name"), rows.getString("barcode"),
                            rows.getDouble("detected_at"), rows.getString("old_prices_json"),
                            rows.getString("new_prices_json"), processed));
                }
            }
        }
        return result;
    }

    /** Applies exactly one selected event and records its point-specific status in the same SQLite transaction. */
    public AppliedPointPriceChange applySalesPointChange(long pointId, long changeId) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                // Reserve SQLite's writer lock before inspecting the event/status, so concurrent clicks cannot both apply it.
                try (PreparedStatement lock = connection.prepareStatement(
                        "UPDATE sales_point_items SET updated_at=updated_at WHERE point_id=? AND article=("
                                + "SELECT marking FROM price_changes WHERE id=?) COLLATE NOCASE")) {
                    lock.setLong(1, pointId); lock.setLong(2, changeId);
                    if (lock.executeUpdate() == 0) throw new SQLException("Товар события больше не находится в этой точке.");
                }
                String marking, name, pricesJson;
                double detectedAt;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT marking,name,new_prices_json,detected_at FROM price_changes WHERE id=?")) {
                    query.setLong(1, changeId);
                    try (ResultSet row = query.executeQuery()) {
                        if (!row.next()) throw new SQLException("Выбранное событие больше не существует.");
                        marking = row.getString("marking"); name = row.getString("name");
                        pricesJson = row.getString("new_prices_json");
                        detectedAt = row.getDouble("detected_at");
                    }
                }
                try (PreparedStatement done = connection.prepareStatement(
                        "SELECT 1 FROM sales_point_price_change_status WHERE point_id=? AND change_id=?")) {
                    done.setLong(1, pointId); done.setLong(2, changeId);
                    try (ResultSet row = done.executeQuery()) {
                        if (row.next()) throw new SQLException("Это событие уже обработано для данной точки.");
                    }
                }
                try (PreparedStatement newer = connection.prepareStatement(
                        "SELECT 1 FROM price_changes WHERE marking=? COLLATE NOCASE "
                                + "AND (detected_at>? OR (detected_at=? AND id>?)) LIMIT 1")) {
                    newer.setString(1, marking); newer.setDouble(2, detectedAt);
                    newer.setDouble(3, detectedAt); newer.setLong(4, changeId);
                    try (ResultSet row = newer.executeQuery()) {
                        if (row.next()) throw new SQLException("Есть более новое событие этого товара. Обновите список и выберите последнее.");
                    }
                }
                JsonNode root;
                try { root = JSON.readTree(pricesJson == null ? "{}" : pricesJson); }
                catch (JsonProcessingException error) { throw new SQLException("Не удалось прочитать новые цены события.", error); }
                // Shelf-item columns correspond to SetRetailX price numbers 2 (regular) and 4 (discount)
                // in pricesMap. prices99Map is a separate price source, not the point's discount column.
                String regular = priceValue(root.path("pricesMap").path("2"));
                String promo = priceValue(root.path("pricesMap").path("4"));
                if (regular.isBlank() && promo.isBlank()) {
                    throw new SQLException("В выбранном событии нет обычной или скидочной цены для ценника.");
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE sales_point_items SET name=CASE WHEN ?<>'' THEN ? ELSE name END,"
                                + "price=CASE WHEN ?<>'' THEN ? ELSE price END,"
                                + "discount_price=CASE WHEN ?<>'' THEN ? ELSE discount_price END,updated_at=? "
                                + "WHERE point_id=? AND article=? COLLATE NOCASE")) {
                    update.setString(1, name == null ? "" : name.trim()); update.setString(2, name == null ? "" : name.trim());
                    update.setString(3, regular); update.setString(4, regular);
                    update.setString(5, promo); update.setString(6, promo);
                    update.setLong(7, System.currentTimeMillis()); update.setLong(8, pointId); update.setString(9, marking);
                    if (update.executeUpdate() == 0) throw new SQLException("Не удалось обновить ценник точки.");
                }
                long processedAt = System.currentTimeMillis();
                try (PreparedStatement mark = connection.prepareStatement(
                        "INSERT INTO sales_point_price_change_status(point_id,change_id,processed_at) VALUES(?,?,?)")) {
                    mark.setLong(1, pointId); mark.setLong(2, changeId); mark.setLong(3, processedAt); mark.executeUpdate();
                }
                connection.commit();
                return new AppliedPointPriceChange(pointId, changeId, marking, name == null ? "" : name,
                        regular, promo, processedAt);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
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

    /** Returns the most recently captured regular and promotional shelf prices for one article. */
    public ShelfPrice latestShelfPrice(String marking) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT prices_json FROM price_snapshot_history WHERE marking=? ORDER BY captured_at DESC, id DESC LIMIT 1")) {
            statement.setString(1, marking);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                JsonNode root = JSON.readTree(row.getString("prices_json"));
                JsonNode prices = root == null ? null : root.path("pricesMap");
                return new ShelfPrice(priceValue(prices == null ? null : prices.path("2")),
                        priceValue(prices == null ? null : prices.path("4")));
            } catch (JsonProcessingException error) {
                throw new SQLException("Не удалось прочитать последний ценник из истории.", error);
            }
        }
    }

    private static String priceValue(JsonNode price) {
        if (price == null || price.isMissingNode() || price.isNull()) return "";
        JsonNode value = price.isObject() ? price.path("value") : price;
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) return "";
        try {
            return new java.math.BigDecimal(value.asText()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return "";
        }
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

    /** Sales-bearing articles, optionally filtered by the article, product name, or barcode. */
    public List<SalesArticle> searchSalesArticles(String query, Double fromInclusive,
                                                   Double toExclusive, int limit) throws SQLException {
        List<SalesArticle> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT item, MAX(name) AS name, MAX(barcode) AS barcode, "
                + "SUM(quantity) AS units, SUM(cost) AS revenue, COUNT(DISTINCT check_id) AS checks "
                + "FROM sales_positions WHERE item IS NOT NULL AND TRIM(item)<>'' ");
        if (fromInclusive != null) sql.append("AND sold_at>=? ");
        if (toExclusive != null) sql.append("AND sold_at<? ");
        if (query != null && !query.isBlank()) sql.append("AND (item LIKE ? COLLATE NOCASE OR name LIKE ? COLLATE NOCASE "
                + "OR barcode LIKE ? COLLATE NOCASE) ");
        sql.append("GROUP BY item ORDER BY revenue DESC, item COLLATE NOCASE LIMIT ?");
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            if (fromInclusive != null) statement.setDouble(parameter++, fromInclusive);
            if (toExclusive != null) statement.setDouble(parameter++, toExclusive);
            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.trim() + "%";
                statement.setString(parameter++, pattern);
                statement.setString(parameter++, pattern);
                statement.setString(parameter++, pattern);
            }
            statement.setInt(parameter, Math.max(1, Math.min(limit, 5000)));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new SalesArticle(rows.getString("item"), rows.getString("name"),
                        rows.getString("barcode"), rows.getDouble("units"), rows.getDouble("revenue"),
                        rows.getInt("checks")));
            }
        }
        return result;
    }

    /** Daily combined sales totals for a chosen set of articles and optional time bounds. */
    public List<SalesDay> salesHistory(List<String> markings, Double fromInclusive,
                                       Double toExclusive) throws SQLException {
        if (markings == null || markings.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder("SELECT CAST(strftime('%s', date(sold_at, 'unixepoch', 'localtime')) AS INTEGER) AS day, "
                + "SUM(quantity) AS units, SUM(cost) AS revenue, COUNT(DISTINCT check_id) AS checks "
                + "FROM sales_positions WHERE item IN (");
        sql.append("?,".repeat(markings.size()));
        sql.setLength(sql.length() - 1);
        sql.append(") ");
        if (fromInclusive != null) sql.append("AND sold_at>=? ");
        if (toExclusive != null) sql.append("AND sold_at<? ");
        sql.append("GROUP BY day ORDER BY day DESC");
        List<SalesDay> result = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            for (String marking : markings) statement.setString(parameter++, marking);
            if (fromInclusive != null) statement.setDouble(parameter++, fromInclusive);
            if (toExclusive != null) statement.setDouble(parameter, toExclusive);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new SalesDay(rows.getLong("day"), rows.getDouble("units"),
                        rows.getDouble("revenue"), rows.getInt("checks")));
            }
        }
        return result;
    }

    /** Receipt lines for selected articles; capped to keep the desktop view responsive. */
    public List<SalesPosition> salesPositions(List<String> markings, Double fromInclusive,
                                               Double toExclusive, int limit) throws SQLException {
        if (markings == null || markings.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder("SELECT check_id, check_number, sold_at, date_commit, name, item, barcode, "
                + "quantity, unit, price, cost, discount FROM sales_positions WHERE item IN (");
        sql.append("?,".repeat(markings.size()));
        sql.setLength(sql.length() - 1);
        sql.append(") ");
        if (fromInclusive != null) sql.append("AND sold_at>=? ");
        if (toExclusive != null) sql.append("AND sold_at<? ");
        sql.append("ORDER BY sold_at DESC, position_order DESC LIMIT ?");
        List<SalesPosition> result = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            for (String marking : markings) statement.setString(parameter++, marking);
            if (fromInclusive != null) statement.setDouble(parameter++, fromInclusive);
            if (toExclusive != null) statement.setDouble(parameter++, toExclusive);
            statement.setInt(parameter, Math.max(1, Math.min(limit, 20_000)));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new SalesPosition(rows.getLong("check_id"), rows.getString("check_number"),
                        rows.getDouble("sold_at"), rows.getDouble("date_commit"), rows.getString("name"),
                        rows.getString("item"), rows.getString("barcode"), rows.getDouble("quantity"),
                        rows.getString("unit"), rows.getDouble("price"), rows.getDouble("cost"),
                        rows.getDouble("discount")));
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
        long runId = 0;
        try {
            runId = startRun(task.taskId());
            String result = switch (task.taskId()) {
                case DISCOVERY_TASK_ID -> runCandidateDiscovery();
                case SALES_TASK_ID -> runSalesHistory(runId);
                default -> runPriceSnapshots();
            };
            finish(task.taskId(), runId, "Успешно", result);
        } catch (Throwable error) {
            Throwable cause = unwrap(error);
            String result = errorText(cause);
            try { finish(task.taskId(), runId, "Ошибка", result); }
            catch (SQLException finishError) { cause.addSuppressed(finishError); }
        } finally {
            isTaskRunning.set(false);
        }
    }

    private static String errorText(Throwable error) {
        Throwable cause = unwrap(error);
        return cause.getClass().getSimpleName() + ": "
                + (cause.getMessage() == null ? cause.toString() : cause.getMessage());
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

    private String runSalesHistory(long runId) {
        Instant until = Instant.now();
        Instant since = salesSyncWatermark();
        if (since == null) since = until.minusSeconds(SALES_INITIAL_LOOKBACK_DAYS * 24L * 60 * 60);
        else since = since.minusSeconds(60 * 60); // overlap an hour to cover late-arriving receipts
        // SearchArgumentVO date values have second precision.
        since = Instant.ofEpochSecond(since.getEpochSecond());
        until = Instant.ofEpochSecond(until.getEpochSecond());
        List<String> productItems;
        try {
            productItems = salesPoints.productItemOrBarcodes();
        } catch (SQLException error) {
            throw new IllegalStateException("Не удалось прочитать товары торговых точек для поиска чеков.", error);
        }
        if (productItems.isEmpty()) {
            throw new IllegalStateException("В созданных торговых точках нет товаров для фильтра ProductItemOrBarcode; "
                    + "поиск чеков не выполнялся, watermark не обновлён.");
        }

        long searchTask = startSubtask(runId, null, "Поиск чеков по " + productItems.size()
                + " товарам торговых точек: " + since + " — " + until);
        List<JsonNode> checks;
        try {
            SalesCheckSearchResult search = findSalesChecks(since, until, productItems);
            checks = search.checks();
            finishSubtask(searchTask, "Успешно", "Найдено чеков: " + checks.size()
                    + "; запросов findChecks: " + search.requestCount());
        } catch (Throwable error) {
            finishSubtask(searchTask, "Ошибка", errorText(error));
            throw error;
        }
        List<JsonNode> pendingChecks = new ArrayList<>();
        for (JsonNode summary : checks) {
            long id = checkId(summary);
            if (!isSalesCheckProcessed(id)) pendingChecks.add(summary);
        }
        int loaded = 0;
        int positions = 0;
        for (JsonNode check : pendingChecks) {
            long id = checkId(check);
            long checkTask = startSubtask(runId, null, "Сохранение чека " + id);
            try {
                JsonNode fullCheck = check;
                for (String property : List.of("check", "receipt", "data")) {
                    if (fullCheck.path(property).isObject()) { fullCheck = fullCheck.path(property); break; }
                }
                if (!fullCheck.path("positions").isArray()) {
                    JsonNode fullResponse = connector.findCheck(check).join();
                    fullCheck = fullCheckFromResponse(fullResponse, id, 0);
                    if (fullCheck == null) {
                        throw new IllegalStateException("findCheck не вернул полный чек " + id
                                + " с positions; watermark не обновлён.");
                    }
                }
                int saved = recordSalesCheck(fullCheck);
                positions += saved;
                loaded++;
                finishSubtask(checkTask, "Успешно", "Сохранено позиций: " + saved);
            } catch (Throwable error) {
                finishSubtask(checkTask, "Ошибка", errorText(error));
                throw error;
            }
        }
        long watermarkTask = startSubtask(runId, null, "Сохранение границы синхронизации");
        try {
            saveSalesSyncWatermark(until);
            finishSubtask(watermarkTask, "Успешно", "Синхронизировано до: " + until);
        } catch (Throwable error) {
            finishSubtask(watermarkTask, "Ошибка", errorText(error));
            throw error;
        }
        return "Период: " + since + " — " + until + "; найдено чеков: "
                + checks.size() + "; новых чеков: " + loaded + "; позиций сохранено: " + positions;
    }

    /** Splits saturated time ranges until each response is known to be complete. */
    private SalesCheckSearchResult findSalesChecks(Instant from, Instant to, List<String> productItems) {
        List<JsonNode> rows = checkRows(
                connector.findChecks(from, to, SALES_CHECK_LIMIT, productItems).join());
        if (rows.size() < SALES_CHECK_LIMIT) return new SalesCheckSearchResult(rows, 1);

        long fromSecond = from.getEpochSecond();
        long toSecond = to.getEpochSecond();
        if (toSecond - fromSecond <= 3) {
            throw new IllegalStateException("findChecks вернул лимит " + SALES_CHECK_LIMIT
                    + " даже в интервале не длиннее трёх секунд; нельзя подтвердить полноту списка чеков, "
                    + "watermark не обновлён во избежание потери чеков.");
        }

        long middleSecond = fromSecond + (toSecond - fromSecond) / 2;
        Instant middle = Instant.ofEpochSecond(middleSecond);
        SalesCheckSearchResult earlier = findSalesChecks(from, middle, productItems);
        // Overlap by one second so a check on the split is found even if one bound is exclusive.
        SalesCheckSearchResult later = findSalesChecks(middle.minusSeconds(1), to, productItems);

        // Check IDs make the overlap harmless.
        Map<Long, JsonNode> uniqueChecks = new LinkedHashMap<>();
        for (JsonNode check : earlier.checks()) uniqueChecks.putIfAbsent(checkId(check), check);
        for (JsonNode check : later.checks()) uniqueChecks.putIfAbsent(checkId(check), check);
        return new SalesCheckSearchResult(new ArrayList<>(uniqueChecks.values()),
                1 + earlier.requestCount() + later.requestCount());
    }

    private record SalesCheckSearchResult(List<JsonNode> checks, int requestCount) { }

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
        JsonNode rows = findCheckRows(result, 0);
        if (rows == null && result.isObject() && hasCheckId(result)) {
            rows = JSON.createArrayNode().add(result);
        }
        if (rows == null && result.isArray() && isCheckIdArray(result)) {
            var normalized = JSON.createArrayNode();
            for (JsonNode id : result) normalized.addObject().put("id", id.asLong());
            rows = normalized;
        }
        if (rows == null) {
            String shape = result.isObject()
                    ? "объект с полями " + fieldNames(result)
                    : "значение типа " + result.getNodeType();
            throw new IllegalStateException("findChecks вернул неожиданный формат списка (" + shape
                    + "); история продаж остановлена без продвижения watermark. Ответ (фрагмент): "
                    + diagnosticSnippet(result));
        }
        List<JsonNode> checks = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isObject()) {
                throw new IllegalStateException("findChecks содержит строку неожиданного формата. Строка (фрагмент): "
                        + diagnosticSnippet(row));
            }
            checkId(row); // Fail rather than advance the watermark past an unidentifiable receipt.
            checks.add(row);
        }
        return checks;
    }

    /** Finds the complete receipt in JSON-RPC wrappers without mistaking nested position rows for checks. */
    private static JsonNode fullCheckFromResponse(JsonNode node, long expectedId, int depth) {
        if (node == null || node.isNull() || node.isMissingNode() || depth > 10) return null;
        if (node.isTextual()) {
            String value = node.asText().trim();
            if (value.startsWith("{") || value.startsWith("[")) {
                try { return fullCheckFromResponse(JSON.readTree(value), expectedId, depth + 1); }
                catch (JsonProcessingException ignored) { return null; }
            }
            return null;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = fullCheckFromResponse(child, expectedId, depth + 1);
                if (found != null) return found;
            }
            return null;
        }
        if (!node.isObject()) return null;
        if (node.path("positions").isArray()) {
            JsonNode id = node.path("id");
            if (id.isMissingNode() || id.isNull() || id.asText().isBlank()) id = node.path("checkId");
            if (!id.isMissingNode() && !id.isNull() && id.asLong(Long.MIN_VALUE) == expectedId) return node;
        }
        for (String property : List.of("check", "receipt", "data", "result", "value", "object")) {
            JsonNode found = fullCheckFromResponse(node.get(property), expectedId, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** Finds a receipt array in common JSON-RPC collection and paging wrappers. */
    private static JsonNode findCheckRows(JsonNode node, int depth) {
        if (node == null || depth > 8) return null;
        if (node.isTextual()) {
            String value = node.asText().trim();
            if (value.startsWith("[") || value.startsWith("{")) {
                try { return findCheckRows(JSON.readTree(value), depth + 1); }
                catch (JsonProcessingException ignored) { return null; }
            }
            return null;
        }
        if (node.isArray()) {
            if (isCheckRowArray(node)) return node;
            if (isCheckIdArray(node)) {
                var normalized = JSON.createArrayNode();
                for (JsonNode id : node) normalized.addObject().put("id", id.asLong());
                return normalized;
            }
            JsonNode emptyArray = null;
            for (JsonNode child : node) {
                JsonNode found = findCheckRows(child, depth + 1);
                if (found != null && !found.isEmpty()) return found;
                if (found != null) emptyArray = found;
            }
            return emptyArray;
        }
        if (!node.isObject()) return null;

        for (String property : List.of("checks", "list", "items", "result", "data", "rows",
                "records", "objects", "values", "content", "elements")) {
            JsonNode nested = node.get(property);
            if (nested == null) continue;
            JsonNode found = findCheckRows(nested, depth + 1);
            if (found != null) return found;
        }
        if (hasCheckId(node)) return JSON.createArrayNode().add(node);
        // Some Java collection serializers wrap the actual values under implementation-specific keys.
        for (var fields = node.fields(); fields.hasNext();) {
            var field = fields.next();
            if (List.of("@class", "class", "total", "count", "size").contains(field.getKey())) continue;
            JsonNode found = findCheckRows(field.getValue(), depth + 1);
            if (found != null && !found.isEmpty()) return found;
        }
        return null;
    }

    private static boolean isCheckRowArray(JsonNode rows) {
        if (!rows.isArray()) return false;
        for (JsonNode row : rows) {
            if (!row.isObject() || !hasCheckId(row)) return false;
        }
        return true;
    }

    private static boolean hasCheckId(JsonNode summary) {
        JsonNode id = summary.path("id");
        if (id.isMissingNode() || id.isNull() || id.asText().isBlank()) id = summary.path("checkId");
        return id.isValueNode() && id.asLong(0) > 0;
    }

    private static boolean isCheckIdArray(JsonNode rows) {
        if (!rows.isArray() || rows.isEmpty()) return false;
        for (JsonNode row : rows) {
            if (!row.isIntegralNumber() || row.asLong(0) <= 0) return false;
        }
        return true;
    }

    private static String fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names.isEmpty() ? "нет" : String.join(", ", names);
    }

    /** Keeps enough of an unexpected response in the task error to diagnose its shape without logging a full payload. */
    private static String diagnosticSnippet(JsonNode node) {
        final int maxLength = 600;
        String json = node == null ? "null" : node.toString().replaceAll("\\s+", " ").trim();
        if (json.length() <= maxLength) return json;
        return json.substring(0, maxLength) + "… [фрагмент усечён; всего символов: " + json.length() + "]";
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

    public List<SubtaskRun> subtasks(long runId) throws SQLException {
        List<SubtaskRun> result = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT id, parent_id, name, started_at, finished_at, status, result FROM task_subtasks WHERE run_id=? ORDER BY id")) {
            query.setLong(1, runId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    long parent = rows.getLong("parent_id");
                    Long parentId = rows.wasNull() ? null : parent;
                    result.add(new SubtaskRun(rows.getLong("id"), parentId, rows.getString("name"),
                            rows.getDouble("started_at"), nullableDouble(rows, "finished_at"),
                            rows.getString("status"), rows.getString("result")));
                }
            }
        }
        return result;
    }

    private long startRun(String taskId) throws SQLException {
        try (Connection connection = connect(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO task_runs(task_id, started_at, status, result) VALUES (?, ?, 'Выполняется', '')",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, taskId);
            insert.setDouble(2, nowSeconds());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("Не удалось получить ID запуска");
            }
        }
    }

    private long startSubtask(long runId, Long parentId, String name) {
        try (Connection connection = connect(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO task_subtasks(run_id, parent_id, name, started_at, status) VALUES (?, ?, ?, ?, 'Выполняется')",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, runId);
            if (parentId == null) insert.setNull(2, java.sql.Types.INTEGER); else insert.setLong(2, parentId);
            insert.setString(3, name);
            insert.setDouble(4, nowSeconds());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("Не удалось получить ID подзадачи");
            }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    private void finishSubtask(long id, String status, String result) {
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE task_subtasks SET finished_at=?, status=?, result=? WHERE id=?")) {
            update.setDouble(1, nowSeconds()); update.setString(2, status); update.setString(3, result); update.setLong(4, id);
            update.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }

    private void finish(String taskId, long runId, String status, String result) throws SQLException {
        double finishedAt = nowSeconds();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE long_tasks SET last_finished_at=?, last_status=?, last_result=? WHERE task_id=?")) {
                statement.setDouble(1, finishedAt); statement.setString(2, status); statement.setString(3, result);
                statement.setString(4, taskId); statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE task_runs SET finished_at=?, status=?, result=? WHERE id=?")) {
                statement.setDouble(1, finishedAt); statement.setString(2, status);
                statement.setString(3, result); statement.setLong(4, runId); statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE task_subtasks SET finished_at=?, status=?, result=? WHERE run_id=? AND status='Выполняется'")) {
                statement.setDouble(1, finishedAt); statement.setString(2, "Прервано");
                statement.setString(3, result); statement.setLong(4, runId); statement.executeUpdate();
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
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS sales_point_price_change_status ("
                    + "point_id INTEGER NOT NULL, change_id INTEGER NOT NULL, processed_at INTEGER NOT NULL, "
                    + "PRIMARY KEY(point_id,change_id))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sales_point_change_status_event "
                    + "ON sales_point_price_change_status(change_id,point_id)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS task_runs (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "task_id TEXT NOT NULL, started_at REAL NOT NULL, finished_at REAL, status TEXT NOT NULL, "
                    + "result TEXT NOT NULL DEFAULT '')");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_task_runs_task_started ON task_runs(task_id, started_at DESC)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS task_subtasks (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "run_id INTEGER NOT NULL REFERENCES task_runs(id), parent_id INTEGER REFERENCES task_subtasks(id), "
                    + "name TEXT NOT NULL, started_at REAL NOT NULL, finished_at REAL, status TEXT NOT NULL, "
                    + "result TEXT NOT NULL DEFAULT '')");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_task_subtasks_run ON task_subtasks(run_id, id)");
            statement.executeUpdate("UPDATE task_subtasks SET finished_at=" + nowSeconds()
                    + ", status='Прервано', result='Приложение закрыто до завершения.' WHERE status='Выполняется'");
            statement.executeUpdate("UPDATE task_runs SET finished_at=" + nowSeconds()
                    + ", status='Прервано', result='Приложение закрыто до завершения.' WHERE status='Выполняется'");
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
    public record PriceChange(long id, String marking, String name, String barcode, double detectedAt,
                              String oldPricesJson, String newPricesJson) { }
    public record SalesPointPriceChange(long id, long pointId, String marking, String pointItemName,
                                        String name, String barcode, double detectedAt, String oldPricesJson,
                                        String newPricesJson, Double processedAt) { }
    public record AppliedPointPriceChange(long pointId, long changeId, String marking, String name,
                                          String regularPrice, String discountPrice, long processedAt) { }
    public record PriceSnapshot(String name, String barcode, double capturedAt, String pricesJson) { }
    public record ShelfPrice(String regularPrice, String discountPrice) { }
    public record SalesDay(long dayEpochSeconds, double units, double revenue, int checks) { }
    public record SalesArticle(String marking, String name, String barcode, double units,
                               double revenue, int checks) { }
    public record SalesPosition(long checkId, String checkNumber, double soldAt, double dateCommit, String name, String item,
                                String barcode, double quantity, String unit, double price, double cost,
                                double discount) { }
    public record TaskRun(long id, String taskId, double startedAt, Double finishedAt,
                          String status, String result) { }
    public record SubtaskRun(long id, Long parentId, String name, double startedAt, Double finishedAt,
                             String status, String result) { }
    private record SnapshotCounts(int snapshots, int changes, int unmatched) { }
}
