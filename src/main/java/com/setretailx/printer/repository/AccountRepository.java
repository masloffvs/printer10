package com.setretailx.printer.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Parent-only account storage. Passwords are stored as salted PBKDF2 hashes. */
public final class AccountRepository {
    private static final int HASH_ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path path;

    public AccountRepository() {
        this(Path.of(System.getProperty("user.home"), ".setretailx", "accounts.sqlite"));
    }

    public AccountRepository(Path path) {
        this.path = java.util.Objects.requireNonNull(path).toAbsolutePath().normalize();
    }

    public void migrate() throws SQLException {
        try {
            Files.createDirectories(path.getParent());
        } catch (java.io.IOException error) {
            throw new SQLException("Не удалось подготовить хранилище учётных записей.", error);
        }
        restrictPermissions(path.getParent(), "rwx------");
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS accounts ("
                    + "account_id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL COLLATE NOCASE UNIQUE, "
                    + "salt BLOB NOT NULL, password_hash BLOB NOT NULL, iterations INTEGER NOT NULL, "
                    + "is_admin INTEGER NOT NULL DEFAULT 0, enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS account_permissions ("
                    + "account_id INTEGER NOT NULL REFERENCES accounts(account_id) ON DELETE CASCADE, "
                    + "permission TEXT NOT NULL, PRIMARY KEY(account_id, permission))");
        }
        restrictPermissions(path, "rw-------");
    }

    /** Creates the bootstrap administrator only while the parent account database is empty. */
    public long createFirstAdmin(String username, char[] password) throws SQLException {
        try {
            String normalized = validateUsername(username);
            validatePassword(password);
            byte[] salt = new byte[SALT_BYTES];
            RANDOM.nextBytes(salt);
            byte[] hash = hash(password, salt, HASH_ITERATIONS);
            try (Connection connection = connect()) {
                connection.setAutoCommit(false);
                try {
                    try (Statement statement = connection.createStatement();
                         ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM accounts")) {
                        if (!row.next() || row.getLong(1) != 0)
                            throw new SQLException("Первый административный аккаунт уже создан.");
                    }
                    long accountId = insertAccount(connection, normalized, salt, hash, true);
                    connection.commit();
                    return accountId;
                } catch (SQLException | RuntimeException error) {
                    connection.rollback();
                    throw error;
                } finally {
                    connection.setAutoCommit(true);
                }
            } finally {
                java.util.Arrays.fill(hash, (byte) 0);
            }
        } finally {
            if (password != null) java.util.Arrays.fill(password, '\0');
        }
    }

    public long create(String username, char[] password, Set<Permission> permissions, boolean admin)
            throws SQLException {
        try {
            String normalized = validateUsername(username);
            validatePassword(password);
            byte[] salt = new byte[SALT_BYTES];
            RANDOM.nextBytes(salt);
            byte[] hash = hash(password, salt, HASH_ITERATIONS);
            try (Connection connection = connect()) {
                connection.setAutoCommit(false);
                try {
                    long accountId = insertAccount(connection, normalized, salt, hash, admin);
                    if (permissions != null && !permissions.isEmpty()) {
                        try (PreparedStatement grant = connection.prepareStatement(
                                "INSERT INTO account_permissions(account_id,permission) VALUES(?,?)")) {
                            for (Permission permission : permissions) {
                                grant.setLong(1, accountId);
                                grant.setString(2, permission.name());
                                grant.addBatch();
                            }
                            grant.executeBatch();
                        }
                    }
                    connection.commit();
                    return accountId;
                } catch (SQLException | RuntimeException error) {
                    connection.rollback();
                    throw error;
                } finally {
                    connection.setAutoCommit(true);
                }
            } finally {
                java.util.Arrays.fill(hash, (byte) 0);
            }
        } finally {
            if (password != null) java.util.Arrays.fill(password, '\0');
        }
    }

    public Account authenticate(String username, char[] password) throws SQLException {
        try {
            if (username == null || username.isBlank() || password == null || password.length == 0) return null;
            try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT account_id,username,salt,password_hash,iterations,is_admin FROM accounts "
                        + "WHERE username=? COLLATE NOCASE AND enabled=1")) {
                query.setString(1, username.trim());
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) return null;
                    int iterations = row.getInt("iterations");
                    if (iterations < 100_000 || iterations > 2_000_000) return null;
                    byte[] candidate = hash(password, row.getBytes("salt"), iterations);
                    try {
                        if (!MessageDigest.isEqual(candidate, row.getBytes("password_hash"))) return null;
                        return new Account(row.getLong("account_id"), row.getString("username"),
                                row.getInt("is_admin") != 0, true,
                                permissions(connection, row.getLong("account_id")));
                    } finally {
                        java.util.Arrays.fill(candidate, (byte) 0);
                    }
                }
            }
        } finally {
            if (password != null) java.util.Arrays.fill(password, '\0');
        }
    }

    public Account findEnabled(long accountId) throws SQLException {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT username,is_admin FROM accounts WHERE account_id=? AND enabled=1")) {
            query.setLong(1, accountId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return null;
                return new Account(accountId, row.getString("username"), row.getInt("is_admin") != 0, true,
                        permissions(connection, accountId));
            }
        }
    }

    public long countAccounts() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM accounts")) {
            return row.next() ? row.getLong(1) : 0;
        }
    }

    public Set<Account> listAccounts() throws SQLException {
        java.util.LinkedHashSet<Account> result = new java.util.LinkedHashSet<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT account_id,username,is_admin,enabled FROM accounts ORDER BY username COLLATE NOCASE")) {
            while (rows.next()) {
                long id = rows.getLong("account_id");
                result.add(new Account(id, rows.getString("username"), rows.getInt("is_admin") != 0,
                        rows.getInt("enabled") != 0,
                        permissions(connection, id)));
            }
        }
        return Set.copyOf(result);
    }

    private static long insertAccount(Connection connection, String username, byte[] salt, byte[] hash,
                                      boolean admin) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO accounts(username,salt,password_hash,iterations,is_admin,created_at) VALUES(?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, username);
            insert.setBytes(2, salt);
            insert.setBytes(3, hash);
            insert.setInt(4, HASH_ITERATIONS);
            insert.setInt(5, admin ? 1 : 0);
            insert.setLong(6, System.currentTimeMillis());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Не удалось создать учётную запись.");
                return keys.getLong(1);
            }
        }
    }

    public void setEnabled(long accountId, boolean enabled) throws SQLException {
        try (Connection connection = connect()) {
            if (!enabled) {
                try (PreparedStatement guard = connection.prepareStatement(
                        "SELECT is_admin FROM accounts WHERE account_id=? AND enabled=1")) {
                    guard.setLong(1, accountId);
                    try (ResultSet row = guard.executeQuery()) {
                        if (!row.next()) throw new SQLException("Учётная запись не найдена или уже отключена.");
                        if (row.getInt(1) != 0) {
                            try (Statement count = connection.createStatement();
                                 ResultSet admins = count.executeQuery(
                                         "SELECT COUNT(*) FROM accounts WHERE enabled=1 AND is_admin=1")) {
                                if (admins.next() && admins.getLong(1) <= 1)
                                    throw new SQLException("Нельзя отключить последнего активного администратора.");
                            }
                        }
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE accounts SET enabled=? WHERE account_id=?")) {
                statement.setInt(1, enabled ? 1 : 0);
                statement.setLong(2, accountId);
                if (statement.executeUpdate() != 1) throw new SQLException("Учётная запись не найдена.");
            }
        }
    }

    public void replacePermissions(long accountId, Set<Permission> permissions) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM account_permissions WHERE account_id=?")) {
                    delete.setLong(1, accountId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO account_permissions(account_id,permission) VALUES(?,?)")) {
                    for (Permission permission : permissions == null ? Set.<Permission>of() : permissions) {
                        insert.setLong(1, accountId);
                        insert.setString(2, permission.name());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void updateAccess(long accountId, Set<Permission> permissions, boolean admin) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                if (!admin) {
                    try (PreparedStatement guard = connection.prepareStatement(
                            "SELECT is_admin,enabled FROM accounts WHERE account_id=?")) {
                        guard.setLong(1, accountId);
                        try (ResultSet row = guard.executeQuery()) {
                            if (!row.next()) throw new SQLException("Учётная запись не найдена.");
                            if (row.getInt("is_admin") != 0 && row.getInt("enabled") != 0) {
                                try (Statement count = connection.createStatement();
                                     ResultSet admins = count.executeQuery(
                                             "SELECT COUNT(*) FROM accounts WHERE enabled=1 AND is_admin=1")) {
                                    if (admins.next() && admins.getLong(1) <= 1)
                                        throw new SQLException("Нельзя снять права последнего активного администратора.");
                                }
                            }
                        }
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE accounts SET is_admin=? WHERE account_id=?")) {
                    update.setInt(1, admin ? 1 : 0);
                    update.setLong(2, accountId);
                    if (update.executeUpdate() != 1) throw new SQLException("Учётная запись не найдена.");
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM account_permissions WHERE account_id=?")) {
                    delete.setLong(1, accountId);
                    delete.executeUpdate();
                }
                try (PreparedStatement grant = connection.prepareStatement(
                        "INSERT INTO account_permissions(account_id,permission) VALUES(?,?)")) {
                    for (Permission permission : permissions == null ? Set.<Permission>of() : permissions) {
                        grant.setLong(1, accountId);
                        grant.setString(2, permission.name());
                        grant.addBatch();
                    }
                    grant.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void setAdministrator(long accountId, boolean admin) throws SQLException {
        try (Connection connection = connect()) {
            if (!admin) {
                try (PreparedStatement guard = connection.prepareStatement(
                        "SELECT is_admin,enabled FROM accounts WHERE account_id=?")) {
                    guard.setLong(1, accountId);
                    try (ResultSet row = guard.executeQuery()) {
                        if (!row.next()) throw new SQLException("Учётная запись не найдена.");
                        if (row.getInt("is_admin") != 0 && row.getInt("enabled") != 0) {
                            try (Statement count = connection.createStatement();
                                 ResultSet admins = count.executeQuery(
                                         "SELECT COUNT(*) FROM accounts WHERE enabled=1 AND is_admin=1")) {
                                if (admins.next() && admins.getLong(1) <= 1)
                                    throw new SQLException("Нельзя снять права последнего активного администратора.");
                            }
                        }
                    }
                }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE accounts SET is_admin=? WHERE account_id=?")) {
                update.setInt(1, admin ? 1 : 0);
                update.setLong(2, accountId);
                if (update.executeUpdate() != 1) throw new SQLException("Учётная запись не найдена.");
            }
        }
    }

    private static Set<Permission> permissions(Connection connection, long accountId) throws SQLException {
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT permission FROM account_permissions WHERE account_id=?")) {
            query.setLong(1, accountId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    try { result.add(Permission.valueOf(rows.getString(1))); }
                    catch (IllegalArgumentException ignored) { /* Ignore permissions added by a newer version. */ }
                }
            }
        }
        return Set.copyOf(result);
    }

    private static String validateUsername(String username) {
        if (username == null) throw new IllegalArgumentException("Введите имя пользователя.");
        String normalized = username.trim();
        if (normalized.length() < 2 || normalized.length() > 64
                || !normalized.matches("[\\p{L}\\p{N}_.@-]+")) {
            throw new IllegalArgumentException("Имя пользователя должно содержать 2–64 допустимых символа.");
        }
        return normalized;
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length < 10 || password.length > 1024)
            throw new IllegalArgumentException("Пароль должен содержать от 10 до 1024 символов.");
    }

    private static byte[] hash(char[] password, byte[] salt, int iterations) throws SQLException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException error) {
            throw new SQLException("Не удалось проверить пароль.", error);
        } finally {
            spec.clearPassword();
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=15000");
        }
        return connection;
    }

    private static void restrictPermissions(Path target, String permissions) throws SQLException {
        try {
            Files.setPosixFilePermissions(target,
                    java.nio.file.attribute.PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException ignored) {
            // The platform does not expose POSIX permissions; rely on its user-private application directory.
        } catch (java.io.IOException error) {
            throw new SQLException("Не удалось ограничить доступ к хранилищу учётных записей.", error);
        }
    }

    public enum Permission {
        SALES_READ, SHARED_DATA_WRITE, ACCOUNT_ADMIN, CATALOG_READ, REFERENCE_READ;

        public static Permission fromWire(String value) {
            if (value == null) return null;
            try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { return null; }
        }
    }

    public record Account(long id, String username, boolean admin, boolean enabled, Set<Permission> permissions) {
        public Account { permissions = Set.copyOf(permissions == null ? Set.of() : permissions); }
        public boolean can(Permission permission) {
            return admin || permission == Permission.CATALOG_READ || permission == Permission.REFERENCE_READ
                    || permissions.contains(permission);
        }
    }
}
