package com.setretailx.printer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.setretailx.printer.config.AppConfig;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Add-only merge of the explicitly permitted catalog and local workspace tables. */
final class PeerSyncRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final java.nio.file.Path catalogPath = AppConfig.DB_PATH.toAbsolutePath().normalize();
    private final java.nio.file.Path tasksPath = AppConfig.tasksDatabasePath();
    private final DomainChangeRepository domainChanges = new DomainChangeRepository();

    long domainCursor(String parentId) throws SQLException { return domainChanges.cursor(parentId); }
    long domainHighWater() throws SQLException { return domainChanges.highWater(); }
    ObjectNode exportChangesAfter(long cursor) throws SQLException {
        ObjectNode changes = domainChanges.exportAfter(cursor);
        changes.put("changeFeedVersion", 1);
        return changes;
    }
    ChangeApplySummary applyDomainChanges(String parentId, JsonNode events, long nextCursor) throws SQLException {
        int conflicts = domainChanges.apply(parentId, events, nextCursor);
        return new ChangeApplySummary(events.size() - conflicts, conflicts);
    }
    void markDomainBootstrap(String parentId, long cursor) throws SQLException {
        domainChanges.markBootstrap(parentId, cursor);
    }

    ObjectNode exportSnapshot() throws Exception {
        ObjectNode snapshot = JSON.createObjectNode();
        snapshot.put("protocolVersion", 2);
        snapshot.put("snapshotVersion", 2);
        snapshot.set("catalog", exportCatalog());
        snapshot.set("workspace", exportWorkspace());
        return snapshot;
    }

    MergeSummary importSnapshot(JsonNode snapshot) throws Exception {
        if (snapshot == null || !snapshot.isObject() || snapshot.path("protocolVersion").asInt(-1) != 2)
            throw new SQLException("Snapshot синхронизации имеет неизвестную версию.");
        if (snapshot.path("snapshotVersion").asInt(-1) != 2)
            throw new SQLException("Snapshot синхронизации не содержит стабильные идентификаторы доменных объектов.");
        int products = importCatalog(snapshot.path("catalog"));
        int workspace = importWorkspace(snapshot.path("workspace"));
        return new MergeSummary(products, workspace);
    }

    private ObjectNode exportCatalog() throws SQLException {
        ObjectNode catalog = JSON.createObjectNode();
        ArrayNode rows = JSON.createArrayNode();
        catalog.set("products", rows);
        if (!Files.isRegularFile(catalogPath)) return catalog;
        try (Connection connection = connect(catalogPath); Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("SELECT * FROM products LIMIT 200000")) {
            appendRows(result, rows);
        }
        try (Connection connection = connect(catalogPath)) {
            for (String table : List.of("product_categories_path", "product_attributes", "product_attribute_values")) {
                if (hasTable(connection, table)) catalog.set(table, readTable(connection, table));
            }
        }
        return catalog;
    }

    private int importCatalog(JsonNode catalog) throws SQLException {
        JsonNode rows = catalog.path("products");
        if (!Files.isRegularFile(catalogPath) || !rows.isArray()) return 0;
        int added = 0;
        try (Connection connection = connect(catalogPath)) {
            List<String> columns = tableColumns(connection, "products");
            if (columns.isEmpty() || !columns.contains("product_id")) return 0;
            JsonNode firstRow = rows.isEmpty() ? null : rows.get(0);
            List<String> sharedColumns = columns.stream()
                    .filter(column -> firstRow != null && firstRow.has(column)).toList();
            if (!sharedColumns.contains("product_id")) return 0;
            String sql = "INSERT OR IGNORE INTO products (" + joinQuoted(sharedColumns) + ") VALUES ("
                    + String.join(",", java.util.Collections.nCopies(sharedColumns.size(), "?")) + ")";
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                for (JsonNode row : rows) {
                    if (!row.hasNonNull("product_id")) continue;
                    for (int i = 0; i < sharedColumns.size(); i++) {
                        JsonNode value = row.get(sharedColumns.get(i));
                        if (value == null || value.isNull()) insert.setObject(i + 1, null);
                        else insert.setString(i + 1, value.asText());
                    }
                    added += insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally { connection.setAutoCommit(true); }
        }
        try (Connection connection = connect(catalogPath)) {
            importAddOnlyRows(connection, "product_categories_path", catalog.path("product_categories_path"));
            Map<String, Long> attributeIds = importAttributes(connection, catalog.path("product_attributes"));
            importAttributeValues(connection, catalog.path("product_attribute_values"), attributeIds);
        }
        return added;
    }

    private static ArrayNode readTable(Connection connection, String table) throws SQLException {
        ArrayNode rows = JSON.createArrayNode();
        try (Statement query = connection.createStatement(); ResultSet result = query.executeQuery(
                "SELECT * FROM \"" + table + "\"")) {
            appendRows(result, rows);
        }
        return rows;
    }

    private static void appendRows(ResultSet result, ArrayNode rows) throws SQLException {
        var metadata = result.getMetaData();
        while (result.next()) {
            ObjectNode item = JSON.createObjectNode();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                String name = metadata.getColumnLabel(column);
                Object value = result.getObject(column);
                if (value instanceof byte[]) continue;
                if (value == null) item.putNull(name);
                else item.put(name, String.valueOf(value));
            }
            rows.add(item);
        }
    }

    private static int importAddOnlyRows(Connection connection, String table, JsonNode rows) throws SQLException {
        if (!rows.isArray() || !hasTable(connection, table)) return 0;
        List<String> columns = tableColumns(connection, table);
        int inserted = 0;
        for (JsonNode row : rows) {
            List<String> shared = columns.stream().filter(row::has).toList();
            if (shared.isEmpty()) continue;
            List<String> match = shared.stream().filter(column -> !row.get(column).isNull()).toList();
            if (!match.isEmpty()) {
                String where = String.join(" AND ", match.stream().map(column -> "\"" + column + "\" IS ?").toList());
                try (PreparedStatement find = connection.prepareStatement("SELECT 1 FROM \"" + table + "\" WHERE " + where + " LIMIT 1")) {
                    bindRow(find, row, match, null, null);
                    try (ResultSet found = find.executeQuery()) { if (found.next()) continue; }
                }
            }
            String sql = "INSERT OR IGNORE INTO \"" + table + "\" (" + joinQuoted(shared) + ") VALUES ("
                    + String.join(",", java.util.Collections.nCopies(shared.size(), "?")) + ")";
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                bindRow(insert, row, shared, null, null);
                inserted += insert.executeUpdate();
            }
        }
        return inserted;
    }

    private static Map<String, Long> importAttributes(Connection connection, JsonNode rows) throws SQLException {
        Map<String, Long> idMap = new LinkedHashMap<>();
        if (!rows.isArray() || !hasTable(connection, "product_attributes")) return idMap;
        List<String> columns = tableColumns(connection, "product_attributes");
        if (!columns.contains("id") || !columns.contains("product_id")) return idMap;
        for (JsonNode row : rows) {
            List<String> natural = List.of("product_id", "name", "code", "type", "raw_json").stream()
                    .filter(columns::contains).filter(row::has).filter(column -> !row.get(column).isNull()).toList();
            if (natural.isEmpty()) continue;
            String where = String.join(" AND ", natural.stream().map(column -> "\"" + column + "\" IS ?").toList());
            Long localId = null;
            try (PreparedStatement find = connection.prepareStatement("SELECT id FROM product_attributes WHERE " + where + " LIMIT 1")) {
                bindRow(find, row, natural, null, null);
                try (ResultSet found = find.executeQuery()) { if (found.next()) localId = found.getLong(1); }
            }
            if (localId == null) {
                List<String> insertColumns = columns.stream().filter(column -> !column.equals("id") && row.has(column)).toList();
                if (!insertColumns.isEmpty()) {
                    try (PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO product_attributes ("
                            + joinQuoted(insertColumns) + ") VALUES (" + String.join(",", java.util.Collections.nCopies(insertColumns.size(), "?")) + ")")) {
                        bindRow(insert, row, insertColumns, null, null);
                        insert.executeUpdate();
                    }
                    try (PreparedStatement find = connection.prepareStatement("SELECT id FROM product_attributes WHERE " + where + " LIMIT 1")) {
                        bindRow(find, row, natural, null, null);
                        try (ResultSet found = find.executeQuery()) { if (found.next()) localId = found.getLong(1); }
                    }
                }
            }
            if (localId != null && row.hasNonNull("id")) idMap.put(row.path("id").asText(), localId);
        }
        return idMap;
    }

    private static int importAttributeValues(Connection connection, JsonNode rows, Map<String, Long> idMap) throws SQLException {
        if (!rows.isArray() || !hasTable(connection, "product_attribute_values") || idMap.isEmpty()) return 0;
        List<String> columns = tableColumns(connection, "product_attribute_values");
        if (!columns.contains("attribute_id")) return 0;
        int inserted = 0;
        for (JsonNode row : rows) {
            Long localId = idMap.get(text(row, "attribute_id"));
            if (localId == null) continue;
            List<String> insertColumns = columns.stream().filter(column -> !column.equals("id") && row.has(column)).toList();
            if (!insertColumns.contains("attribute_id")) continue;
            List<String> matchColumns = insertColumns.stream().filter(column -> !column.equals("attribute_id")
                    && !column.equals("raw_json")).toList();
            String where = "attribute_id IS ?" + (matchColumns.isEmpty() ? "" : " AND "
                    + String.join(" AND ", matchColumns.stream().map(column -> "\"" + column + "\" IS ?").toList()));
            try (PreparedStatement find = connection.prepareStatement(
                    "SELECT 1 FROM product_attribute_values WHERE " + where + " LIMIT 1")) {
                find.setLong(1, localId);
                for (int i = 0; i < matchColumns.size(); i++) {
                    JsonNode value = row.get(matchColumns.get(i));
                    if (value == null || value.isNull()) find.setObject(i + 2, null);
                    else find.setString(i + 2, value.asText());
                }
                try (ResultSet found = find.executeQuery()) { if (found.next()) continue; }
            }
            String sql = "INSERT OR IGNORE INTO product_attribute_values (" + joinQuoted(insertColumns) + ") VALUES ("
                    + String.join(",", java.util.Collections.nCopies(insertColumns.size(), "?")) + ")";
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                bindRow(insert, row, insertColumns, "attribute_id", localId);
                inserted += insert.executeUpdate();
            }
        }
        return inserted;
    }

    private static void bindRow(PreparedStatement statement, JsonNode row, List<String> columns,
                                String overrideColumn, Long overrideValue) throws SQLException {
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            if (column.equals(overrideColumn)) statement.setLong(i + 1, overrideValue);
            else {
                JsonNode value = row.get(column);
                if (value == null || value.isNull()) statement.setObject(i + 1, null);
                else statement.setString(i + 1, value.asText());
            }
        }
    }

    private static boolean hasTable(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private ObjectNode exportWorkspace() throws SQLException {
        ObjectNode result = JSON.createObjectNode();
        try (Connection connection = connect(tasksPath)) {
            result.set("points", queryRows(connection, "SELECT sync_key,name,x,y,width,height,color,icon FROM sales_points"));
            result.set("pointItems", queryRows(connection,
                    "SELECT p.sync_key AS point_sync_key,p.name AS point_name,i.article,i.name,i.price,i.discount_price,i.updated_at "
                            + "FROM sales_point_items i JOIN sales_points p ON p.point_id=i.point_id"));
            result.set("fixtures", queryRows(connection,
                    "SELECT sync_key,kind,name,x,y,width,height,rotation,color FROM sales_point_fixtures"));
            result.set("addresses", queryRows(connection, "SELECT address_id,name,created_at FROM storage_addresses"));
            result.set("boxes", queryRows(connection,
                    "SELECT b.box_code,a.address_id,b.created_at FROM storage_boxes b "
                            + "JOIN storage_addresses a ON a.address_id=b.address_id"));
            result.set("storedProducts", queryRows(connection,
                    "SELECT b.box_code,p.product_key,p.product_id,p.name,p.vendor_code,p.barcode,p.quantity,p.updated_at "
                            + "FROM storage_box_products p JOIN storage_boxes b ON b.box_id=p.box_id"));
        }
        return result;
    }

    private int importWorkspace(JsonNode source) throws SQLException {
        if (!source.isObject()) return 0;
        int added = 0;
        try (Connection connection = connect(tasksPath)) {
            connection.setAutoCommit(false);
            try {
                // Snapshot rows are a bootstrap copy, not local edits. Flip the trigger guard
                // inside the same SQLite write transaction so concurrent UI writes wait rather
                // than being accidentally hidden from the local change journal.
                try (Statement guard = connection.createStatement()) {
                    guard.executeUpdate("UPDATE peer_domain_sync_control SET suppress=1 WHERE singleton=1");
                }
                Map<String, Long> pointIds = new LinkedHashMap<>();
                for (JsonNode point : source.path("points")) {
                    String syncKey = text(point, "sync_key");
                    String name = text(point, "name");
                    if (syncKey.isBlank() || name.isBlank()) continue;
                    try (PreparedStatement find = connection.prepareStatement(
                            "SELECT point_id FROM sales_points WHERE sync_key=? LIMIT 1")) {
                        find.setString(1, syncKey);
                        try (ResultSet row = find.executeQuery()) {
                            if (row.next()) { pointIds.put(syncKey, row.getLong(1)); continue; }
                        }
                    }
                    // Upgrade nodes may already have additive name-based snapshot rows. Adopt
                    // their stable identity only when the name is unambiguous; retain all local data.
                    Long legacyPointId = null;
                    try (PreparedStatement find = connection.prepareStatement(
                            "SELECT point_id FROM sales_points WHERE name=? COLLATE NOCASE LIMIT 2")) {
                        find.setString(1, name);
                        try (ResultSet row = find.executeQuery()) {
                            if (row.next()) {
                                long candidate = row.getLong(1);
                                if (!row.next()) legacyPointId = candidate;
                            }
                        }
                    }
                    if (legacyPointId != null) {
                        try (PreparedStatement adopt = connection.prepareStatement("UPDATE sales_points SET sync_key=? WHERE point_id=?")) {
                            adopt.setString(1, syncKey); adopt.setLong(2, legacyPointId); adopt.executeUpdate();
                        }
                        pointIds.put(syncKey, legacyPointId);
                        continue;
                    }
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO sales_points(sync_key,name,x,y,width,height,color,icon) VALUES(?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                        insert.setString(1, syncKey); insert.setString(2, name); insert.setInt(3, point.path("x").asInt());
                        insert.setInt(4, point.path("y").asInt()); insert.setInt(5, point.path("width").asInt(160));
                        insert.setInt(6, point.path("height").asInt(100));
                        insert.setString(7, text(point, "color").isBlank() ? "#2F7E69" : text(point, "color"));
                        insert.setString(8, text(point, "icon").isBlank() ? "store" : text(point, "icon")); insert.executeUpdate();
                        try (ResultSet key = insert.getGeneratedKeys()) { if (key.next()) pointIds.put(syncKey, key.getLong(1)); }
                    }
                    added++;
                }
                for (JsonNode item : source.path("pointItems")) {
                    Long pointId = pointIds.get(text(item, "point_sync_key"));
                    if (pointId == null) continue;
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT OR IGNORE INTO sales_point_items(point_id,article,name,price,discount_price,updated_at) VALUES(?,?,?,?,?,?)")) {
                        insert.setLong(1, pointId); insert.setString(2, text(item, "article"));
                        insert.setString(3, text(item, "name")); insert.setString(4, text(item, "price"));
                        insert.setString(5, text(item, "discount_price")); insert.setLong(6, item.path("updated_at").asLong());
                        added += insert.executeUpdate();
                    }
                }

                for (JsonNode fixture : source.path("fixtures")) {
                    String syncKey = text(fixture, "sync_key");
                    if (syncKey.isBlank()) continue;
                    try (PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO sales_point_fixtures"
                            + "(sync_key,kind,name,x,y,width,height,rotation,color) VALUES(?,?,?,?,?,?,?,?,?)")) {
                        insert.setString(1, syncKey); insert.setString(2, text(fixture, "kind"));
                        insert.setString(3, text(fixture, "name")); insert.setInt(4, fixture.path("x").asInt());
                        insert.setInt(5, fixture.path("y").asInt()); insert.setInt(6, fixture.path("width").asInt(120));
                        insert.setInt(7, fixture.path("height").asInt(48)); insert.setDouble(8, fixture.path("rotation").asDouble());
                        insert.setString(9, text(fixture, "color")); added += insert.executeUpdate();
                    }
                }

                for (JsonNode address : source.path("addresses")) {
                    String id = text(address, "address_id");
                    if (id.isBlank()) continue;
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT OR IGNORE INTO storage_addresses(address_id,name,created_at) VALUES(?,?,?)")) {
                        insert.setString(1, id); insert.setString(2, text(address, "name"));
                        insert.setLong(3, address.path("created_at").asLong()); added += insert.executeUpdate();
                    }
                }
                Map<String, Long> boxIds = new LinkedHashMap<>();
                for (JsonNode box : source.path("boxes")) {
                    String code = text(box, "box_code");
                    if (code.isBlank()) continue;
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT OR IGNORE INTO storage_boxes(address_id,box_code,created_at) VALUES(?,?,?)")) {
                        insert.setString(1, text(box, "address_id")); insert.setString(2, code);
                        insert.setLong(3, box.path("created_at").asLong()); added += insert.executeUpdate();
                    }
                    try (PreparedStatement find = connection.prepareStatement(
                            "SELECT box_id,address_id FROM storage_boxes WHERE box_code=?")) {
                        find.setString(1, code);
                        try (ResultSet row = find.executeQuery()) {
                            if (row.next() && text(box, "address_id").equals(row.getString(2)))
                                boxIds.put(code, row.getLong(1));
                        }
                    }
                }
                for (JsonNode product : source.path("storedProducts")) {
                    Long boxId = boxIds.get(text(product, "box_code"));
                    if (boxId == null) continue;
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT OR IGNORE INTO storage_box_products(box_id,product_key,product_id,name,vendor_code,barcode,quantity,updated_at) "
                                    + "VALUES(?,?,?,?,?,?,?,?)")) {
                        insert.setLong(1, boxId); insert.setString(2, text(product, "product_key"));
                        insert.setString(3, text(product, "product_id")); insert.setString(4, text(product, "name"));
                        insert.setString(5, text(product, "vendor_code")); insert.setString(6, text(product, "barcode"));
                        insert.setInt(7, product.path("quantity").asInt(1)); insert.setLong(8, product.path("updated_at").asLong());
                        added += insert.executeUpdate();
                    }
                }
                try (Statement guard = connection.createStatement()) {
                    guard.executeUpdate("UPDATE peer_domain_sync_control SET suppress=0 WHERE singleton=1");
                }
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally { connection.setAutoCommit(true); }
        }
        return added;
    }

    private static ArrayNode queryRows(Connection connection, String sql) throws SQLException {
        ArrayNode rows = JSON.createArrayNode();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            var metadata = result.getMetaData();
            while (result.next()) {
                ObjectNode item = JSON.createObjectNode();
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    String name = metadata.getColumnLabel(i); Object value = result.getObject(i);
                    if (value == null) item.putNull(name); else item.put(name, String.valueOf(value));
                }
                rows.add(item);
            }
        }
        return rows;
    }

    private static List<String> tableColumns(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
            while (rows.next()) columns.add(rows.getString("name"));
        }
        return columns;
    }

    private static String joinQuoted(List<String> columns) {
        return columns.stream().map(name -> "\"" + name.replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static Connection connect(java.nio.file.Path path) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=15000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    record MergeSummary(int products, int workspaceRows) { }
    record ChangeApplySummary(int applied, int conflicts) { }
}
