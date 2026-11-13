package com.setretailx.printer.service;

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
    public static final String DISCOVERY_TASK_NAME = "Поиск товаров с изменением цен";
    public static final String SNAPSHOT_TASK_NAME = "Снимки и сравнение цен";
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
        this(connector, defaultDatabasePath());
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
        try {
            String result = task.taskId().equals(DISCOVERY_TASK_ID)
                    ? runCandidateDiscovery() : runPriceSnapshots();
            finish(task.taskId(), "Успешно", result);
        } catch (Throwable error) {
            Throwable cause = unwrap(error);
            String result = cause.getClass().getSimpleName() + ": "
                    + (cause.getMessage() == null ? cause.toString() : cause.getMessage());
            try { finish(task.taskId(), "Ошибка", result); }
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

    private int recordCandidates(List<String> rawMarkings, double now) {
        LinkedHashSet<String> markings = new LinkedHashSet<>();
        if (rawMarkings != null) for (String raw : rawMarkings) {
            if (raw != null && !raw.trim().isEmpty()) markings.add(raw.trim());
        }
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            acquireWriteLock(connection);
            for (String marking : markings) {
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT 1 FROM price_candidates WHERE marking=? AND expires_at>?")) {
                    query.setString(1, marking);
                    query.setDouble(2, now);
                    try (ResultSet row = query.executeQuery()) {
                        if (!row.next()) try (PreparedStatement delete = connection.prepareStatement(
                                "DELETE FROM price_snapshots WHERE marking=?")) {
                            delete.setString(1, marking);
                            delete.executeUpdate();
                        }
                    }
                }
            }
            List<String> expired = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT marking FROM price_candidates WHERE expires_at<=?")) {
                query.setDouble(1, now);
                try (ResultSet rows = query.executeQuery()) { while (rows.next()) expired.add(rows.getString(1)); }
            }
            for (String marking : expired) {
                deleteByMarking(connection, "price_snapshots", marking);
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

    private void finish(String taskId, String status, String result) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE long_tasks SET last_finished_at=?, last_status=?, last_result=? WHERE task_id=?")) {
            statement.setDouble(1, nowSeconds()); statement.setString(2, status); statement.setString(3, result);
            statement.setString(4, taskId); statement.executeUpdate();
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
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS price_changes (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "marking TEXT NOT NULL, name TEXT NOT NULL DEFAULT '', barcode TEXT NOT NULL DEFAULT '', detected_at REAL NOT NULL, "
                    + "old_prices_json TEXT NOT NULL, new_prices_json TEXT NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_price_changes_detected ON price_changes(detected_at DESC)");
            statement.executeUpdate("UPDATE long_tasks SET task_id='" + DISCOVERY_TASK_ID + "', name='"
                    + DISCOVERY_TASK_NAME + "' WHERE task_id='price_change_monitor' AND NOT EXISTS "
                    + "(SELECT 1 FROM long_tasks WHERE task_id='" + DISCOVERY_TASK_ID + "')");
            insertTask(connection, DISCOVERY_TASK_ID, DISCOVERY_TASK_NAME);
            insertTask(connection, SNAPSHOT_TASK_ID, SNAPSHOT_TASK_NAME);
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

    private static Path defaultDatabasePath() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        Path base;
        if (os.contains("win")) base = Path.of(System.getenv().getOrDefault("LOCALAPPDATA",
                home.resolve("AppData/Local").toString()));
        else if (os.contains("mac") || os.contains("darwin")) base = home.resolve("Library/Application Support");
        else base = Path.of(System.getenv().getOrDefault("XDG_DATA_HOME", home.resolve(".local/share").toString()));
        return base.resolve("SetRetailX").resolve("printer12.db");
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
    private record SnapshotCounts(int snapshots, int changes, int unmatched) { }
}
