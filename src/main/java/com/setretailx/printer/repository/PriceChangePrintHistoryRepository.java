package com.setretailx.printer.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.model.PriceChangePrintEntry;
import com.setretailx.printer.model.PriceChangePrintReport;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** SQLite archive for printed price-change reports, separate from regular label-print batches. */
public final class PriceChangePrintHistoryRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final java.nio.file.Path path;

    public PriceChangePrintHistoryRepository() { this(AppConfig.PRINT_HISTORY_PATH); }

    public PriceChangePrintHistoryRepository(java.nio.file.Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public void migrate() throws SQLException { ensureSchema(); }

    public long addReport(List<PriceChangePrintEntry> entries) throws SQLException {
        ensureSchema();
        ArrayNode rows = JSON.createArrayNode();
        for (PriceChangePrintEntry entry : entries) {
            ObjectNode row = rows.addObject();
            row.put("article", entry.article());
            row.put("product", entry.product());
            row.put("barcode", entry.barcode());
            row.put("priceType", entry.priceType());
            row.put("oldPrice", entry.oldPrice());
            row.put("newPrice", entry.newPrice());
            row.put("delta", entry.delta());
        }
        try (Connection connection = connect(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO price_change_print_reports(created_at, entries_json) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
            insert.setString(2, JSON.writeValueAsString(rows));
            insert.executeUpdate();
            try (ResultSet key = insert.getGeneratedKeys()) {
                if (key.next()) return key.getLong(1);
            }
            throw new SQLException("SQLite не вернула id отчёта изменений цен.");
        } catch (java.io.IOException error) {
            throw new SQLException("Не удалось подготовить отчёт изменений цен для истории.", error);
        }
    }

    public List<PriceChangePrintReport> reports() throws SQLException {
        ensureSchema();
        List<PriceChangePrintReport> reports = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT id, created_at, entries_json FROM price_change_print_reports ORDER BY id DESC");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) reports.add(new PriceChangePrintReport(rows.getLong("id"),
                    rows.getString("created_at"), decode(rows.getString("entries_json"))));
        } catch (java.io.IOException error) {
            throw new SQLException("Не удалось прочитать историю печати изменений цен.", error);
        }
        return reports;
    }

    private static List<PriceChangePrintEntry> decode(String json) throws java.io.IOException {
        JsonNode rows = JSON.readTree(json == null ? "[]" : json);
        List<PriceChangePrintEntry> entries = new ArrayList<>();
        if (rows != null && rows.isArray()) for (JsonNode row : rows) {
            entries.add(new PriceChangePrintEntry(text(row, "article"), text(row, "product"),
                    text(row, "barcode"), text(row, "priceType"), text(row, "oldPrice"),
                    text(row, "newPrice"), text(row, "delta")));
        }
        return entries;
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private void ensureSchema() throws SQLException {
        try { Files.createDirectories(path.getParent()); }
        catch (java.io.IOException error) { throw new SQLException("Не удалось создать папку истории печати.", error); }
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS price_change_print_reports ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, created_at TEXT NOT NULL, entries_json TEXT NOT NULL)");
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + path);
    }
}
