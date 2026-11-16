package com.setretailx.printer.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.model.ChangedLabelPrint;
import com.setretailx.printer.model.Price;
import com.setretailx.printer.model.PriceType;
import com.setretailx.printer.model.PrintHistoryEntry;
import com.setretailx.printer.model.Product;

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
import java.util.Map;

/** Archive of successfully submitted labels that were queued from detected price changes. */
public final class ChangedLabelPrintHistoryRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final java.nio.file.Path path;

    public ChangedLabelPrintHistoryRepository() { this(AppConfig.PRINT_HISTORY_PATH); }
    public ChangedLabelPrintHistoryRepository(java.nio.file.Path path) { this.path = path.toAbsolutePath().normalize(); }

    public void addSuccessful(List<PrintHistoryEntry> entries) throws SQLException {
        if (entries == null || entries.isEmpty()) return;
        ensureSchema();
        String printedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO changed_label_print_history(printed_at, entry_json) VALUES (?, ?)")) {
                for (PrintHistoryEntry entry : entries) {
                    insert.setString(1, printedAt);
                    insert.setString(2, encode(entry));
                    insert.addBatch();
                }
                insert.executeBatch();
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                if (error instanceof SQLException sql) throw sql;
                throw new SQLException("Не удалось сохранить ценники изменений в историю.", error);
            }
        }
    }

    public List<ChangedLabelPrint> list() throws SQLException {
        ensureSchema();
        List<ChangedLabelPrint> result = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT id, printed_at, entry_json FROM changed_label_print_history ORDER BY id DESC");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) result.add(new ChangedLabelPrint(rows.getLong("id"), rows.getString("printed_at"),
                    decode(rows.getString("entry_json"))));
        } catch (java.io.IOException error) {
            throw new SQLException("Не удалось прочитать историю печати изменённых ценников.", error);
        }
        return result;
    }

    private static String encode(PrintHistoryEntry entry) throws java.io.IOException {
        ObjectNode row = JSON.createObjectNode();
        Product product = entry.product();
        row.put("name", product.name()); row.put("stCode", product.stCode());
        row.put("vendorCode", product.vendorCode()); row.put("barcode", product.barcode());
        row.put("uom", product.uom()); row.put("source", product.source());
        if (product.price() != null) row.put("priceKopecks", product.price().kopecks());
        row.put("totalQuantity", product.totalQuantity());
        ObjectNode details = row.putObject("details");
        product.details().forEach(details::put);
        row.put("formatKey", entry.formatKey());
        if (entry.amount() == null) row.putNull("amount"); else row.put("amount", entry.amount());
        row.put("quantity", entry.quantity());
        row.put("priceType", entry.priceType() == PriceType.PROMO ? "promo" : "regular");
        return JSON.writeValueAsString(row);
    }

    private static PrintHistoryEntry decode(String json) throws java.io.IOException {
        JsonNode row = JSON.readTree(json);
        Map<String, String> details = new java.util.LinkedHashMap<>();
        if (row.path("details").isObject()) row.path("details").fields().forEachRemaining(field ->
                details.put(field.getKey(), field.getValue().asText("")));
        JsonNode priceNode = row.get("priceKopecks");
        Product product = new Product(text(row, "stCode"), text(row, "name"), text(row, "vendorCode"),
                text(row, "barcode"), text(row, "uom"), text(row, "source"),
                priceNode == null || !priceNode.canConvertToLong() ? null : new Price(priceNode.longValue()),
                text(row, "totalQuantity"), details);
        JsonNode amount = row.get("amount");
        Integer amountValue = amount == null || amount.isNull() || !amount.canConvertToInt() ? null : amount.intValue();
        return new PrintHistoryEntry(product, text(row, "formatKey"), amountValue,
                Math.max(1, row.path("quantity").asInt(1)), PriceType.fromStoredValue(text(row, "priceType")));
    }

    private static String text(JsonNode row, String key) {
        JsonNode value = row.path(key);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private void ensureSchema() throws SQLException {
        try { Files.createDirectories(path.getParent()); }
        catch (java.io.IOException error) { throw new SQLException("Не удалось создать папку истории печати.", error); }
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS changed_label_print_history ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, printed_at TEXT NOT NULL, entry_json TEXT NOT NULL)");
        }
    }

    private Connection connect() throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + path); }
}
