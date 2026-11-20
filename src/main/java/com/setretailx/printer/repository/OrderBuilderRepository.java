package com.setretailx.printer.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.model.Price;
import com.setretailx.printer.model.Product;

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
import java.util.UUID;

/** Local persistent storage for named order drafts and their grouped product tree. */
public final class OrderBuilderRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final java.nio.file.Path databasePath;

    public OrderBuilderRepository() { this(AppConfig.tasksDatabasePath()); }

    public OrderBuilderRepository(java.nio.file.Path databasePath) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
    }

    public List<Order> list() throws Exception {
        ensureSchema();
        List<Order> orders = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id, name, updated_at, content_json FROM order_builder_orders ORDER BY updated_at DESC")) {
            while (rows.next()) {
                List<Group> groups = List.of();
                List<Position> positions = List.of();
                String content = rows.getString("content_json");
                if (content != null && !content.isBlank()) {
                    OrderContent decoded = JSON.readValue(content, OrderContent.class);
                    groups = decoded.groups() == null ? List.of() : decoded.groups();
                    positions = decoded.positions() == null ? List.of() : decoded.positions();
                }
                orders.add(new Order(rows.getString("id"), rows.getString("name"), rows.getLong("updated_at"),
                        groups, positions));
            }
        }
        return List.copyOf(orders);
    }

    public Order create(String name) throws Exception {
        Order order = new Order(UUID.randomUUID().toString(), cleanName(name), System.currentTimeMillis(), List.of(), List.of());
        save(order);
        return order;
    }

    public void save(Order order) throws Exception {
        ensureSchema();
        String content = JSON.writeValueAsString(new OrderContent(order.groups(), order.positions()));
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO order_builder_orders(id, name, updated_at, content_json) VALUES(?, ?, ?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET name=excluded.name, updated_at=excluded.updated_at, content_json=excluded.content_json")) {
            statement.setString(1, order.id());
            statement.setString(2, cleanName(order.name()));
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, content);
            statement.executeUpdate();
        }
    }

    public void delete(String id) throws Exception {
        ensureSchema();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM order_builder_orders WHERE id=?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private void ensureSchema() throws Exception {
        if (databasePath.getParent() != null) Files.createDirectories(databasePath.getParent());
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS order_builder_orders ("
                    + "id TEXT PRIMARY KEY, name TEXT NOT NULL, updated_at INTEGER NOT NULL, content_json TEXT NOT NULL)");
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=15000"); }
        return connection;
    }

    private static String cleanName(String value) {
        return value == null || value.isBlank() ? "Новый заказ" : value.trim();
    }

    public record Order(String id, String name, long updatedAt, List<Group> groups, List<Position> positions) {
        public Order {
            groups = groups == null ? List.of() : List.copyOf(groups);
            positions = positions == null ? List.of() : List.copyOf(positions);
        }
        public Order with(String newName, List<Group> newGroups, List<Position> newPositions) {
            return new Order(id, newName, System.currentTimeMillis(), newGroups, newPositions);
        }
    }
    public record Group(String id, String name) {
        public Group {
            id = value(id);
            name = value(name).isBlank() ? "Без названия" : value(name);
        }
    }
    public record Position(String id, String groupId, String title, String comment,
                           ProductData product, List<Candidate> candidates) {
        public Position {
            id = value(id);
            groupId = value(groupId);
            title = value(title);
            comment = value(comment);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
        public boolean need() { return product == null; }
    }
    public record Candidate(String id, ProductData product, String comment) {
        public Candidate {
            id = value(id);
            comment = value(comment);
        }
    }
    public record ProductData(String stCode, String name, String vendorCode, String barcode, String uom,
                              String source, Long priceKopecks, String totalQuantity,
                              Map<String, String> details, String rawJson) {
        public ProductData {
            stCode = value(stCode);
            name = value(name).isBlank() ? "Без названия" : value(name);
            vendorCode = value(vendorCode);
            barcode = value(barcode);
            uom = value(uom).isBlank() ? "ST" : value(uom);
            source = value(source);
            totalQuantity = value(totalQuantity);
            rawJson = value(rawJson);
            if (details == null || details.isEmpty()) details = Map.of();
            else {
                Map<String, String> normalized = new LinkedHashMap<>();
                details.forEach((key, value) -> { if (key != null && value != null) normalized.put(key, value); });
                details = Map.copyOf(normalized);
            }
        }
        public static ProductData from(Product product) {
            return new ProductData(product.stCode(), product.name(), product.vendorCode(), product.barcode(),
                    product.uom(), product.source(), product.price() == null ? null : product.price().kopecks(),
                    product.totalQuantity(), product.details(), product.rawJson());
        }
        public Product toProduct() {
            return new Product(stCode, name, vendorCode, barcode, uom, source,
                    priceKopecks == null ? null : new Price(priceKopecks), totalQuantity, details, rawJson);
        }
    }
    private record OrderContent(List<Group> groups, List<Position> positions) { }

    private static String value(String text) { return text == null ? "" : text; }
}
