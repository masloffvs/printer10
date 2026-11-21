package com.setretailx.printer.ui;

import com.setretailx.printer.service.ParentApiClient;
import com.setretailx.printer.repository.ProductRepository;
import com.setretailx.printer.repository.SalesPointRepository;
import com.setretailx.printer.service.PriceMonitoringTaskService.SalesArticle;
import com.setretailx.printer.service.PriceMonitoringTaskService.SalesPosition;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JMenuItem;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Search and analyze receipt sales across all locally synchronized articles. */
@SuppressWarnings("serial")
final class SalesAnalyticsPanel extends JPanel {
    private static final NumberFormat NUMBER = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"));
    private static final NumberFormat MONEY = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("ru-RU"));
    private static final int POSITION_LIMIT = 20_000;

    private final ParentApiClient service;
    private final ProductRepository products;
    private final Consumer<SalesArticle> openProductCard;
    private final Consumer<SalesArticle> addToPrintQueue;
    private final JTextField query = new JTextField(24);
    private final JComboBox<Period> period = new JComboBox<>(Period.values());
    private final JComboBox<String> categoryFilter = new JComboBox<>(new String[]{"Все категории"});
    private final JComboBox<String> tableMode = new JComboBox<>(new String[]{"Товары", "Последние чеки"});
    private final DefaultTableModel articlesModel = new SalesArticleTableModel();
    private final DefaultTableModel recentModel = new TypedTableModel(
            new Object[]{"Дата продажи", "Чек", "Товар", "Кол-во", "Цена", "Сумма", "Скидка"},
            new Class<?>[]{java.util.Date.class, ReceiptNumber.class, String.class,
                    BigDecimal.class, BigDecimal.class, BigDecimal.class, BigDecimal.class});
    private final JTable articlesTable = new JTable(articlesModel);
    private final JScrollPane articleScroll = new JScrollPane(articlesTable);
    private final SalesPointRepository salesPoints = new SalesPointRepository();
    private final JLabel summary = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final AtomicLong requestId = new AtomicLong();
    private final AtomicLong recentRequestId = new AtomicLong();
    private List<SalesArticle> articles = List.of();
    private Map<String, String> categories = Map.of();
    private Map<String, List<String>> pointNames = Map.of();
    private boolean updatingCategoryFilter;

    SalesAnalyticsPanel(ParentApiClient service, ProductRepository products, Consumer<SalesArticle> openProductCard,
                        Consumer<SalesArticle> addToPrintQueue) {
        super(new BorderLayout(8, 8));
        this.service = service;
        this.products = products;
        this.openProductCard = openProductCard;
        this.addToPrintQueue = addToPrintQueue;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JButton search = new JButton("Найти");
        search.putClientProperty("JButton.buttonType", "default");
        search.addActionListener(event -> loadArticles());
        query.addActionListener(event -> loadArticles());
        period.addActionListener(event -> { loadArticles(); loadRecentSales(); });
        JPanel controls = new JPanel(new java.awt.GridLayout(0, 1, 0, 7));
        controls.add(new JLabel("Поиск товара / артикула / штрихкода"));
        controls.add(query);
        controls.add(new JLabel("Категория товара"));
        controls.add(categoryFilter);
        categoryFilter.addActionListener(event -> {
            if (!updatingCategoryFilter && articlesTable.getModel() == articlesModel) populateArticlesTable();
        });
        controls.add(new JLabel("Данные в таблице"));
        controls.add(tableMode);
        tableMode.addActionListener(event -> {
            boolean showArticles = tableMode.getSelectedIndex() == 0;
            articlesTable.setModel(showArticles ? articlesModel : recentModel);
            configureSalesSorters(articlesTable);
            articlesTable.setAutoCreateRowSorter(true);
            categoryFilter.setEnabled(showArticles);
            articleScroll.setBorder(BorderFactory.createTitledBorder(
                    showArticles ? "Товары с продажами" : "Последние чеки"));
            articlesTable.getParent().revalidate();
        });
        controls.add(new JLabel("Период продаж"));
        controls.add(period);
        controls.add(search);
        JButton refresh = new JButton("Обновить данные");
        refresh.addActionListener(event -> { loadArticles(); loadRecentSales(); });
        controls.add(refresh);
        JButton login = new JButton();
        updateLoginButton(login);
        login.addActionListener(event -> {
            if (service.isLoggedIn()) {
                try {
                    service.logout();
                    requestId.incrementAndGet();
                    recentRequestId.incrementAndGet();
                    updateLoginButton(login);
                    articles = List.of();
                    categories = Map.of();
                    updateCategoryFilter();
                    recentModel.setRowCount(0);
                    populateArticlesTable();
                    status.setText("Сеанс завершён.");
                } catch (Exception error) { showError(error); }
            } else if (showLoginDialog()) {
                updateLoginButton(login);
                loadArticles();
                loadRecentSales();
            }
        });
        controls.add(login);

        articlesTable.setFillsViewportHeight(true);
        articlesTable.setAutoCreateRowSorter(true);
        articlesTable.setDefaultRenderer(Object.class, new SalesCellRenderer());
        installArticleContextMenu();
        JPanel articlePanel = new JPanel(new BorderLayout());
        articleScroll.setBorder(BorderFactory.createTitledBorder("Товары с продажами"));
        articlePanel.add(articleScroll, BorderLayout.CENTER);
        JPanel filterPane = new JPanel(new BorderLayout(8, 8));
        filterPane.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, javax.swing.UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(10, 12, 10, 4)));
        filterPane.setPreferredSize(new java.awt.Dimension(245, 10));
        filterPane.add(controls, BorderLayout.NORTH);
        filterPane.add(summary, BorderLayout.SOUTH);
        JSplitPane content = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, articlePanel, filterPane);
        content.setResizeWeight(.76);
        content.setDividerLocation(850);
        add(content, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        if (service.isLoggedIn()) loadRecentSales();
        if (service.isLoggedIn()) loadArticles();
        else {
            summary.setText("Продажи доступны после входа с правом SALES_READ.");
            status.setText("Войдите в родительский аккаунт для просмотра продаж и чеков.");
        }
    }

    private static void configureSalesSorters(JTable table) {
        table.setDefaultRenderer(Object.class, new SalesCellRenderer());
        table.setDefaultRenderer(Number.class, new SalesCellRenderer());
        table.setDefaultRenderer(java.util.Date.class, new SalesCellRenderer());
        table.setDefaultRenderer(ReceiptNumber.class, new SalesCellRenderer());
        table.setDefaultRenderer(BigDecimal.class, new SalesCellRenderer());
    }

    private void updateLoginButton(JButton button) {
        button.setText(service.isLoggedIn() ? "Выйти" : "Войти");
        button.setToolTipText(service.isLoggedIn() ? "Завершить пользовательский сеанс" :
                "Войти в аккаунт родительского узла с правом чтения продаж");
    }

    private boolean showLoginDialog() {
        JTextField username = new JTextField(20);
        javax.swing.JPasswordField password = new javax.swing.JPasswordField(20);
        JPanel fields = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        fields.add(new JLabel("Имя пользователя"));
        fields.add(username);
        fields.add(new JLabel("Пароль"));
        fields.add(password);
        int choice = JOptionPane.showConfirmDialog(this, fields, "Вход в parent API",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            java.util.Arrays.fill(password.getPassword(), '\0');
            return false;
        }
        try {
            service.login(username.getText(), password.getPassword());
            status.setText("Вход выполнен.");
            return true;
        } catch (Exception error) {
            showError(error);
            return false;
        }
    }

    private void installArticleContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem openCard = new JMenuItem("Открыть карточку товара");
        JMenuItem queue = new JMenuItem("Добавить в очередь печати");
        menu.add(openCard);
        menu.add(queue);
        openCard.addActionListener(event -> {
            SalesArticle article = contextArticle();
            if (article != null) openProductCard.accept(article);
        });
        queue.addActionListener(event -> {
            SalesArticle article = contextArticle();
            if (article != null) addToPrintQueue.accept(article);
        });
        articlesTable.addMouseListener(new java.awt.event.MouseAdapter() {
            private void showMenu(java.awt.event.MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                int viewRow = articlesTable.rowAtPoint(event.getPoint());
                if (viewRow < 0) return;
                articlesTable.setRowSelectionInterval(viewRow, viewRow);
                menu.show(articlesTable, event.getX(), event.getY());
            }
            @Override public void mousePressed(java.awt.event.MouseEvent event) { showMenu(event); }
            @Override public void mouseReleased(java.awt.event.MouseEvent event) { showMenu(event); }
        });
    }

    private SalesArticle contextArticle() {
        if (articlesTable.getModel() != articlesModel) return null;
        int viewRow = articlesTable.getSelectedRow();
        if (viewRow < 0) return null;
        int row = articlesTable.convertRowIndexToModel(viewRow);
        if (row < 0 || row >= articlesModel.getRowCount()) return null;
        String marking = String.valueOf(articlesModel.getValueAt(row, 0));
        return articles.stream().filter(article -> article.marking().equals(marking)).findFirst().orElse(null);
    }

    private void loadArticles() {
        long id = requestId.incrementAndGet();
        double[] bounds = periodBounds();
        String search = query.getText();
        summary.setText("Ищем артикулы в загруженной истории чеков…");
        new SwingWorker<LoadedArticles, Void>() {
            @Override protected LoadedArticles doInBackground() throws Exception {
                List<SalesArticle> found = service.searchArticles(search, bounds[0] < 0 ? null : bounds[0],
                        bounds[1] < 0 ? null : bounds[1], 2000);
                Map<String, String> paths;
                try { paths = products.categoryPaths(found.stream().map(SalesArticle::marking).toList()); }
                catch (Exception ignored) { paths = Map.of(); }
                List<String> locationKeys = new ArrayList<>();
                for (SalesArticle article : found) {
                    locationKeys.add(article.marking());
                    if (!article.barcode().isBlank()) locationKeys.add(article.barcode());
                }
                Map<String, List<String>> locations;
                try { locations = salesPoints.locationNamesForProducts(locationKeys); }
                catch (Exception ignored) { locations = Map.of(); }
                return new LoadedArticles(found, paths, locations);
            }
            @Override protected void done() {
                if (id != requestId.get()) return;
                try {
                    LoadedArticles loaded = get();
                    articles = loaded.articles();
                    categories = loaded.categories();
                    pointNames = loaded.locations();
                    updateCategoryFilter();
                    populateArticlesTable();
                    summary.setText("В таблице показаны итоги локальной истории чеков и связанные торговые точки.");
                    status.setText(articles.isEmpty()
                            ? "Продажи не найдены. Проверьте запрос или успешность задачи «История продаж из чеков»."
                            : "Показано артикулов: " + articles.size() + (articles.size() == 2000 ? " (лимит выдачи)" : ""));
                } catch (Exception error) {
                    showError(error);
                }
            }
        }.execute();
    }

    private void populateArticlesTable() {
        articlesModel.setRowCount(0);
        for (SalesArticle article : articles) {
            if (!matchesCategoryFilter(article)) continue;
            List<String> locations = new ArrayList<>(pointNames.getOrDefault(normalizeKey(article.marking()), List.of()));
            locations.addAll(pointNames.getOrDefault(normalizeKey(article.barcode()), List.of()));
            articlesModel.addRow(new Object[]{article.marking(), article.barcode(),
                    article.name(), BigDecimal.valueOf(article.units()), BigDecimal.valueOf(article.revenue()),
                    article.checks(), String.join(", ", locations.stream().distinct().toList())});
        }
    }

    private void updateCategoryFilter() {
        String current = String.valueOf(categoryFilter.getSelectedItem());
        Set<String> paths = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        boolean uncategorized = false;
        for (SalesArticle article : articles) {
            String category = categories.get(article.marking());
            if (category == null || category.isBlank()) {
                uncategorized = true;
                continue;
            }
            StringBuilder path = new StringBuilder();
            for (String part : category.split("\\s*›\\s*")) {
                if (path.length() > 0) path.append(" › ");
                path.append(part);
                paths.add(path.toString());
            }
        }
        updatingCategoryFilter = true;
        categoryFilter.removeAllItems();
        categoryFilter.addItem("Все категории");
        paths.forEach(categoryFilter::addItem);
        if (uncategorized) categoryFilter.addItem("Без категории");
        if (paths.contains(current) || (uncategorized && "Без категории".equals(current)))
            categoryFilter.setSelectedItem(current);
        else categoryFilter.setSelectedIndex(0);
        updatingCategoryFilter = false;
    }

    private boolean matchesCategoryFilter(SalesArticle article) {
        String filter = (String) categoryFilter.getSelectedItem();
        if (filter == null || "Все категории".equals(filter)) return true;
        String category = categories.get(article.marking());
        if ("Без категории".equals(filter)) return category == null || category.isBlank();
        return category != null && (category.equalsIgnoreCase(filter)
                || category.regionMatches(true, 0, filter + " › ", 0, filter.length() + 3));
    }

    private void loadRecentSales() {
        long id = recentRequestId.incrementAndGet();
        double[] bounds = periodBounds();
        new SwingWorker<List<SalesPosition>, Void>() {
            @Override protected List<SalesPosition> doInBackground() throws Exception {
                return service.recentSalesPositions(bounds[0] < 0 ? null : bounds[0],
                        bounds[1] < 0 ? null : bounds[1], POSITION_LIMIT);
            }
            @Override protected void done() {
                if (id != recentRequestId.get()) return;
                try {
                    List<SalesPosition> rows = get();
                    recentModel.setRowCount(0);
                    for (SalesPosition position : rows) {
                        recentModel.addRow(new Object[]{new java.util.Date((long) (position.soldAt() * 1000)),
                                new ReceiptNumber(position.checkNumber()), position.name(),
                                BigDecimal.valueOf(position.quantity()), BigDecimal.valueOf(position.price()),
                                BigDecimal.valueOf(position.cost()), BigDecimal.valueOf(position.discount())});
                    }
                    status.setText("Последние продажи: " + rows.size()
                            + (rows.size() == POSITION_LIMIT ? " · достигнут лимит строк" : ""));
                } catch (Exception error) { showError(error); }
            }
        }.execute();
    }

    /** Bounds are epoch seconds; negative values represent an unbounded side. */
    private double[] periodBounds() {
        Period chosen = (Period) period.getSelectedItem();
        if (chosen == null || chosen.days == 0) return new double[]{-1, -1};
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        double from = today.minusDays(chosen.days - 1L).atStartOfDay(zone).toEpochSecond();
        double to = today.plusDays(1).atStartOfDay(zone).toEpochSecond();
        return new double[]{from, to};
    }

    private void showError(Exception error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        status.setText("Не удалось загрузить продажи: "
                + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
    }

    private record LoadedArticles(List<SalesArticle> articles, Map<String, String> categories,
                                  Map<String, List<String>> locations) { }
    private enum Period {
        WEEK("7 дней", 7), MONTH("30 дней", 30), QUARTER("90 дней", 90), ALL("Всё время", 0);
        private final String title;
        private final int days;
        Period(String title, int days) { this.title = title; this.days = days; }
        @Override public String toString() { return title; }
    }

    private static final class SalesArticleTableModel extends DefaultTableModel {
        SalesArticleTableModel() {
            super(new Object[]{"Артикул", "Штрихкод", "Название", "Продано", "Выручка", "Чеков", "Торговые точки"}, 0);
        }
        @Override public boolean isCellEditable(int row, int column) { return false; }
        @Override public Class<?> getColumnClass(int column) {
            return switch (column) {
                case 3, 4 -> BigDecimal.class;
                case 5 -> Integer.class;
                default -> String.class;
            };
        }
    }

    private static final class TypedTableModel extends DefaultTableModel {
        private final Class<?>[] columnTypes;
        TypedTableModel(Object[] columns, Class<?>[] columnTypes) {
            super(columns, 0);
            this.columnTypes = columnTypes.clone();
        }
        @Override public boolean isCellEditable(int row, int column) { return false; }
        @Override public Class<?> getColumnClass(int column) { return columnTypes[column]; }
    }

    private record ReceiptNumber(String value) implements Comparable<ReceiptNumber> {
        ReceiptNumber { value = value == null ? "" : value; }
        @Override public int compareTo(ReceiptNumber other) {
            BigDecimal left = numericValue(value);
            BigDecimal right = numericValue(other.value);
            if (left != null && right != null) return left.compareTo(right);
            if (left != null) return -1;
            if (right != null) return 1;
            return value.compareToIgnoreCase(other.value);
        }
        private static BigDecimal numericValue(String value) {
            try { return new BigDecimal(value.trim().replace(',', '.')); }
            catch (NumberFormatException ignored) { return null; }
        }
        @Override public String toString() { return value; }
    }

    private static final class SalesCellRenderer extends javax.swing.table.DefaultTableCellRenderer {
        @Override public java.awt.Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            if (value instanceof java.util.Date date) {
                String pattern = table.getColumnName(column).equals("День") ? "dd.MM.yyyy" : "dd.MM.yyyy HH:mm:ss";
                setText(new java.text.SimpleDateFormat(pattern, Locale.forLanguageTag("ru-RU")).format(date));
            } else if (value instanceof BigDecimal decimal) {
                String columnName = table.getColumnName(column);
                setText(java.util.Set.of("Выручка", "Цена", "Сумма", "Скидка").contains(columnName)
                        ? MONEY.format(decimal) : formatQuantity(decimal.doubleValue()));
            } else if (value instanceof ReceiptNumber receipt) setText(receipt.value());
            else setText(value == null ? "" : value.toString());
            setHorizontalAlignment(value instanceof Number || value instanceof BigDecimal
                    ? javax.swing.SwingConstants.RIGHT : javax.swing.SwingConstants.LEFT);
            return this;
        }
    }

    private static String formatQuantity(double value) {
        NUMBER.setMaximumFractionDigits(3);
        return NUMBER.format(value);
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }
}
