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
import java.awt.Color;

/** Minimal storage for virtual sales points and the latest shelf label per article. */
public final class SalesPointRepository {
    private final java.nio.file.Path databasePath;

    public SalesPointRepository() { this(AppConfig.tasksDatabasePath()); }

    public SalesPointRepository(java.nio.file.Path databasePath) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath").toAbsolutePath().normalize();
        try {
            if (this.databasePath.getParent() != null) Files.createDirectories(this.databasePath.getParent());
            migrate();
        } catch (Exception error) {
            throw new IllegalStateException("Не удалось открыть точки продаж: " + this.databasePath, error);
        }
    }

    public void migrate() throws SQLException { initialize(); }

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

    public List<MapFixture> fixtures() throws SQLException {
        List<MapFixture> result = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT fixture_id, kind, name, x, y, width, height, rotation, color "
                     + "FROM sales_point_fixtures ORDER BY fixture_id")) {
            while (rows.next()) result.add(new MapFixture(rows.getLong(1), rows.getString(2), rows.getString(3),
                    rows.getInt(4), rows.getInt(5), rows.getInt(6), rows.getInt(7), rows.getDouble(8), rows.getString(9)));
        }
        return result;
    }

    public MapFixture createFixture(String kind, String name, int x, int y, int width, int height,
                                    double rotation, String color) throws SQLException {
        String safeKind = "walkway".equalsIgnoreCase(kind) ? "walkway" : "shelf";
        String title = required(name, "Введите название объекта.");
        int safeWidth = Math.max(36, width);
        int safeHeight = Math.max(28, height);
        String safeColor = validColor(color);
        try (Connection connection = connect(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO sales_point_fixtures(kind,name,x,y,width,height,rotation,color) VALUES(?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, safeKind); insert.setString(2, title); insert.setInt(3, x); insert.setInt(4, y);
            insert.setInt(5, safeWidth); insert.setInt(6, safeHeight); insert.setDouble(7, rotation); insert.setString(8, safeColor);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (keys.next()) return new MapFixture(keys.getLong(1), safeKind, title, x, y,
                        safeWidth, safeHeight, rotation, safeColor);
            }
        }
        throw new SQLException("Не удалось создать объект карты.");
    }

    public void moveFixture(long id, int x, int y) throws SQLException {
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE sales_point_fixtures SET x=?,y=? WHERE fixture_id=?")) {
            update.setInt(1, x); update.setInt(2, y); update.setLong(3, id); update.executeUpdate();
        }
    }

    public void updateFixture(long id, String name, int width, int height, double rotation, String color) throws SQLException {
        if (!Double.isFinite(rotation)) throw new SQLException("Угол поворота должен быть числом.");
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE sales_point_fixtures SET name=?,width=?,height=?,rotation=?,color=? WHERE fixture_id=?")) {
            update.setString(1, required(name, "Введите название объекта."));
            update.setInt(2, Math.max(36, width)); update.setInt(3, Math.max(28, height));
            update.setDouble(4, rotation % 360); update.setString(5, validColor(color)); update.setLong(6, id);
            if (update.executeUpdate() == 0) throw new SQLException("Объект карты больше не существует.");
        }
    }

    public void deleteFixture(long id) throws SQLException {
        try (Connection connection = connect(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM sales_point_fixtures WHERE fixture_id=?")) {
            delete.setLong(1, id); delete.executeUpdate();
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
                "SELECT article, name, price, discount_price, updated_at FROM sales_point_items WHERE point_id=? ORDER BY article COLLATE NOCASE")) {
            query.setLong(1, pointId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new ShelfItem(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4), rows.getLong(5)));
            }
        }
        return result;
    }

    /** Distinct SetRetailX item/barcode keys stored on all locally configured sales points. */
    public List<String> productItemOrBarcodes() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT DISTINCT TRIM(article) FROM sales_point_items "
                     + "WHERE article IS NOT NULL AND TRIM(article)<>'' ORDER BY TRIM(article) COLLATE NOCASE")) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    /** Insert or replace the current shelf label; this intentionally does not keep price history. */
    public void putItem(long pointId, String article, String name, String price) throws SQLException {
        putItem(pointId, article, name, price, "");
    }

    /** Insert or replace both the regular and discounted shelf prices. */
    public void putItem(long pointId, String article, String name, String price, String discountPrice) throws SQLException {
        String regular = price == null ? null : price.trim();
        String discounted = discountPrice == null ? null : discountPrice.trim();
        if ((regular == null || regular.isBlank()) && (discounted == null || discounted.isBlank())) {
            throw new SQLException("Введите обычную или скидочную цену.");
        }
        try (Connection connection = connect(); PreparedStatement upsert = connection.prepareStatement(
                "INSERT INTO sales_point_items(point_id, article, name, price, discount_price, updated_at) "
                        + "VALUES(?, ?, ?, COALESCE(?, ''), COALESCE(?, ''), ?) "
                        + "ON CONFLICT(point_id, article) DO UPDATE SET name=excluded.name, "
                        + "price=CASE WHEN ?=1 THEN excluded.price ELSE sales_point_items.price END, "
                        + "discount_price=CASE WHEN ?=1 THEN excluded.discount_price ELSE sales_point_items.discount_price END, "
                        + "updated_at=excluded.updated_at")) {
            upsert.setLong(1, pointId); upsert.setString(2, required(article, "Введите артикул."));
            upsert.setString(3, name == null ? "" : name.trim()); upsert.setString(4, regular);
            upsert.setString(5, discounted);
            upsert.setLong(6, System.currentTimeMillis());
            upsert.setInt(7, regular == null ? 0 : 1);
            upsert.setInt(8, discounted == null ? 0 : 1);
            upsert.executeUpdate();
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
                    + "price TEXT NOT NULL DEFAULT '', discount_price TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL, PRIMARY KEY(point_id, article))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS sales_point_fixtures (fixture_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "kind TEXT NOT NULL DEFAULT 'shelf', name TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, "
                    + "width INTEGER NOT NULL DEFAULT 120, height INTEGER NOT NULL DEFAULT 48, rotation REAL NOT NULL DEFAULT 0, "
                    + "color TEXT NOT NULL DEFAULT '#3B82F6')");
            boolean hasDiscountPrice = false;
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(sales_point_items)")) {
                while (columns.next()) if ("discount_price".equalsIgnoreCase(columns.getString("name"))) hasDiscountPrice = true;
            }
            if (!hasDiscountPrice) statement.executeUpdate(
                    "ALTER TABLE sales_point_items ADD COLUMN discount_price TEXT NOT NULL DEFAULT ''");
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

    private static String validColor(String value) throws SQLException {
        try { return String.format("#%06X", Color.decode(value == null ? "#3B82F6" : value).getRGB() & 0xFFFFFF); }
        catch (NumberFormatException error) { throw new SQLException("Выберите корректный цвет.", error); }
    }

    public record SalesPoint(long id, String name, int x, int y, int width, int height) { }
    public record MapFixture(long id, String kind, String name, int x, int y, int width, int height,
                             double rotation, String color) { }
    public record ShelfItem(String article, String name, String price, String discountPrice, long updatedAt) { }
}
