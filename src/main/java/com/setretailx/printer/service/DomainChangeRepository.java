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

/** Durable parent change feed for shared address/box/inventory domain data. */
final class DomainChangeRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BATCH = 500;
    private final java.nio.file.Path path = AppConfig.tasksDatabasePath().toAbsolutePath().normalize();

    DomainChangeRepository() {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            migrate();
        } catch (SQLException | java.io.IOException error) {
            throw new IllegalStateException("Не удалось подготовить журнал доменных изменений.", error);
        }
    }

    private void migrate() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS peer_domain_changes ("
                    + "change_id INTEGER PRIMARY KEY AUTOINCREMENT, entity_key TEXT NOT NULL, "
                    + "operation TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_peer_domain_changes_entity "
                    + "ON peer_domain_changes(entity_key, change_id)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS peer_domain_sync_state ("
                    + "singleton INTEGER PRIMARY KEY CHECK(singleton=1), parent_id TEXT NOT NULL, "
                    + "parent_cursor INTEGER NOT NULL, local_baseline INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS peer_domain_sync_conflicts ("
                    + "parent_id TEXT NOT NULL, change_id INTEGER NOT NULL, entity_key TEXT NOT NULL, "
                    + "created_at INTEGER NOT NULL, PRIMARY KEY(parent_id, change_id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS peer_domain_sync_control ("
                    + "singleton INTEGER PRIMARY KEY CHECK(singleton=1), suppress INTEGER NOT NULL)");
            statement.executeUpdate("INSERT OR IGNORE INTO peer_domain_sync_control(singleton,suppress) VALUES(1,0)");

            createTriggers(statement, "storage_addresses", "address_id", "NEW.address_id", "OLD.address_id",
                    "json_object('address_id',NEW.address_id,'name',NEW.name,'created_at',NEW.created_at)",
                    "json_object('address_id',OLD.address_id,'name',OLD.name,'created_at',OLD.created_at)");
            createTriggers(statement, "storage_boxes", "box_code", "NEW.box_code", "OLD.box_code",
                    "json_object('box_code',NEW.box_code,'address_id',NEW.address_id,'created_at',NEW.created_at)",
                    "json_object('box_code',OLD.box_code,'address_id',OLD.address_id,'created_at',OLD.created_at)");
            createProductTriggers(statement);
            createSalesTriggers(statement);
            createFixtureTriggers(statement);
        }
    }

    private static void createTriggers(Statement statement, String table, String keyColumn,
                                       String newKey, String oldKey, String newPayload, String oldPayload)
            throws SQLException {
        String safe = table;
        statement.executeUpdate("DROP TRIGGER IF EXISTS peer_sync_" + safe + "_insert");
        statement.executeUpdate("DROP TRIGGER IF EXISTS peer_sync_" + safe + "_update");
        statement.executeUpdate("DROP TRIGGER IF EXISTS peer_sync_" + safe + "_delete");
        statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS peer_sync_" + safe + "_insert AFTER INSERT ON " + table
                + " WHEN (SELECT suppress FROM peer_domain_sync_control WHERE singleton=1)=0 BEGIN "
                + "INSERT INTO peer_domain_changes(entity_key,operation,payload,created_at) VALUES('" + table + ":'||"
                + newKey + ",'upsert'," + newPayload + ",CAST(strftime('%s','now') AS INTEGER)*1000); END");
        statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS peer_sync_" + safe + "_update AFTER UPDATE ON " + table
                + " WHEN (SELECT suppress FROM peer_domain_sync_control WHERE singleton=1)=0 BEGIN "
                + "INSERT INTO peer_domain_changes(entity_key,operation,payload,created_at) SELECT '" + table + ":'||"
                + oldKey + ",'delete'," + oldPayload + ",CAST(strftime('%s','now') AS INTEGER)*1000 WHERE (" + oldKey + ") IS NOT (" + newKey + "); "
                + "INSERT INTO peer_domain_changes(entity_key,operation,payload,created_at) VALUES('" + table + ":'||"
                + newKey + ",'upsert'," + newPayload + ",CAST(strftime('%s','now') AS INTEGER)*1000); END");
        statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS peer_sync_" + safe + "_delete AFTER DELETE ON " + table
                + " WHEN (SELECT suppress FROM peer_domain_sync_control WHERE singleton=1)=0 BEGIN "
                + "INSERT INTO peer_domain_changes(entity_key,operation,payload,created_at) VALUES('" + table + ":'||"
                + oldKey + ",'delete'," + oldPayload + ",CAST(strftime('%s','now') AS INTEGER)*1000); END");
    }

    private static void createProductTriggers(Statement statement) throws SQLException {
        statement.executeUpdate("DROP TRIGGER IF EXISTS peer_sync_storage_products_insert");
        statement.executeUpdate("DROP TRIGGER IF EXISTS peer_sync_storage_products_update");
        statement.executeUpdate("DROP TRIGGER IF EXISTS peer_sync_storage_products_delete");
        String box = "(SELECT box_code FROM storage_boxes WHERE box_id=%s)";
        String newBox = box.formatted("NEW.box_id");
        String oldBox = box.formatted("OLD.box_id");
        String newKey = "'storage_box_products:'||" + newBox + "||char(31)||NEW.product_key";
        String oldKey = "'storage_box_products:'||" + oldBox + "||char(31)||OLD.product_key";
        String newPayload = "json_object('box_code'," + newBox + ",'product_key',NEW.product_key,'product_id',NEW.product_id,"
                + "'name',NEW.name,'vendor_code',NEW.vendor_code,'barcode',NEW.barcode,'quantity',NEW.quantity,'updated_at',NEW.updated_at)";
        String oldPayload = "json_object('box_code'," + oldBox + ",'product_key',OLD.product_key,'product_id',OLD.product_id,"
                + "'name',OLD.name,'vendor_code',OLD.vendor_code,'barcode',OLD.barcode,'quantity',OLD.quantity,'updated_at',OLD.updated_at)";
        statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS peer_sync_storage_products_insert AFTER INSERT ON storage_box_products "
                + "WHEN (SELECT suppress FROM peer_domain_sync_control WHERE singleton=1)=0 AND " + newBox + " IS NOT NULL BEGIN "
                + "INSERT INTO peer_domain_changes(entity_key,operation,payload,created_at) VALUES(" + newKey + ",'upsert'," + newPayload
                + ",CAST(strftime('%s','now') AS INTEGER)*1000); END");
        statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS peer_sync_storage_products_update AFTER UPDATE ON storage_box_products "
                + "WHEN (SELECT suppress FROM peer_domain_sync_control WHERE singleton=1)=0 AND " + oldBox + " IS NOT NULL AND " + newBox + " IS NOT NULL BEGIN "
                + "INSERT INTO peer_domain_changes(entity_key,operation,payload,created_at) SELECT " + oldKey + ",'delete'," + oldPayload
                + ",CAST(strftime('%s','now') AS INTEGER)*1000 WHERE (" + oldKey + ") IS NOT (" + newKey + "); "
                + "INSERT INTO peer_domain_changes(entity_key,operation,payload,created_at) VALUES(" + newKey
                + ",'upsert'," + newPayload + ",CAST(strftime('%s','now') AS INTEGER)*1000); END");
        statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS peer_sync_storage_products_delete AFTER DELETE ON storage_box_products "
                + "WHEN (SELECT suppress FROM peer_domain_sync_control WHERE singleton=1)=0 AND " + oldBox + " IS NOT NULL BEGIN "
                + "INSERT INTO peer_domain_changes(entity_key,operation,payload,created_at) VALUES(" + oldKey + ",'delete'," + oldPayload
                + ",CAST(strftime('%s','now') AS INTEGER)*1000); END");
    }

    private static void createSalesTriggers(Statement statement) throws SQLException {
        createTriggers(statement, "sales_points", "sync_key", "NEW.sync_key", "OLD.sync_key",
                "json_object('sync_key',NEW.sync_key,'name',NEW.name,'x',NEW.x,'y',NEW.y,'width',NEW.width,'height',NEW.height,'color',NEW.color,'icon',NEW.icon)",
                "json_object('sync_key',OLD.sync_key,'name',OLD.name,'x',OLD.x,'y',OLD.y,'width',OLD.width,'height',OLD.height,'color',OLD.color,'icon',OLD.icon)");
        String pointNew = "(SELECT sync_key FROM sales_points WHERE point_id=NEW.point_id)";
        String pointOld = "(SELECT sync_key FROM sales_points WHERE point_id=OLD.point_id)";
        String pointNameNew = "(SELECT name FROM sales_points WHERE point_id=NEW.point_id)";
        String pointNameOld = "(SELECT name FROM sales_points WHERE point_id=OLD.point_id)";
        String keyNew = "'sales_point_items:'||" + pointNew + "||char(31)||lower(NEW.article)";
        String keyOld = "'sales_point_items:'||" + pointOld + "||char(31)||lower(OLD.article)";
        String payloadNew = "json_object('point_name'," + pointNameNew + ",'article',NEW.article,'name',NEW.name,"
                + "'point_sync_key'," + pointNew + ",'price',NEW.price,'discount_price',NEW.discount_price,'updated_at',NEW.updated_at)";
        String payloadOld = "json_object('point_name'," + pointNameOld + ",'article',OLD.article,'name',OLD.name,"
                + "'point_sync_key'," + pointOld + ",'price',OLD.price,'discount_price',OLD.discount_price,'updated_at',OLD.updated_at)";
        statement.executeUpdate("DROP TRIGGER IF EXISTS peer_sync_sales_items_insert");
        statement.executeUpdate("DROP TRIGGER IF EXISTS peer_sync_sales_items_update");
        statement.executeUpdate("DROP TRIGGER IF EXISTS peer_sync_sales_items_delete");
        statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS peer_sync_sales_items_insert AFTER INSERT ON sales_point_items "
                + "WHEN (SELECT suppress FROM peer_domain_sync_control WHERE singleton=1)=0 AND " + pointNameNew + " IS NOT NULL BEGIN "
                + "INSERT INTO peer_domain_changes(entity_key,operation,payload,created_at) VALUES(" + keyNew + ",'upsert'," + payloadNew
                + ",CAST(strftime('%s','now') AS INTEGER)*1000); END");
        statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS peer_sync_sales_items_update AFTER UPDATE ON sales_point_items "
                + "WHEN (SELECT suppress FROM peer_domain_sync_control WHERE singleton=1)=0 AND " + pointNameOld + " IS NOT NULL AND " + pointNameNew + " IS NOT NULL BEGIN "
                + "INSERT INTO peer_domain_changes(entity_key,operation,payload,created_at) SELECT " + keyOld + ",'delete'," + payloadOld
                + ",CAST(strftime('%s','now') AS INTEGER)*1000 WHERE (" + keyOld + ") IS NOT (" + keyNew + "); "
                + "INSERT INTO peer_domain_changes(entity_key,operation,payload,created_at) VALUES(" + keyNew
                + ",'upsert'," + payloadNew + ",CAST(strftime('%s','now') AS INTEGER)*1000); END");
        statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS peer_sync_sales_items_delete AFTER DELETE ON sales_point_items "
                + "WHEN (SELECT suppress FROM peer_domain_sync_control WHERE singleton=1)=0 AND " + pointNameOld + " IS NOT NULL BEGIN "
                + "INSERT INTO peer_domain_changes(entity_key,operation,payload,created_at) VALUES(" + keyOld + ",'delete'," + payloadOld
                + ",CAST(strftime('%s','now') AS INTEGER)*1000); END");
    }

    private static void createFixtureTriggers(Statement statement) throws SQLException {
        statement.executeUpdate("DROP TRIGGER IF EXISTS peer_sync_sales_fixtures_insert");
        statement.executeUpdate("DROP TRIGGER IF EXISTS peer_sync_sales_fixtures_update");
        statement.executeUpdate("DROP TRIGGER IF EXISTS peer_sync_sales_fixtures_delete");
        String payloadNew = "json_object('sync_key',NEW.sync_key,'kind',NEW.kind,'name',NEW.name,'x',NEW.x,'y',NEW.y,"
                + "'width',NEW.width,'height',NEW.height,'rotation',NEW.rotation,'color',NEW.color)";
        String payloadOld = "json_object('sync_key',OLD.sync_key,'kind',OLD.kind,'name',OLD.name,'x',OLD.x,'y',OLD.y,"
                + "'width',OLD.width,'height',OLD.height,'rotation',OLD.rotation,'color',OLD.color)";
        statement.executeUpdate("CREATE TRIGGER peer_sync_sales_fixtures_insert AFTER INSERT ON sales_point_fixtures "
                + "WHEN (SELECT suppress FROM peer_domain_sync_control WHERE singleton=1)=0 BEGIN "
                + "INSERT INTO peer_domain_changes(entity_key,operation,payload,created_at) VALUES('sales_point_fixtures:'||NEW.sync_key,'upsert',"
                + payloadNew + ",CAST(strftime('%s','now') AS INTEGER)*1000); END");
        statement.executeUpdate("CREATE TRIGGER peer_sync_sales_fixtures_update AFTER UPDATE ON sales_point_fixtures "
                + "WHEN (SELECT suppress FROM peer_domain_sync_control WHERE singleton=1)=0 BEGIN "
                + "INSERT INTO peer_domain_changes(entity_key,operation,payload,created_at) VALUES('sales_point_fixtures:'||NEW.sync_key,'upsert',"
                + payloadNew + ",CAST(strftime('%s','now') AS INTEGER)*1000); END");
        statement.executeUpdate("CREATE TRIGGER peer_sync_sales_fixtures_delete AFTER DELETE ON sales_point_fixtures "
                + "WHEN (SELECT suppress FROM peer_domain_sync_control WHERE singleton=1)=0 BEGIN "
                + "INSERT INTO peer_domain_changes(entity_key,operation,payload,created_at) VALUES('sales_point_fixtures:'||OLD.sync_key,'delete',"
                + payloadOld + ",CAST(strftime('%s','now') AS INTEGER)*1000); END");
    }

    long cursor(String parentId) throws SQLException {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT parent_cursor FROM peer_domain_sync_state WHERE singleton=1 AND parent_id=?")) {
            query.setString(1, parentId);
            try (ResultSet row = query.executeQuery()) { return row.next() ? row.getLong(1) : -1; }
        }
    }

    long highWater() throws SQLException {
        try (Connection connection = connect(); Statement query = connection.createStatement();
             ResultSet row = query.executeQuery("SELECT COALESCE(MAX(change_id),0) FROM peer_domain_changes")) {
            return row.next() ? row.getLong(1) : 0;
        }
    }

    ObjectNode exportAfter(long cursor) throws SQLException {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode events = result.putArray("events");
        long highWater;
        try (Connection connection = connect(); Statement high = connection.createStatement();
             ResultSet row = high.executeQuery("SELECT COALESCE(MAX(change_id),0) FROM peer_domain_changes")) {
            highWater = row.next() ? row.getLong(1) : 0;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT change_id,entity_key,operation,payload FROM peer_domain_changes WHERE change_id>? AND change_id<=? "
                            + "ORDER BY change_id LIMIT ?")) {
                query.setLong(1, cursor); query.setLong(2, highWater); query.setInt(3, MAX_BATCH);
                try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    ObjectNode event = events.addObject();
                    event.put("changeId", rows.getLong(1)); event.put("entityKey", rows.getString(2));
                    event.put("operation", rows.getString(3)); event.set("payload", JSON.readTree(rows.getString(4)));
                }
                } catch (java.io.IOException error) { throw new SQLException("Не удалось прочитать журнал доменных изменений.", error); }
            }
        }
        long last = events.isEmpty() ? cursor : events.get(events.size() - 1).path("changeId").asLong(cursor);
        result.put("nextCursor", last);
        result.put("highWater", highWater);
        result.put("more", last < highWater);
        return result;
    }

    int apply(String parentId, JsonNode events, long highWater) throws SQLException {
        if (!events.isArray() || events.size() > MAX_BATCH) throw new SQLException("Некорректный пакет доменных изменений.");
        int conflicts = 0;
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                long baseline = syncBaseline(connection, parentId);
                try (Statement control = connection.createStatement()) { control.executeUpdate("UPDATE peer_domain_sync_control SET suppress=1 WHERE singleton=1"); }
                try (PreparedStatement changedLocally = connection.prepareStatement(
                        "SELECT 1 FROM peer_domain_changes WHERE entity_key=? AND change_id>? LIMIT 1");
                     PreparedStatement alreadyConflicted = connection.prepareStatement(
                             "SELECT 1 FROM peer_domain_sync_conflicts WHERE parent_id=? AND entity_key=? LIMIT 1");
                     PreparedStatement conflict = connection.prepareStatement(
                             "INSERT OR IGNORE INTO peer_domain_sync_conflicts(parent_id,change_id,entity_key,created_at) VALUES(?,?,?,?)")) {
                    for (JsonNode event : events) {
                        long id = event.path("changeId").asLong(-1);
                        String key = event.path("entityKey").asText("");
                        if (id < 0 || key.isBlank()) throw new SQLException("Пакет доменных изменений содержит некорректную запись.");
                        changedLocally.setString(1, key); changedLocally.setLong(2, baseline);
                        boolean isConflict;
                        try (ResultSet row = changedLocally.executeQuery()) { isConflict = row.next(); }
                        alreadyConflicted.setString(1, parentId); alreadyConflicted.setString(2, key);
                        try (ResultSet row = alreadyConflicted.executeQuery()) { isConflict |= row.next(); }
                        if (isConflict) {
                            conflict.setString(1, parentId); conflict.setLong(2, id); conflict.setString(3, key);
                            conflict.setLong(4, System.currentTimeMillis()); conflict.executeUpdate(); conflicts++;
                        } else {
                            applyEvent(connection, key, event.path("operation").asText(), event.path("payload"));
                        }
                    }
                }
                try (Statement control = connection.createStatement()) { control.executeUpdate("UPDATE peer_domain_sync_control SET suppress=0 WHERE singleton=1"); }
                try (PreparedStatement save = connection.prepareStatement("INSERT INTO peer_domain_sync_state(singleton,parent_id,parent_cursor,local_baseline) "
                        + "VALUES(1,?,?,?) ON CONFLICT(singleton) DO UPDATE SET parent_id=excluded.parent_id,parent_cursor=excluded.parent_cursor,local_baseline=excluded.local_baseline")) {
                    save.setString(1, parentId); save.setLong(2, highWater);
                    save.setLong(3, localChangeHighWater(connection)); save.executeUpdate();
                }
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                if (error instanceof SQLException sql) throw sql;
                throw new SQLException("Не удалось применить доменные изменения.", error);
            } finally {
                try (Statement control = connection.createStatement()) { control.executeUpdate("UPDATE peer_domain_sync_control SET suppress=0 WHERE singleton=1"); }
                connection.setAutoCommit(true);
            }
        }
        return conflicts;
    }

    void markBootstrap(String parentId, long cursor) throws SQLException {
        try (Connection connection = connect(); PreparedStatement save = connection.prepareStatement(
                "INSERT INTO peer_domain_sync_state(singleton,parent_id,parent_cursor,local_baseline) VALUES(1,?,?,?) "
                        + "ON CONFLICT(singleton) DO UPDATE SET parent_id=excluded.parent_id,parent_cursor=excluded.parent_cursor,local_baseline=excluded.local_baseline")) {
            // Include edits made before first pairing: those are precisely the local-only work
            // that must not be silently replaced by the first parent change batch.
            save.setString(1, parentId); save.setLong(2, cursor); save.setLong(3, 0); save.executeUpdate();
        }
    }

    private static long syncBaseline(Connection connection, String parentId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT local_baseline FROM peer_domain_sync_state WHERE singleton=1 AND parent_id=?")) {
            query.setString(1, parentId);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? row.getLong(1) : localChangeHighWater(connection);
            }
        }
    }

    private static long localChangeHighWater(Connection connection) throws SQLException {
        try (Statement query = connection.createStatement(); ResultSet row = query.executeQuery(
                "SELECT COALESCE(MAX(change_id),0) FROM peer_domain_changes")) { return row.next() ? row.getLong(1) : 0; }
    }

    private static void applyEvent(Connection connection, String key, String operation, JsonNode payload) throws SQLException {
        String entity = key.substring(0, key.indexOf(':'));
        boolean delete = "delete".equals(operation);
        switch (entity) {
            case "storage_addresses" -> {
                String id = payload.path("address_id").asText();
                if (delete) {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM storage_addresses WHERE address_id=?")) {
                        statement.setString(1, id); statement.executeUpdate();
                    }
                } else try (PreparedStatement statement = connection.prepareStatement("INSERT INTO storage_addresses(address_id,name,created_at) VALUES(?,?,?) "
                        + "ON CONFLICT(address_id) DO UPDATE SET name=excluded.name,created_at=excluded.created_at")) {
                    statement.setString(1, id); statement.setString(2, payload.path("name").asText());
                    statement.setLong(3, payload.path("created_at").asLong()); statement.executeUpdate();
                }
            }
            case "storage_boxes" -> {
                String code = payload.path("box_code").asText();
                if (delete) {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM storage_boxes WHERE box_code=?")) {
                        statement.setString(1, code); statement.executeUpdate();
                    }
                } else try (PreparedStatement statement = connection.prepareStatement("INSERT INTO storage_boxes(address_id,box_code,created_at) VALUES(?,?,?) "
                        + "ON CONFLICT(box_code) DO UPDATE SET address_id=excluded.address_id,created_at=excluded.created_at")) {
                    statement.setString(1, payload.path("address_id").asText()); statement.setString(2, code);
                    statement.setLong(3, payload.path("created_at").asLong()); statement.executeUpdate();
                }
            }
            case "storage_box_products" -> {
                String code = payload.path("box_code").asText(); String productKey = payload.path("product_key").asText();
                try (PreparedStatement find = connection.prepareStatement("SELECT box_id FROM storage_boxes WHERE box_code=?")) {
                    find.setString(1, code);
                    try (ResultSet box = find.executeQuery()) {
                        if (!box.next()) return;
                        long boxId = box.getLong(1);
                        if (delete) {
                            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM storage_box_products WHERE box_id=? AND product_key=?")) {
                                statement.setLong(1, boxId); statement.setString(2, productKey); statement.executeUpdate();
                            }
                        } else try (PreparedStatement statement = connection.prepareStatement("INSERT INTO storage_box_products(box_id,product_key,product_id,name,vendor_code,barcode,quantity,updated_at) "
                                + "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(box_id,product_key) DO UPDATE SET product_id=excluded.product_id,name=excluded.name,"
                                + "vendor_code=excluded.vendor_code,barcode=excluded.barcode,quantity=excluded.quantity,updated_at=excluded.updated_at")) {
                            statement.setLong(1, boxId); statement.setString(2, productKey); statement.setString(3, payload.path("product_id").asText());
                            statement.setString(4, payload.path("name").asText()); statement.setString(5, payload.path("vendor_code").asText());
                            statement.setString(6, payload.path("barcode").asText()); statement.setInt(7, payload.path("quantity").asInt());
                            statement.setLong(8, payload.path("updated_at").asLong()); statement.executeUpdate();
                        }
                    }
                }
            }
            case "sales_points" -> {
                String syncKey = payload.path("sync_key").asText();
                String name = payload.path("name").asText();
                if (delete) {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sales_points WHERE sync_key=?")) {
                        statement.setString(1, syncKey); statement.executeUpdate();
                    }
                } else {
                    try (PreparedStatement find = connection.prepareStatement("SELECT point_id FROM sales_points WHERE sync_key=? LIMIT 1")) {
                        find.setString(1, syncKey);
                        try (ResultSet row = find.executeQuery()) {
                            if (row.next()) {
                                try (PreparedStatement update = connection.prepareStatement("UPDATE sales_points SET name=?,x=?,y=?,width=?,height=?,color=?,icon=? WHERE point_id=?")) {
                                    update.setString(1, name); update.setInt(2, payload.path("x").asInt()); update.setInt(3, payload.path("y").asInt());
                                    update.setInt(4, payload.path("width").asInt(160)); update.setInt(5, payload.path("height").asInt(100));
                                    update.setString(6, payload.path("color").asText("#2F7E69"));
                                    update.setString(7, payload.path("icon").asText("store"));
                                    update.setLong(8, row.getLong(1)); update.executeUpdate();
                                }
                                return;
                            }
                        }
                    }
                    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO sales_points(sync_key,name,x,y,width,height,color,icon) VALUES(?,?,?,?,?,?,?,?)")) {
                        insert.setString(1, syncKey); insert.setString(2, name); insert.setInt(3, payload.path("x").asInt()); insert.setInt(4, payload.path("y").asInt());
                        insert.setInt(5, payload.path("width").asInt(160)); insert.setInt(6, payload.path("height").asInt(100));
                        insert.setString(7, payload.path("color").asText("#2F7E69")); insert.setString(8, payload.path("icon").asText("store")); insert.executeUpdate();
                    }
                }
            }
            case "sales_point_items" -> {
                String pointName = payload.path("point_name").asText(); String article = payload.path("article").asText();
                Long pointId = null;
                try (PreparedStatement find = connection.prepareStatement("SELECT point_id FROM sales_points WHERE sync_key=? LIMIT 1")) {
                    find.setString(1, payload.path("point_sync_key").asText()); try (ResultSet row = find.executeQuery()) { if (row.next()) pointId = row.getLong(1); }
                }
                if (pointId == null) return;
                if (delete) {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sales_point_items WHERE point_id=? AND article=? COLLATE NOCASE")) {
                        statement.setLong(1, pointId); statement.setString(2, article); statement.executeUpdate();
                    }
                } else try (PreparedStatement statement = connection.prepareStatement("INSERT INTO sales_point_items(point_id,article,name,price,discount_price,updated_at) VALUES(?,?,?,?,?,?) "
                        + "ON CONFLICT(point_id,article) DO UPDATE SET name=excluded.name,price=excluded.price,discount_price=excluded.discount_price,updated_at=excluded.updated_at")) {
                    statement.setLong(1, pointId); statement.setString(2, article); statement.setString(3, payload.path("name").asText());
                    statement.setString(4, payload.path("price").asText()); statement.setString(5, payload.path("discount_price").asText());
                    statement.setLong(6, payload.path("updated_at").asLong()); statement.executeUpdate();
                }
            }
            case "sales_point_fixtures" -> {
                String syncKey = payload.path("sync_key").asText();
                if (syncKey.isBlank()) throw new SQLException("Изменение объекта карты не содержит стабильный ID.");
                if (delete) {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sales_point_fixtures WHERE sync_key=?")) {
                        statement.setString(1, syncKey); statement.executeUpdate();
                    }
                } else try (PreparedStatement statement = connection.prepareStatement("INSERT INTO sales_point_fixtures"
                        + "(sync_key,kind,name,x,y,width,height,rotation,color) VALUES(?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(sync_key) DO UPDATE SET kind=excluded.kind,name=excluded.name,x=excluded.x,y=excluded.y,"
                        + "width=excluded.width,height=excluded.height,rotation=excluded.rotation,color=excluded.color")) {
                    statement.setString(1, syncKey); statement.setString(2, payload.path("kind").asText("shelf"));
                    statement.setString(3, payload.path("name").asText()); statement.setInt(4, payload.path("x").asInt());
                    statement.setInt(5, payload.path("y").asInt()); statement.setInt(6, payload.path("width").asInt(120));
                    statement.setInt(7, payload.path("height").asInt(48)); statement.setDouble(8, payload.path("rotation").asDouble());
                    statement.setString(9, payload.path("color").asText("#3B82F6")); statement.executeUpdate();
                }
            }
            default -> throw new SQLException("Неизвестный тип доменного изменения.");
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=15000"); statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }
}
