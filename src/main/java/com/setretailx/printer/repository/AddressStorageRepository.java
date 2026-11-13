package com.setretailx.printer.repository;

import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.model.Product;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/** SQLite-backed virtual addresses, boxes, and product placements. */
public final class AddressStorageRepository {
    private static final char[] BOX_VOWELS = "АЕЁИОУЫЭЮЯ".toCharArray();
    private static final char[] BOX_CONSONANTS = "БВГДЖЗЙКЛМНПРСТФХЦЧШЩ".toCharArray();
    private final java.nio.file.Path databasePath;

    public AddressStorageRepository() {
        this(AppConfig.tasksDatabasePath());
    }

    public AddressStorageRepository(java.nio.file.Path databasePath) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath").toAbsolutePath().normalize();
        try {
            if (this.databasePath.getParent() != null) Files.createDirectories(this.databasePath.getParent());
            initialize();
        } catch (Exception error) {
            throw new IllegalStateException("Не удалось открыть адресное хранение: " + this.databasePath, error);
        }
    }

    public List<StorageAddress> addresses() throws SQLException {
        List<StorageAddress> result = new ArrayList<>();
        try (Connection connection = connect(); Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT address_id, name FROM storage_addresses ORDER BY address_id COLLATE NOCASE")) {
            while (rows.next()) result.add(new StorageAddress(rows.getString(1), rows.getString(2)));
        }
        return result;
    }

    public void createAddress(String addressId, String name) throws SQLException {
        String id = require(addressId, "Введите ID хранилища.");
        String title = name == null || name.isBlank() ? id : name.trim();
        try (Connection connection = connect(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO storage_addresses(address_id, name, created_at) VALUES(?, ?, ?)")) {
            insert.setString(1, id);
            insert.setString(2, title);
            insert.setLong(3, System.currentTimeMillis());
            insert.executeUpdate();
        }
    }

    public void renameAddress(String addressId, String name) throws SQLException {
        String id = require(addressId, "Адрес не выбран.");
        String title = require(name, "Введите название адреса.");
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE storage_addresses SET name=? WHERE address_id=?")) {
            update.setString(1, title);
            update.setString(2, id);
            if (update.executeUpdate() == 0) throw new SQLException("Адрес больше не существует: " + id);
        }
    }

    /** Deletes an address and, through the configured foreign keys, all its boxes and contents. */
    public void deleteAddress(String addressId) throws SQLException {
        String id = require(addressId, "Адрес не выбран.");
        try (Connection connection = connect(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM storage_addresses WHERE address_id=?")) {
            delete.setString(1, id);
            if (delete.executeUpdate() == 0) throw new SQLException("Адрес больше не существует: " + id);
        }
    }

    public List<StorageBox> boxes(String addressId) throws SQLException {
        List<StorageBox> result = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT b.box_id, b.address_id, b.box_code, b.created_at, COUNT(p.product_key) "
                        + "FROM storage_boxes b LEFT JOIN storage_box_products p ON p.box_id=b.box_id "
                        + "WHERE b.address_id=? GROUP BY b.box_id ORDER BY b.created_at DESC, b.box_code")) {
            query.setString(1, addressId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new StorageBox(rows.getLong(1), rows.getString(2),
                        rows.getString(3), rows.getLong(4), rows.getInt(5)));
            }
        }
        return result;
    }

    public StorageBox box(long boxId) throws SQLException {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT b.box_id, b.address_id, b.box_code, b.created_at, COUNT(p.product_key) "
                        + "FROM storage_boxes b LEFT JOIN storage_box_products p ON p.box_id=b.box_id "
                        + "WHERE b.box_id=? GROUP BY b.box_id")) {
            query.setLong(1, boxId);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? new StorageBox(row.getLong(1), row.getString(2), row.getString(3),
                        row.getLong(4), row.getInt(5)) : null;
            }
        }
    }

    public StorageBox createBox(String addressId) throws SQLException {
        try (Connection connection = connect()) {
            for (int attempt = 0; attempt < 100; attempt++) {
                String code = newBoxCode();
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO storage_boxes(address_id, box_code, created_at) VALUES(?, ?, ?)")) {
                    insert.setString(1, addressId);
                    insert.setString(2, code);
                    insert.setLong(3, System.currentTimeMillis());
                    insert.executeUpdate();
                    try (PreparedStatement query = connection.prepareStatement(
                            "SELECT box_id, address_id, box_code, created_at FROM storage_boxes WHERE box_code=?")) {
                        query.setString(1, code);
                        try (ResultSet row = query.executeQuery()) {
                            if (row.next()) return new StorageBox(row.getLong(1), row.getString(2),
                                    row.getString(3), row.getLong(4), 0);
                        }
                    }
                } catch (SQLException error) {
                    if (!isConstraint(error)) throw error;
                }
            }
        }
        throw new SQLException("Не удалось подобрать уникальный код коробки. Повторите попытку.");
    }

    /** Deletes a box and all products stored in it. */
    public void deleteBox(long boxId) throws SQLException {
        try (Connection connection = connect(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM storage_boxes WHERE box_id=?")) {
            delete.setLong(1, boxId);
            if (delete.executeUpdate() == 0) throw new SQLException("Коробка больше не существует.");
        }
    }

    public void addProduct(long boxId, Product product) throws SQLException {
        Objects.requireNonNull(product, "product");
        String key = productKey(product);
        if (key.isBlank()) throw new SQLException("У товара нет артикула, штрихкода или ID.");
        try (Connection connection = connect(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO storage_box_products(box_id, product_key, product_id, name, vendor_code, barcode, quantity, updated_at) "
                        + "VALUES(?, ?, ?, ?, ?, ?, 1, ?) ON CONFLICT(box_id, product_key) DO UPDATE SET "
                        + "name=excluded.name, vendor_code=excluded.vendor_code, barcode=excluded.barcode, "
                        + "quantity=storage_box_products.quantity+1, updated_at=excluded.updated_at")) {
            insert.setLong(1, boxId);
            insert.setString(2, key);
            insert.setString(3, product.stCode());
            insert.setString(4, product.name());
            insert.setString(5, product.vendorCode());
            insert.setString(6, product.barcode());
            insert.setLong(7, System.currentTimeMillis());
            insert.executeUpdate();
        }
    }

    public List<StoredProduct> productsInBox(long boxId) throws SQLException {
        List<StoredProduct> result = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT product_id, name, vendor_code, barcode, quantity FROM storage_box_products "
                        + "WHERE box_id=? ORDER BY name COLLATE NOCASE, vendor_code")) {
            query.setLong(1, boxId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new StoredProduct(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4), rows.getInt(5)));
            }
        }
        return result;
    }

    public List<LocationResult> findLocations(String text) throws SQLException {
        String queryText = text == null ? "" : text.trim();
        if (queryText.isEmpty()) return List.of();
        String like = "%" + queryText.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<LocationResult> result = new ArrayList<>();
        String sql = "SELECT a.address_id, a.name AS address_name, b.box_code, p.name, p.vendor_code, p.barcode, p.quantity "
                + "FROM storage_box_products p JOIN storage_boxes b ON b.box_id=p.box_id "
                + "JOIN storage_addresses a ON a.address_id=b.address_id "
                + "WHERE p.vendor_code LIKE ? ESCAPE '\\' COLLATE NOCASE OR p.barcode LIKE ? ESCAPE '\\' COLLATE NOCASE "
                + "OR p.name LIKE ? ESCAPE '\\' COLLATE NOCASE OR p.product_id LIKE ? ESCAPE '\\' COLLATE NOCASE "
                + "OR b.box_code LIKE ? ESCAPE '\\' COLLATE NOCASE OR a.address_id LIKE ? ESCAPE '\\' COLLATE NOCASE "
                + "ORDER BY a.address_id COLLATE NOCASE, b.box_code, p.name COLLATE NOCASE LIMIT 500";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 1; i <= 6; i++) statement.setString(i, like);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new LocationResult(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6), rows.getInt(7)));
            }
        }
        return result;
    }

    public List<LocationResult> locationsInBox(long boxId) throws SQLException {
        List<LocationResult> result = new ArrayList<>();
        String sql = "SELECT a.address_id, a.name, b.box_code, p.name, p.vendor_code, p.barcode, p.quantity "
                + "FROM storage_box_products p JOIN storage_boxes b ON b.box_id=p.box_id "
                + "JOIN storage_addresses a ON a.address_id=b.address_id WHERE b.box_id=? "
                + "ORDER BY p.name COLLATE NOCASE, p.vendor_code";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, boxId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new LocationResult(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6), rows.getInt(7)));
            }
        }
        return result;
    }

    private void initialize() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS storage_addresses (address_id TEXT PRIMARY KEY, "
                    + "name TEXT NOT NULL, created_at INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS storage_boxes (box_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "address_id TEXT NOT NULL REFERENCES storage_addresses(address_id) ON DELETE CASCADE, "
                    + "box_code TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS storage_box_products (box_id INTEGER NOT NULL "
                    + "REFERENCES storage_boxes(box_id) ON DELETE CASCADE, product_key TEXT NOT NULL, product_id TEXT NOT NULL DEFAULT '', "
                    + "name TEXT NOT NULL DEFAULT '', vendor_code TEXT NOT NULL DEFAULT '', barcode TEXT NOT NULL DEFAULT '', "
                    + "quantity INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL, PRIMARY KEY(box_id, product_key))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_storage_product_vendor ON storage_box_products(vendor_code)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_storage_product_barcode ON storage_box_products(barcode)");
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

    private static String productKey(Product product) {
        if (!product.vendorCode().isBlank()) return "article:" + product.vendorCode().trim().toLowerCase(Locale.ROOT);
        if (!product.barcode().isBlank()) return "barcode:" + product.barcode().trim();
        if (!product.stCode().isBlank()) return "id:" + product.stCode().trim();
        return "";
    }

    private static String newBoxCode() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder code = new StringBuilder(6);
        code.append(BOX_VOWELS[random.nextInt(BOX_VOWELS.length)]);
        code.append(BOX_VOWELS[random.nextInt(BOX_VOWELS.length)]);
        code.append(BOX_CONSONANTS[random.nextInt(BOX_CONSONANTS.length)]);
        for (int i = 0; i < 3; i++) code.append(random.nextInt(1, 10));
        return code.toString();
    }

    private static String require(String value, String message) throws SQLException {
        if (value == null || value.isBlank()) throw new SQLException(message);
        return value.trim();
    }

    private static boolean isConstraint(SQLException error) {
        return error.getErrorCode() == 19 || (error.getMessage() != null && error.getMessage().contains("SQLITE_CONSTRAINT"));
    }

    public record StorageAddress(String addressId, String name) {
        @Override public String toString() { return addressId + " — " + name; }
    }
    public record StorageBox(long boxId, String addressId, String boxCode, long createdAt, int productCount) {
        @Override public String toString() { return boxCode + " · товаров: " + productCount; }
    }
    public record StoredProduct(String productId, String name, String vendorCode, String barcode, int quantity) { }
    public record LocationResult(String addressId, String addressName, String boxCode, String name,
                                 String vendorCode, String barcode, int quantity) { }
}
