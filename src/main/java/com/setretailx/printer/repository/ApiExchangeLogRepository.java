package com.setretailx.printer.repository;

import com.setretailx.printer.config.AppConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Keeps a bounded local audit trail of completed SetRetailX HTTP exchanges. */
public final class ApiExchangeLogRepository {
    public static final int MAX_EXCHANGES = 120;
    private static final int MAX_FIELD_CHARACTERS = 128 * 1024;
    private final Path path;

    public ApiExchangeLogRepository() { this(AppConfig.API_EXCHANGE_LOG_PATH); }

    public ApiExchangeLogRepository(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public void migrate() throws SQLException {
        try {
            Files.createDirectories(path.getParent());
        } catch (java.io.IOException error) {
            throw new SQLException("Не удалось создать папку журнала SetRetailX.", error);
        }
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA auto_vacuum = INCREMENTAL");
            int autoVacuumMode;
            try (java.sql.ResultSet mode = statement.executeQuery("PRAGMA auto_vacuum")) {
                autoVacuumMode = mode.next() ? mode.getInt(1) : 0;
            }
            if (autoVacuumMode != 2) statement.execute("VACUUM");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS api_exchange_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, occurred_at TEXT NOT NULL, "
                    + "method TEXT NOT NULL, request_body TEXT NOT NULL, response_body TEXT NOT NULL, "
                    + "http_status INTEGER, duration_ms INTEGER NOT NULL, outcome TEXT NOT NULL, error TEXT)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_api_exchange_log_time "
                    + "ON api_exchange_log(id DESC)");
            trim(connection);
            vacuumFreedSpace(connection);
        }
    }

    public void record(String method, String request, String response, Integer httpStatus,
                       long durationMs, String outcome, String error) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO api_exchange_log(occurred_at, method, request_body, response_body, "
                            + "http_status, duration_ms, outcome, error) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, Instant.now().toString());
                insert.setString(2, value(method));
                insert.setString(3, bounded(request));
                insert.setString(4, bounded(response));
                if (httpStatus == null) insert.setNull(5, java.sql.Types.INTEGER);
                else insert.setInt(5, httpStatus);
                insert.setLong(6, Math.max(0, durationMs));
                insert.setString(7, value(outcome));
                insert.setString(8, error);
                insert.executeUpdate();
            }
            trim(connection);
            connection.commit();
            vacuumFreedSpace(connection);
        } catch (SQLException ignored) {
            // Logging must not affect SetRetailX availability or the caller's result.
        }
    }

    /** Returns recent completed HTTP attempts, newest first. */
    public List<ExchangeLogEntry> recent(int limit) throws SQLException {
        List<ExchangeLogEntry> entries = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT id, occurred_at, method, request_body, response_body, http_status, "
                        + "duration_ms, outcome, error FROM api_exchange_log ORDER BY id DESC LIMIT ?")) {
            query.setInt(1, Math.max(1, Math.min(limit, MAX_EXCHANGES)));
            try (java.sql.ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    int status = rows.getInt("http_status");
                    Integer httpStatus = rows.wasNull() ? null : status;
                    entries.add(new ExchangeLogEntry(rows.getLong("id"), rows.getString("occurred_at"),
                            rows.getString("method"), rows.getString("request_body"),
                            rows.getString("response_body"), httpStatus, rows.getLong("duration_ms"),
                            rows.getString("outcome"), rows.getString("error")));
                }
            }
        }
        return entries;
    }

    public record ExchangeLogEntry(long id, String occurredAt, String method, String requestBody,
                                   String responseBody, Integer httpStatus, long durationMs,
                                   String outcome, String error) { }

    private static void trim(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM api_exchange_log WHERE id NOT IN "
                    + "(SELECT id FROM api_exchange_log ORDER BY id DESC LIMIT " + MAX_EXCHANGES + ")");
        }
    }

    private static void vacuumFreedSpace(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA incremental_vacuum(16)");
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private static String value(String value) { return value == null ? "" : value; }

    private static String bounded(String text) {
        String value = value(text);
        if (value.length() <= MAX_FIELD_CHARACTERS) return value;
        return value.substring(0, MAX_FIELD_CHARACTERS) + "\n[журнал: содержимое усечено]";
    }
}
