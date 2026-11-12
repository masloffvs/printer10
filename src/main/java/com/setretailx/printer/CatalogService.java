package com.setretailx.printer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Exact barcode lookup and local catalog search against all_products.sqlite. */
final class CatalogService {
    List<Product> search(String query) throws SQLException {
        String text = query == null ? "" : query.trim();
        if (text.isEmpty()) return List.of();
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
            String conditions = String.join(" OR ", searchable.stream()
                    .map(column -> "CAST(\"" + column + "\" AS TEXT) LIKE ? ESCAPE '\\' COLLATE NOCASE")
                    .toList());
            String orderColumn = columns.contains("name") ? "name" : "product_id";
            String sql = selectColumns(columns) + " FROM products WHERE " + conditions
                    + " ORDER BY \"" + orderColumn + "\" LIMIT 100";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < searchable.size(); i++) statement.setString(i + 1, pattern);
            try (ResultSet rows = statement.executeQuery()) {
                List<Product> products = new ArrayList<>();
                while (rows.next()) products.add(product(rows));
                return products;
            }
            }
        }
    }

    Product findByBarcode(String barcode) throws SQLException {
        try (Connection connection = connect()) {
            Set<String> columns = columns(connection);
            if (!columns.contains("product_id") || !columns.contains("barcode")) {
                throw new SQLException("В таблице products отсутствуют поля barcode или product_id.");
            }
            String sql = selectColumns(columns) + " FROM products WHERE CAST(barcode AS TEXT) = ? LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, barcode);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? product(rows) : null;
                }
            }
        }
    }

    private Set<String> columns(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("PRAGMA table_info(products)")) {
            while (rows.next()) columns.add(rows.getString("name").toLowerCase(java.util.Locale.ROOT));
        }
        return columns;
    }

    private String selectColumns(Set<String> columns) {
        StringBuilder select = new StringBuilder("SELECT ");
        for (String column : List.of("product_id", "name", "vendor_code", "barcode", "uom", "price", "total_qty")) {
            if (select.length() > 7) select.append(", ");
            select.append(columns.contains(column) ? "\"" + column + "\"" : "NULL")
                    .append(" AS \"").append(column).append('"');
        }
        return select.toString();
    }

    private Connection connect() throws SQLException {
        if (!java.nio.file.Files.isRegularFile(AppConfig.DB_PATH)) {
            throw new SQLException("Локальная база не найдена: " + AppConfig.DB_PATH);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + AppConfig.DB_PATH);
    }

    private Product product(ResultSet row) throws SQLException {
        return new Product(row.getString("product_id"), row.getString("name"),
                row.getString("vendor_code"), row.getString("barcode"), row.getString("uom"),
                "offline", row.getString("price"), row.getString("total_qty"));
    }
}
