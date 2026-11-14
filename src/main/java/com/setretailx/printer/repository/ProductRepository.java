package com.setretailx.printer.repository;

import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.model.Price;
import com.setretailx.printer.model.Product;

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
import java.util.Set;

/** Exact barcode lookup and local catalog search against all_products.sqlite. */
public final class ProductRepository {
    public List<Product> search(String query) throws SQLException {
        return search(new SearchCriteria(query, "", "", "", null, null));
    }

    public List<Product> search(SearchCriteria criteria) throws SQLException {
        SearchCriteria filters = criteria == null ? SearchCriteria.empty() : criteria;
        String text = filters.query().trim();
        String pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        try (Connection connection = connect()) {
            Set<String> columns = columns(connection);
            List<String> searchable = new ArrayList<>();
            for (String column : List.of("name", "vendor_code", "barcode")) {
                if (columns.contains(column)) searchable.add(column);
            }
            if (!columns.contains("product_id") || searchable.isEmpty()) {
                throw new SQLException("В таблице products отсутствуют поля для поиска товаров.");
            }
            List<String> conditions = new ArrayList<>();
            List<Object> parameters = new ArrayList<>();
            if (!text.isEmpty()) {
                conditions.add("(" + String.join(" OR ", searchable.stream()
                        .map(column -> "CAST(\"" + column + "\" AS TEXT) LIKE ? ESCAPE '\\' COLLATE NOCASE")
                        .toList()) + ")");
                for (int i = 0; i < searchable.size(); i++) parameters.add(pattern);
            }
            if (!filters.category().isBlank() && hasTable(connection, "product_categories_path")) {
                conditions.add("(SELECT group_concat(name, ' › ') FROM (SELECT name FROM product_categories_path pcp "
                        + "WHERE pcp.product_id = products.product_id ORDER BY position)) = ?");
                parameters.add(filters.category());
            }
            if (filters.minimumPrice() != null && columns.contains("price")) {
                conditions.add("CAST(price AS INTEGER) >= ?");
                parameters.add(filters.minimumPrice());
            }
            if (filters.maximumPrice() != null && columns.contains("price")) {
                conditions.add("CAST(price AS INTEGER) <= ?");
                parameters.add(filters.maximumPrice());
            }
            if (!filters.attributeName().isBlank()
                    && hasTable(connection, "product_attributes")
                    && hasTable(connection, "product_attribute_values")) {
                conditions.add("EXISTS (SELECT 1 FROM product_attributes a "
                        + "LEFT JOIN product_attribute_values v ON v.attribute_id = a.id "
                        + "WHERE a.product_id = products.product_id "
                        + "AND COALESCE(a.name, a.code, '') = ?"
                        + (filters.attributeValue().isBlank() ? ")" : " AND COALESCE(v.name, v.value, '') = ?)"));
                parameters.add(filters.attributeName());
                if (!filters.attributeValue().isBlank()) parameters.add(filters.attributeValue());
            }
            String orderColumn = columns.contains("name") ? "name" : "product_id";
            String sql = selectColumns(connection, columns) + " FROM products"
                    + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions))
                    + " ORDER BY \"" + orderColumn + "\" LIMIT 500";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
                try (ResultSet rows = statement.executeQuery()) {
                    List<Product> products = new ArrayList<>();
                    while (rows.next()) products.add(product(rows));
                    return products;
                }
            }
        }
    }

    public SearchOptions searchOptions() throws SQLException {
        try (Connection connection = connect()) {
            List<String> categories = new ArrayList<>();
            if (hasTable(connection, "product_categories_path")) {
                try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                        "SELECT DISTINCT category_path FROM (SELECT product_id, "
                                + "group_concat(name, ' › ') AS category_path FROM "
                                + "(SELECT product_id, name, position FROM product_categories_path "
                                + "ORDER BY position) GROUP BY product_id) "
                                + "WHERE category_path IS NOT NULL AND category_path <> '' ORDER BY category_path")) {
                    while (rows.next()) categories.add(rows.getString(1));
                }
            }

            Map<String, List<String>> attributes = new LinkedHashMap<>();
            if (hasTable(connection, "product_attributes") && hasTable(connection, "product_attribute_values")) {
                try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                        "SELECT DISTINCT COALESCE(a.name, a.code, '') AS attribute_name, "
                                + "COALESCE(v.name, v.value, '') AS attribute_value "
                                + "FROM product_attributes a JOIN product_attribute_values v "
                                + "ON v.attribute_id = a.id WHERE attribute_name <> '' "
                                + "AND attribute_value <> '' ORDER BY attribute_name, attribute_value")) {
                    while (rows.next()) attributes.computeIfAbsent(rows.getString("attribute_name"), ignored -> new ArrayList<>())
                            .add(rows.getString("attribute_value"));
                }
            }

            long minimum = 0;
            long maximum = 100_000_000L;
            Set<String> columns = columns(connection);
            if (columns.contains("price")) {
                try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                        "SELECT MIN(CAST(price AS INTEGER)), MAX(CAST(price AS INTEGER)) FROM products "
                                + "WHERE price IS NOT NULL AND CAST(price AS INTEGER) >= 0")) {
                    if (rows.next() && rows.getObject(1) != null) {
                        minimum = Math.max(0, rows.getLong(1));
                        maximum = Math.max(minimum, rows.getLong(2));
                    }
                }
            }
            return new SearchOptions(categories, attributes, minimum, maximum);
        }
    }

    public Product findByBarcode(String barcode) throws SQLException {
        try (Connection connection = connect()) {
            Set<String> columns = columns(connection);
            if (!columns.contains("product_id") || !columns.contains("barcode")) {
                throw new SQLException("В таблице products отсутствуют поля barcode или product_id.");
            }
            String sql = selectColumns(connection, columns) + " FROM products WHERE CAST(barcode AS TEXT) = ? LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, barcode);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? product(rows) : null;
                }
            }
        }
    }

    private Set<String> columns(Connection connection) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("PRAGMA table_info(products)")) {
            while (rows.next()) columns.add(rows.getString("name").toLowerCase(java.util.Locale.ROOT));
        }
        return columns;
    }

    private String selectColumns(Connection connection, Set<String> columns) throws SQLException {
        StringBuilder select = new StringBuilder("SELECT ");
        List<String> standard = List.of("product_id", "name", "vendor_code", "barcode", "uom", "price", "total_qty");
        Set<String> selected = new LinkedHashSet<>();
        for (String column : standard) {
            if (select.length() > 7) select.append(", ");
            select.append(columns.contains(column) ? quote(column) : "NULL")
                    .append(" AS \"").append(column).append('"');
            selected.add(column);
        }
        for (String column : columns) {
            if (selected.add(column)) select.append(", ").append(quote(column));
        }
        if (hasTable(connection, "product_categories_path")) {
            select.append(", (SELECT group_concat(name, ' › ') FROM (SELECT name FROM product_categories_path pcp "
                    + "WHERE pcp.product_id = products.product_id ORDER BY position)) AS \"category_path\"");
        }
        if (hasTable(connection, "product_attributes") && hasTable(connection, "product_attribute_values")) {
            select.append(", (SELECT group_concat(COALESCE(a.name, a.code, '') || char(31) || "
                    + "COALESCE(v.name, v.value, ''), char(30)) FROM product_attributes a "
                    + "LEFT JOIN product_attribute_values v ON v.attribute_id = a.id "
                    + "WHERE a.product_id = products.product_id) AS \"catalog_attributes\"");
        }
        return select.toString();
    }

    private boolean hasTable(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private Connection connect() throws SQLException {
        if (!java.nio.file.Files.isRegularFile(AppConfig.DB_PATH)) {
            throw new SQLException("Локальная база не найдена: " + AppConfig.DB_PATH);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + AppConfig.DB_PATH);
    }

    private Product product(ResultSet row) throws SQLException {
        Map<String, String> details = new LinkedHashMap<>();
        var metadata = row.getMetaData();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            String name = metadata.getColumnLabel(i);
            String value = row.getString(i);
            if (value == null || value.isBlank()) continue;
            if (name.equalsIgnoreCase("catalog_attributes")) {
                for (String attribute : value.split("\\u001e")) {
                    String[] parts = attribute.split("\\u001f", 2);
                    if (parts.length == 2 && !parts[1].isBlank()) details.put(parts[0], parts[1]);
                }
            } else {
                details.put(name, value);
            }
        }
        return new Product(row.getString("product_id"), row.getString("name"),
                row.getString("vendor_code"), row.getString("barcode"), row.getString("uom"),
                "offline", Price.fromDatabase(row.getString("price")), row.getString("total_qty"), details);
    }

    public record SearchCriteria(String query, String category, String attributeName, String attributeValue,
                                 Long minimumPrice, Long maximumPrice) {
        public SearchCriteria {
            query = query == null ? "" : query.trim();
            category = category == null ? "" : category;
            attributeName = attributeName == null ? "" : attributeName;
            attributeValue = attributeValue == null ? "" : attributeValue;
        }

        public static SearchCriteria empty() { return new SearchCriteria("", "", "", "", null, null); }
        public boolean hasFilters() {
            return !category.isBlank() || !attributeName.isBlank()
                    || minimumPrice != null || maximumPrice != null;
        }
    }

    public record SearchOptions(List<String> categories, Map<String, List<String>> attributes,
                                long minimumPrice, long maximumPrice) {
        public SearchOptions {
            categories = List.copyOf(categories);
            Map<String, List<String>> copy = new LinkedHashMap<>();
            attributes.forEach((name, values) -> copy.put(name, List.copyOf(values)));
            attributes = java.util.Collections.unmodifiableMap(copy);
        }
    }
}
