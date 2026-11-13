package com.setretailx.printer.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.model.PrintBatch;
import com.setretailx.printer.model.PrintHistoryEntry;
import com.setretailx.printer.model.PriceType;
import com.setretailx.printer.model.Price;
import com.setretailx.printer.model.Product;

import java.io.IOException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SQLite archive of print batches; schema and entry JSON follow the Python implementation. */
public final class PrintHistoryRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final java.nio.file.Path path;

    public PrintHistoryRepository() {
        this(AppConfig.PRINT_HISTORY_PATH);
    }

    public PrintHistoryRepository(java.nio.file.Path path) {
        this.path = path;
    }

    public long addBatch(List<PrintHistoryEntry> entries) throws SQLException, IOException {
        ensureSchema();
        String createdAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO print_batches (created_at, status, entries_json) VALUES (?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, createdAt);
            statement.setString(2, "Отправляется");
            statement.setString(3, encodeEntries(entries));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
            throw new SQLException("SQLite не вернула id созданной партии.");
        }
    }

    public void finishBatch(long batchId, List<Integer> successfulIndexes, String status)
            throws SQLException, IOException {
        ensureSchema();
        ArrayNode indexes = JSON.createArrayNode();
        successfulIndexes.forEach(indexes::add);
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE print_batches SET status = ?, successful_indexes_json = ? WHERE id = ?")) {
            statement.setString(1, status);
            statement.setString(2, JSON.writeValueAsString(indexes));
            statement.setLong(3, batchId);
            statement.executeUpdate();
        }
    }

    public void failBatch(Long batchId) throws SQLException {
        if (batchId == null) return;
        ensureSchema();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE print_batches SET status = ? WHERE id = ?")) {
            statement.setString(1, "Ошибка отправки");
            statement.setLong(2, batchId);
            statement.executeUpdate();
        }
    }

    public List<PrintBatch> batches() throws SQLException, IOException {
        ensureSchema();
        List<PrintBatch> batches = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, created_at, status, entries_json, successful_indexes_json "
                             + "FROM print_batches ORDER BY id DESC");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                batches.add(new PrintBatch(rows.getLong("id"), rows.getString("created_at"),
                        rows.getString("status"), decodeEntries(rows.getString("entries_json")),
                        decodeIndexes(rows.getString("successful_indexes_json"))));
            }
        }
        return batches;
    }

    private void ensureSchema() throws SQLException {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
        } catch (IOException error) {
            throw new SQLException("Не удалось создать папку истории печати: " + path, error);
        }
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS print_batches ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "created_at TEXT NOT NULL, status TEXT NOT NULL, entries_json TEXT NOT NULL, "
                    + "successful_indexes_json TEXT NOT NULL DEFAULT '[]')");
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
    }

    private static String encodeEntries(List<PrintHistoryEntry> entries) throws IOException {
        ArrayNode array = JSON.createArrayNode();
        for (PrintHistoryEntry entry : entries) {
            Product product = entry.product();
            ObjectNode node = array.addObject();
            node.put("name", product.name());
            node.put("st_code", product.stCode());
            node.put("vendor_code", product.vendorCode());
            node.put("barcode", product.barcode());
            node.put("uom", product.uom());
            node.put("source", product.source());
            if (entry.formatKey().isEmpty()) node.putNull("format");
            else node.put("format", entry.formatKey());
            if (entry.amount() == null) node.putNull("amount");
            else node.put("amount", entry.amount());
            node.put("quantity", entry.quantity());
            node.put("price_type", entry.priceType() == PriceType.PROMO ? "promo" : "regular");
            // Keep the established Python queue fields at the top level so batches can be
            // inspected/restored by either implementation, while preserving Java-only data.
            ObjectNode details = node.putObject("_java_snapshot");
            if (product.price() != null) details.put("price_kopecks", product.price().kopecks());
            details.put("total_quantity", product.totalQuantity());
            ObjectNode productDetails = details.putObject("details");
            product.details().forEach(productDetails::put);
        }
        return JSON.writeValueAsString(array);
    }

    private static List<PrintHistoryEntry> decodeEntries(String json) throws IOException {
        JsonNode array = JSON.readTree(json);
        List<PrintHistoryEntry> entries = new ArrayList<>();
        if (array == null || !array.isArray()) return entries;
        for (JsonNode node : array) {
            Map<String, String> details = new LinkedHashMap<>();
            JsonNode snapshot = node.path("_java_snapshot");
            JsonNode detailNode = snapshot.has("details") ? snapshot.path("details") : node.path("details");
            if (detailNode.isObject()) detailNode.fields().forEachRemaining(field ->
                    details.put(field.getKey(), field.getValue().asText("")));
            JsonNode priceNode = snapshot.has("price_kopecks")
                    ? snapshot.get("price_kopecks") : node.get("price_kopecks");
            Price price = priceNode == null || !priceNode.canConvertToLong()
                    ? null : new Price(priceNode.longValue());
            Product product = new Product(text(node, "st_code"), text(node, "name"),
                    text(node, "vendor_code"), text(node, "barcode"), text(node, "uom"),
                    text(node, "source"), price,
                    snapshot.has("total_quantity") ? text(snapshot, "total_quantity") : "", details);
            JsonNode format = node.get("format");
            String formatKey = format == null || format.isNull() ? "" : format.asText("");
            JsonNode amount = node.get("amount");
            Integer amountValue = amount == null || amount.isNull() || !amount.canConvertToInt()
                    ? null : amount.intValue();
            JsonNode priceType = node.get("price_type");
            entries.add(new PrintHistoryEntry(product, formatKey, amountValue,
                    Math.max(1, node.path("quantity").asInt(1)),
                    PriceType.fromStoredValue(priceType == null || priceType.isNull()
                            ? "" : priceType.asText(""))));
        }
        return entries;
    }

    private static List<Integer> decodeIndexes(String json) throws IOException {
        JsonNode array = JSON.readTree(json == null ? "[]" : json);
        List<Integer> indexes = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode value : array) if (value.canConvertToInt()) indexes.add(value.intValue());
        }
        return indexes;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? "" : value.asText("");
    }
}
