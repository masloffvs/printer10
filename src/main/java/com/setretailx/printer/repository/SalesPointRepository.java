package com.setretailx.printer.repository;

import com.setretailx.printer.config.AppConfig;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Minimal storage for virtual sales points and the latest shelf label per article. */
public final class SalesPointRepository {
    private final java.nio.file.Path databasePath;

    public SalesPointRepository() { this(AppConfig.tasksDatabasePath()); }

    public SalesPointRepository(java.nio.file.Path databasePath) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath").toAbsolutePath().normalize();
        try {
            if (this.databasePath.getParent() != null) Files.createDirectories(this.databasePath.getParent());
            initialize();
        } catch (Exception error) {
            throw new IllegalStateException("Не удалось открыть точки продаж: " + this.databasePath, error);
        }
    }

    public List<SalesPoint> points() throws SQLException {
        List<SalesPoint> result = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT point_id, name, x, y, width, height FROM sales_points ORDER BY point_id")) {
            while (rows.next()) result.add(new SalesPoint(rows.getLong(1), rows.getString(2),
                    rows.getInt(3), rows.getInt(4), rows.getInt(5), rows.getInt(6)));
        }
        return result;
    }

    public SalesPoint createPoint(String name, int x, int y, int width, int height) throws SQLException {
        String title = required(name, "Введите название точки.");
        try (Connection connection = connect(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO sales_points(name, x, y, width, height) VALUES(?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, title); insert.setInt(2, x); insert.setInt(3, y);
            insert.setInt(4, Math.max(80, width)); insert.setInt(5, Math.max(48, height));
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (keys.next()) return new SalesPoint(keys.getLong(1), title, x, y, Math.max(80, width), Math.max(48, height));
            }
        }
        throw new SQLException("Не удалось создать точку продаж.");
    }

    public void renamePoint(long id, String name) throws SQLException {
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE sales_points SET name=? WHERE point_id=?")) {
            update.setString(1, required(name, "Введите название точки.")); update.setLong(2, id);
            if (update.executeUpdate() == 0) throw new SQLException("Точка больше не существует.");
        }
    }

    public void movePoint(long id, int x, int y) throws SQLException {
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE sales_points SET x=?, y=? WHERE point_id=?")) {
            update.setInt(1, x); update.setInt(2, y); update.setLong(3, id); update.executeUpdate();
        }
    }

    public void deletePoint(long id) throws SQLException {
        try (Connection connection = connect(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM sales_points WHERE point_id=?")) {
            delete.setLong(1, id); delete.executeUpdate();
        }
    }

    public List<ShelfItem> items(long pointId) throws SQLException {
        List<ShelfItem> result = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT article, name, price, updated_at FROM sales_point_items WHERE point_id=? ORDER BY article COLLATE NOCASE")) {
            query.setLong(1, pointId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new ShelfItem(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getLong(4)));
            }
        }
        return result;
    }

    /** Insert or replace the current shelf label; this intentionally does not keep price history. */
    public void putItem(long pointId, String article, String name, String price) throws SQLException {
        try (Connection connection = connect(); PreparedStatement upsert = connection.prepareStatement(
                "INSERT INTO sales_point_items(point_id, article, name, price, updated_at) VALUES(?, ?, ?, ?, ?) "
                        + "ON CONFLICT(point_id, article) DO UPDATE SET name=excluded.name, price=excluded.price, updated_at=excluded.updated_at")) {
            upsert.setLong(1, pointId); upsert.setString(2, required(article, "Введите артикул."));
            upsert.setString(3, name == null ? "" : name.trim()); upsert.setString(4, required(price, "Введите цену ценника."));
            upsert.setLong(5, System.currentTimeMillis()); upsert.executeUpdate();
        }
    }

    public void removeItem(long pointId, String article) throws SQLException {
        try (Connection connection = connect(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM sales_point_items WHERE point_id=? AND article=?")) {
            delete.setLong(1, pointId); delete.setString(2, article); delete.executeUpdate();
        }
    }

    private void initialize() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS sales_points (point_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "name TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS sales_point_items (point_id INTEGER NOT NULL "
                    + "REFERENCES sales_points(point_id) ON DELETE CASCADE, article TEXT NOT NULL, name TEXT NOT NULL DEFAULT '', "
                    + "price TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(point_id, article))");
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=15000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private static String required(String value, String message) throws SQLException {
        if (value == null || value.isBlank()) throw new SQLException(message);
        return value.trim();
    }

    public record SalesPoint(long id, String name, int x, int y, int width, int height) { }
    public record ShelfItem(String article, String name, String price, long updatedAt) { }
}
