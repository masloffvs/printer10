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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import org.sqlite.Function;

/** Exact barcode lookup and local catalog search against all_products.sqlite. */
public final class ProductRepository {
    private static final int MAX_CACHED_PRODUCTS = 12_000;
    private static final Pattern ADVANCED_SIZE = Pattern.compile("(?iu)(\\d+)\\s*[/xх×]\\s*(\\d+)");
    private static final Pattern ADVANCED_TERM = Pattern.compile("(?iu)\\d+\\s*[/xх×]\\s*\\d+|[\\p{L}\\p{N}_]+");
    private static final Pattern SIZE_TERM = Pattern.compile("^(\\d+)/(\\d+)$");
    private static volatile CatalogCache catalogCache;

    /** Checks the catalog's hot-path indexes and warms a heap-sized, bounded lookup cache. */
    public StartupSummary prepareForStartup(Consumer<StartupProgress> progress) throws SQLException {
        Consumer<StartupProgress> updates = progress == null ? ignored -> { } : progress;
        updates.accept(new StartupProgress("Проверяем базу товаров и индексы…", -1));
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            Set<String> productColumns = columns(connection);
            if (!productColumns.contains("product_id")) {
                throw new SQLException("В таблице products отсутствует поле product_id.");
            }
            int indexesCreated = 0;
            if (productColumns.contains("barcode")) {
                indexesCreated += ensureIndex(statement, connection, "products", "idx_products_barcode", "barcode");
            }
            if (productColumns.contains("vendor_code")) {
                indexesCreated += ensureIndex(statement, connection, "products", "idx_products_vendor_code", "vendor_code");
            }
            if (productColumns.contains("product_id") && !isPrimaryKey(connection, "products", "product_id")) {
                indexesCreated += ensureIndex(statement, connection, "products", "idx_products_product_id", "product_id");
            }
            indexesCreated += ensureIndexIfPresent(statement, connection, "product_categories_path",
                    "idx_product_categories_path_product_position", "product_id", "position");
            indexesCreated += ensureIndexIfPresent(statement, connection, "product_attributes",
                    "idx_product_attributes_product", "product_id");
            indexesCreated += ensureIndexIfPresent(statement, connection, "product_attribute_values",
                    "idx_product_attribute_values_attribute", "attribute_id");
            updates.accept(new StartupProgress("Индексы проверены · загружаем каталог в оперативную память…", -1));

            int cacheLimit = cacheLimit();
            long total;
            try (ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM products")) {
                total = row.next() ? row.getLong(1) : 0;
            }
            int target = (int) Math.min(total, cacheLimit);
            Map<String, Product> byBarcode = new HashMap<>(Math.max(16, target));
            Map<String, Product> byMarking = new HashMap<>(Math.max(16, target));
            String sql = selectColumns(connection, productColumns) + " FROM products ORDER BY product_id LIMIT ?";
            try (PreparedStatement products = connection.prepareStatement(sql)) {
                products.setInt(1, target);
                try (ResultSet rows = products.executeQuery()) {
                    int loaded = 0;
                    while (rows.next()) {
                        Product product = product(rows);
                        if (!product.barcode().isBlank()) byBarcode.putIfAbsent(product.barcode().trim(), product);
                        String marking = product.marking().trim();
                        if (!marking.isBlank()) byMarking.putIfAbsent(marking, product);
                        loaded++;
                        if (loaded % 500 == 0 || loaded == target) {
                            int percent = target == 0 ? 100 : (int) ((long) loaded * 100 / target);
                            updates.accept(new StartupProgress("Загружаем товары: " + loaded + " из " + total, percent));
                        }
                    }
                    java.nio.file.Path databasePath = AppConfig.DB_PATH.toAbsolutePath().normalize();
                    catalogCache = new CatalogCache(databasePath, fileSize(databasePath), lastModified(databasePath),
                            Map.copyOf(byBarcode), Map.copyOf(byMarking), loaded, total);
                    updates.accept(new StartupProgress("Каталог готов · " + loaded + " товаров в быстром кэше", 100));
                    return new StartupSummary(total, loaded, indexesCreated);
                }
            }
        }
    }

    private static int cacheLimit() {
        long heapBasedLimit = Runtime.getRuntime().maxMemory() / (128L * 1024);
        return (int) Math.max(500, Math.min(MAX_CACHED_PRODUCTS, heapBasedLimit));
    }

    private static int ensureIndexIfPresent(Statement statement, Connection connection, String table,
                                           String index, String... columns) throws SQLException {
        if (!hasTable(connection, table)) return 0;
        Set<String> existing = tableColumns(connection, table);
        for (String column : columns) if (!existing.contains(column)) return 0;
        return ensureIndex(statement, connection, table, index, columns);
    }

    private static int ensureIndex(Statement statement, Connection connection, String table,
                                   String index, String... columns) throws SQLException {
        if (hasIndex(connection, index) || hasIndexForColumns(connection, table, columns)) return 0;
        String columnList = String.join(", ", java.util.Arrays.stream(columns).map(ProductRepository::quote).toList());
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS " + quote(index) + " ON " + quote(table)
                + " (" + columnList + ")");
        return 1;
    }

    private static boolean hasIndex(Connection connection, String index) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?")) {
            statement.setString(1, index);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static boolean hasIndexForColumns(Connection connection, String table, String... expectedColumns)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet indexes = statement.executeQuery("PRAGMA index_list(" + quote(table) + ")")) {
            while (indexes.next()) {
                if (indexes.getInt("partial") != 0) continue;
                String indexName = indexes.getString("name");
                List<String> indexedColumns = new ArrayList<>();
                try (Statement details = connection.createStatement();
                     ResultSet columns = details.executeQuery("PRAGMA index_info(" + quote(indexName) + ")")) {
                    while (columns.next()) indexedColumns.add(columns.getString("name"));
                }
                if (indexedColumns.size() < expectedColumns.length) continue;
                boolean matches = true;
                for (int i = 0; i < expectedColumns.length; i++) {
                    if (!expectedColumns[i].equalsIgnoreCase(indexedColumns.get(i))) {
                        matches = false;
                        break;
                    }
                }
                if (matches) return true;
            }
        }
        return false;
    }

    private static boolean isPrimaryKey(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + quote(table) + ")")) {
            while (rows.next()) {
                if (column.equalsIgnoreCase(rows.getString("name"))) return rows.getInt("pk") > 0;
            }
        }
        return false;
    }

    private static Set<String> tableColumns(Connection connection, String table) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + quote(table) + ")")) {
            while (rows.next()) columns.add(rows.getString("name").toLowerCase(java.util.Locale.ROOT));
        }
        return columns;
    }

    public List<Product> search(String query) throws SQLException {
        return search(new SearchCriteria(query, "", "", "", null, null));
    }

    public List<Product> search(SearchCriteria criteria) throws SQLException {
        SearchCriteria filters = criteria == null ? SearchCriteria.empty() : criteria;
        String text = filters.query().trim();
        try (Connection connection = connect()) {
            Function.create(connection, "unicode_lower", new Function() {
                @Override protected void xFunc() throws SQLException {
                    String value = value_text(0);
                    result(value == null ? "" : value.toLowerCase(Locale.ROOT));
                }
            });
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
                if (filters.advancedSearch()) {
                    for (String term : advancedTerms(text)) {
                        List<String> alternatives = new ArrayList<>();
                        for (String variant : termVariants(term)) {
                            String pattern = likePattern(variant);
                            for (String column : searchable) {
                                alternatives.add("unicode_lower(CAST(\"" + column
                                        + "\" AS TEXT)) LIKE unicode_lower(?) ESCAPE '\\'");
                                parameters.add(pattern);
                            }
                        }
                        if (!alternatives.isEmpty()) conditions.add("(" + String.join(" OR ", alternatives) + ")");
                    }
                } else {
                    conditions.add("(" + String.join(" OR ", searchable.stream()
                            .map(column -> "unicode_lower(CAST(\"" + column + "\" AS TEXT)) LIKE unicode_lower(?) ESCAPE '\\'")
                            .toList()) + ")");
                    String pattern = likePattern(text);
                    for (int i = 0; i < searchable.size(); i++) parameters.add(pattern);
                }
            }
            if (!filters.category().isBlank() && hasTable(connection, "product_categories_path")) {
                String categoryExpression = "unicode_lower((SELECT group_concat(name, ' › ') "
                        + "FROM (SELECT name FROM product_categories_path pcp "
                        + "WHERE pcp.product_id = products.product_id ORDER BY position)))";
                conditions.add("(" + categoryExpression + " = unicode_lower(?) OR "
                        + categoryExpression + " LIKE unicode_lower(?) ESCAPE '\\')");
                parameters.add(filters.category());
                parameters.add(filters.category().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                        + " › %");
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
            List<Object> orderingParameters = new ArrayList<>();
            String relevance = relevanceOrder(columns, text, orderingParameters);
            String sql = selectColumns(connection, columns) + " FROM products"
                    + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions))
                    + " ORDER BY " + relevance + " LIMIT 500";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
                for (int i = 0; i < orderingParameters.size(); i++) {
                    statement.setObject(parameters.size() + i + 1, orderingParameters.get(i));
                }
                try (ResultSet rows = statement.executeQuery()) {
                    List<Product> products = new ArrayList<>();
                    while (rows.next()) products.add(product(rows));
                    return products;
                }
            }
        }
    }

    private static List<String> advancedTerms(String text) {
        Matcher matcher = ADVANCED_TERM.matcher(text);
        List<String> terms = new ArrayList<>();
        while (matcher.find()) {
            String term = ADVANCED_SIZE.matcher(matcher.group()).replaceAll("$1/$2");
            terms.add(term);
        }
        return terms.isEmpty() ? List.of(text) : terms;
    }

    private static List<String> termVariants(String term) {
        Matcher size = SIZE_TERM.matcher(term);
        if (!size.matches()) return List.of(term);
        String first = size.group(1);
        String second = size.group(2);
        return List.of(first + "/" + second, first + "x" + second, first + "х" + second,
                first + "×" + second);
    }

    private static String likePattern(String value) {
        return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String relevanceOrder(Set<String> columns, String text, List<Object> parameters) {
        if (text.isEmpty()) return columns.contains("name") ? "\"name\" COLLATE NOCASE, product_id" : "product_id";

        StringBuilder order = new StringBuilder("CASE");
        if (columns.contains("barcode")) {
            order.append(" WHEN CAST(\"barcode\" AS TEXT) = ? THEN 0");
            parameters.add(text);
            order.append(" WHEN unicode_lower(CAST(\"barcode\" AS TEXT)) = unicode_lower(?) THEN 1");
            parameters.add(text);
        }
        if (columns.contains("vendor_code")) {
            order.append(" WHEN unicode_lower(CAST(\"vendor_code\" AS TEXT)) = unicode_lower(?) THEN 2");
            parameters.add(text);
        }
        if (columns.contains("name")) {
            order.append(" WHEN unicode_lower(CAST(\"name\" AS TEXT)) = unicode_lower(?) THEN 3");
            parameters.add(text);
        }
        if (columns.contains("barcode")) {
            order.append(" WHEN unicode_lower(CAST(\"barcode\" AS TEXT)) LIKE unicode_lower(?) ESCAPE '\\' THEN 4");
            parameters.add(text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (columns.contains("vendor_code")) {
            order.append(" WHEN unicode_lower(CAST(\"vendor_code\" AS TEXT)) LIKE unicode_lower(?) ESCAPE '\\' THEN 5");
            parameters.add(text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (columns.contains("name")) {
            order.append(" WHEN unicode_lower(CAST(\"name\" AS TEXT)) LIKE unicode_lower(?) ESCAPE '\\' THEN 6");
            parameters.add(text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        order.append(" ELSE 7 END");
        if (columns.contains("name")) order.append(", unicode_lower(CAST(\"name\" AS TEXT))");
        else order.append(", product_id");
        return order.toString();
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
        if (barcode != null) {
            CatalogCache cache = currentCache();
            if (cache != null) {
                Product cached = cache.byBarcode().get(barcode.trim());
                if (cached != null) return cached;
            }
        }
        try (Connection connection = connect()) {
            Set<String> columns = columns(connection);
            if (!columns.contains("product_id") || !columns.contains("barcode")) {
                throw new SQLException("В таблице products отсутствуют поля barcode или product_id.");
            }
            String sql = selectColumns(connection, columns) + " FROM products WHERE barcode = ? LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, barcode.trim());
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? product(rows) : null;
                }
            }
        }
    }

    /**
     * Fast exact lookup for pallet scans. The pallet workflow only needs the
     * product identity and label fields, so avoid loading correlated category
     * and attribute details on a cache miss.
     */
    public Product findByBarcodeForPallet(String barcode) throws SQLException {
        if (barcode == null || barcode.isBlank()) return null;
        String normalizedBarcode = barcode.trim();
        CatalogCache cache = currentCache();
        if (cache != null) {
            Product cached = cache.byBarcode().get(normalizedBarcode);
            if (cached != null) return cached;
        }

        try (Connection connection = connect()) {
            Set<String> productColumns = columns(connection);
            if (!productColumns.contains("product_id") || !productColumns.contains("barcode")) {
                throw new SQLException("В таблице products отсутствуют поля barcode или product_id.");
            }
            String sql = selectBasicColumns(productColumns) + " FROM products WHERE barcode = ? LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, normalizedBarcode);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? product(rows) : null;
                }
            }
        }
    }

    private String selectBasicColumns(Set<String> columns) {
        List<String> standard = List.of("product_id", "name", "vendor_code", "barcode", "uom", "price", "total_qty");
        return "SELECT " + standard.stream()
                .map(column -> (columns.contains(column) ? quote(column) : "NULL") + " AS \"" + column + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** Finds a local product by the canonical article used by price labels. */
    public Product findByMarking(String marking) throws SQLException {
        if (marking == null || marking.isBlank()) return null;
        CatalogCache cache = currentCache();
        if (cache != null) {
            Product cached = cache.byMarking().get(marking.trim());
            if (cached != null) return cached;
        }
        try (Connection connection = connect()) {
            Set<String> columns = columns(connection);
            if (!columns.contains("product_id")) {
                throw new SQLException("В таблице products отсутствует поле product_id.");
            }
            List<String> alternatives = new ArrayList<>();
            if (columns.contains("vendor_code")) {
                String uom = columns.contains("uom") ? "COALESCE(NULLIF(TRIM(uom), ''), 'ST')" : "'ST'";
                alternatives.add("(TRIM(COALESCE(vendor_code, '')) || '_' || " + uom + ") = ?");
                alternatives.add("(TRIM(COALESCE(vendor_code, '')) = '' AND CAST(product_id AS TEXT) = ?)");
            } else {
                alternatives.add("CAST(product_id AS TEXT) = ?");
            }
            String sql = selectColumns(connection, columns) + " FROM products WHERE ("
                    + String.join(" OR ", alternatives) + ") LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 1; index <= alternatives.size(); index++) statement.setString(index, marking.trim());
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? product(rows) : null;
                }
            }
        }
    }

    /** Category paths for sales articles, loaded in one catalog query. */
    public Map<String, String> categoryPaths(List<String> markings) throws SQLException {
        if (markings == null || markings.isEmpty()) return Map.of();
        try (Connection connection = connect()) {
            Set<String> columns = columns(connection);
            if (!columns.contains("product_id") || !hasTable(connection, "product_categories_path")) return Map.of();
            String identity = columns.contains("vendor_code")
                    ? "CASE WHEN TRIM(COALESCE(p.vendor_code, ''))<>'' THEN TRIM(p.vendor_code) || '_' || "
                    + (columns.contains("uom") ? "COALESCE(NULLIF(TRIM(p.uom), ''), 'ST')" : "'ST'")
                    + " ELSE CAST(p.product_id AS TEXT) END"
                    : "CAST(p.product_id AS TEXT)";
            String sql = "SELECT " + identity + " AS marking, "
                    + "group_concat(c.name, ' › ') AS category FROM products p "
                    + "LEFT JOIN (SELECT product_id, group_concat(name, ' › ') AS name FROM "
                    + "(SELECT product_id, name FROM product_categories_path ORDER BY position) GROUP BY product_id) c "
                    + "ON c.product_id=p.product_id WHERE " + identity + " IN (" + "?,".repeat(markings.size());
            sql = sql.substring(0, sql.length() - 1) + ") GROUP BY marking";
            Map<String, String> result = new java.util.LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < markings.size(); index++) statement.setString(index + 1, markings.get(index));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String category = rows.getString("category");
                        result.put(rows.getString("marking"), category == null || category.isBlank() ? "Без категории" : category);
                    }
                }
            }
            return result;
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

    private static boolean hasTable(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static CatalogCache currentCache() {
        CatalogCache cache = catalogCache;
        if (cache == null) return null;
        java.nio.file.Path path = AppConfig.DB_PATH.toAbsolutePath().normalize();
        if (!cache.path().equals(path) || cache.fileSize() != fileSize(path)
                || cache.lastModified() != lastModified(path)) return null;
        return cache;
    }

    private static long fileSize(java.nio.file.Path path) {
        try { return java.nio.file.Files.size(path); }
        catch (java.io.IOException ignored) { return -1; }
    }

    private static long lastModified(java.nio.file.Path path) {
        try { return java.nio.file.Files.getLastModifiedTime(path).toMillis(); }
        catch (java.io.IOException ignored) { return -1; }
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
                                 Long minimumPrice, Long maximumPrice, boolean advancedSearch) {
        public SearchCriteria(String query, String category, String attributeName, String attributeValue,
                              Long minimumPrice, Long maximumPrice) {
            this(query, category, attributeName, attributeValue, minimumPrice, maximumPrice, false);
        }

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

    public record StartupProgress(String message, int percent) { }
    public record StartupSummary(long catalogProducts, int cachedProducts, int indexesCreated) { }
    private record CatalogCache(java.nio.file.Path path, long fileSize, long lastModified,
                                Map<String, Product> byBarcode,
                                Map<String, Product> byMarking, int cachedProducts, long totalProducts) { }
}
