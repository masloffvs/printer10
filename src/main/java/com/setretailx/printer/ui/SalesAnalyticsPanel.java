package com.setretailx.printer.ui;

import com.setretailx.printer.service.ParentApiClient;
import com.setretailx.printer.repository.ProductRepository;
import com.setretailx.printer.service.PriceMonitoringTaskService.SalesArticle;
import com.setretailx.printer.service.PriceMonitoringTaskService.SalesDay;
import com.setretailx.printer.service.PriceMonitoringTaskService.SalesPosition;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JMenuItem;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
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
    private final Consumer<List<SalesDay>> openChart;
    private final JTextField query = new JTextField(24);
    private final JComboBox<Period> period = new JComboBox<>(Period.values());
    private final JCheckBox treeView = new JCheckBox("Дерево категорий");
    private final DefaultTableModel articlesModel = new SalesArticleTableModel();
    private final DefaultTableModel daysModel = new TypedTableModel(
            new Object[]{"День", "Продано", "Выручка", "Чеков"},
            new Class<?>[]{java.util.Date.class, BigDecimal.class, BigDecimal.class, Integer.class});
    private final DefaultTableModel positionsModel = new TypedTableModel(
            new Object[]{"Дата", "Чек", "Артикул", "Товар", "Кол-во", "Цена", "Сумма", "Скидка"},
            new Class<?>[]{java.util.Date.class, ReceiptNumber.class, String.class, String.class,
                    BigDecimal.class, BigDecimal.class, BigDecimal.class, BigDecimal.class});
    private final DefaultTableModel recentModel = new TypedTableModel(
            new Object[]{"Дата продажи", "Чек", "Артикул", "Товар", "Кол-во", "Цена", "Сумма", "Скидка"},
            new Class<?>[]{java.util.Date.class, ReceiptNumber.class, String.class, String.class,
                    BigDecimal.class, BigDecimal.class, BigDecimal.class, BigDecimal.class});
    private final JTable articlesTable = new JTable(articlesModel);
    private final DefaultMutableTreeNode articlesRoot = new DefaultMutableTreeNode("Артикулы");
    private final DefaultTreeModel articlesTreeModel = new DefaultTreeModel(articlesRoot);
    private final JTree articlesTree = new JTree(articlesTreeModel);
    private final JPanel articleViews = new JPanel(new java.awt.CardLayout());
    private final JLabel summary = new JLabel("Выберите артикулы, чтобы увидеть общие продажи.");
    private final JLabel status = new JLabel(" ");
    private final AtomicLong requestId = new AtomicLong();
    private final AtomicLong recentRequestId = new AtomicLong();
    private List<SalesArticle> articles = List.of();
    private List<SalesDay> days = List.of();
    private Map<String, String> categories = Map.of();
    private Set<String> selected = new LinkedHashSet<>();
    private boolean updatingTable;

    SalesAnalyticsPanel(ParentApiClient service, ProductRepository products, Consumer<SalesArticle> openProductCard,
                        Consumer<SalesArticle> addToPrintQueue, Consumer<List<SalesDay>> openChart) {
        super(new BorderLayout(8, 8));
        this.service = service;
        this.products = products;
        this.openProductCard = openProductCard;
        this.addToPrintQueue = addToPrintQueue;
        this.openChart = openChart;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JButton search = new JButton("Найти");
        search.addActionListener(event -> loadArticles());
        query.addActionListener(event -> loadArticles());
        period.addActionListener(event -> loadArticles());
        period.addActionListener(event -> loadRecentSales());
        treeView.addActionListener(event -> {
            ((java.awt.CardLayout) articleViews.getLayout()).show(articleViews,
                    treeView.isSelected() ? "tree" : "table");
            syncTreeSelection();
        });
        JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2));
        controls.add(new JLabel("Поиск товара / артикула / штрихкода:"));
        controls.add(query);
        controls.add(search);
        controls.add(new JLabel("Период:"));
        controls.add(period);
        controls.add(treeView);
        JButton chartButton = new JButton("Открыть графики…");
        chartButton.addActionListener(event -> openChart.accept(days));
        controls.add(chartButton);
        JButton refresh = new JButton("Обновить");
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
                    recentModel.setRowCount(0);
                    days = List.of();
                    daysModel.setRowCount(0);
                    positionsModel.setRowCount(0);
                    selected.clear();
                    populateArticlesTable();
                    loadAnalytics();
                    status.setText("Сеанс завершён.");
                } catch (Exception error) { showError(error); }
            } else if (showLoginDialog()) {
                updateLoginButton(login);
                loadArticles();
                loadRecentSales();
            }
        });
        controls.add(login);
        JPanel header = new JPanel(new BorderLayout(4, 4));
        header.add(controls, BorderLayout.NORTH);
        header.add(summary, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        articlesTable.setFillsViewportHeight(true);
        articlesTable.setAutoCreateRowSorter(true);
        articlesTable.getColumnModel().getColumn(0).setMaxWidth(34);
        articlesTable.setDefaultRenderer(Object.class, new SalesCellRenderer());
        installArticleContextMenu();
        articlesTable.getModel().addTableModelListener(event -> {
            if (updatingTable || event.getType() != javax.swing.event.TableModelEvent.UPDATE
                    || event.getColumn() != 0 || event.getFirstRow() < 0) return;
            // TableModelEvent indices are already model indices (unlike mouse/selection events).
            int row = event.getFirstRow();
            if (row >= articlesModel.getRowCount()) return;
            String marking = String.valueOf(articlesModel.getValueAt(row, 1));
            if (Boolean.TRUE.equals(articlesModel.getValueAt(row, 0))) selected.add(marking);
            else selected.remove(marking);
            syncTreeSelection();
            loadAnalytics();
        });
        JScrollPane articleScroll = new JScrollPane(articlesTable);
        articleScroll.setBorder(BorderFactory.createTitledBorder("Артикулы с продажами"));
        articlesTree.setRootVisible(false);
        articlesTree.setShowsRootHandles(true);
        articlesTree.setCellRenderer(new SalesTreeRenderer());
        articlesTree.getSelectionModel().setSelectionMode(
                javax.swing.tree.TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        articlesTree.addTreeSelectionListener(event -> {
            if (updatingTable) return;
            Set<String> visible = new LinkedHashSet<>();
            for (SalesArticle article : articles) visible.add(article.marking());
            selected.removeAll(visible);
            TreePath[] paths = articlesTree.getSelectionPaths();
            if (paths != null) for (TreePath path : paths)
                collectTreeArticles((DefaultMutableTreeNode) path.getLastPathComponent(), selected);
            syncArticleTableSelection();
            loadAnalytics();
        });
        installTreeContextMenu();
        JScrollPane treeScroll = new JScrollPane(articlesTree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("Артикулы по категориям · выберите товары или разделы"));
        articleViews.add(articleScroll, "table");
        articleViews.add(treeScroll, "tree");
        JButton selectVisible = new JButton("Выбрать всё"), clearSelection = new JButton("Снять выбор");
        selectVisible.addActionListener(event -> setVisibleSelected(true));
        clearSelection.addActionListener(event -> {
            selected.clear();
            syncSelection();
            loadAnalytics();
        });
        JPanel articleControls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        articleControls.add(selectVisible);
        articleControls.add(clearSelection);
        JPanel articlePanel = new JPanel(new BorderLayout());
        articlePanel.add(articleViews, BorderLayout.CENTER);
        articlePanel.add(articleControls, BorderLayout.SOUTH);

        JTable daysTable = new JTable(daysModel);
        daysTable.setFillsViewportHeight(true);
        daysTable.setAutoCreateRowSorter(true);
        configureSalesSorters(daysTable);
        JTable positionsTable = new JTable(positionsModel);
        positionsTable.setFillsViewportHeight(true);
        positionsTable.setAutoCreateRowSorter(true);
        configureSalesSorters(positionsTable);
        JTable recentTable = new JTable(recentModel);
        recentTable.setFillsViewportHeight(true);
        recentTable.setAutoCreateRowSorter(true);
        configureSalesSorters(recentTable);
        JScrollPane recentScroll = new JScrollPane(recentTable);
        recentScroll.setBorder(BorderFactory.createTitledBorder("Последние продажи · от новых к старым"));
        JScrollPane positionsScroll = new JScrollPane(positionsTable);
        positionsScroll.setBorder(BorderFactory.createTitledBorder("Строки чеков по выбранным артикулам"));
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(daysTable), positionsScroll);
        right.setResizeWeight(.36);
        JTabbedPane salesTabs = new JTabbedPane();
        salesTabs.addTab("Последние чеки", recentScroll);
        salesTabs.addTab("Аналитика", right);
        JSplitPane content = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, articlePanel, salesTabs);
        content.setResizeWeight(.36);
        content.setDividerLocation(410);
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
        JMenuItem showStats = new JMenuItem("Показать статистику продаж");
        JMenuItem openCard = new JMenuItem("Открыть карточку товара");
        JMenuItem queue = new JMenuItem("Добавить в очередь печати");
        menu.add(showStats);
        menu.add(openCard);
        menu.add(queue);
        showStats.addActionListener(event -> {
            SalesArticle article = contextArticle();
            if (article == null) return;
            selected.clear();
            selected.add(article.marking());
            syncSelection();
            loadAnalytics();
        });
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
        int viewRow = articlesTable.getSelectedRow();
        if (viewRow < 0) return null;
        int row = articlesTable.convertRowIndexToModel(viewRow);
        if (row < 0 || row >= articlesModel.getRowCount()) return null;
        String marking = String.valueOf(articlesModel.getValueAt(row, 1));
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
                return new LoadedArticles(found, paths);
            }
            @Override protected void done() {
                if (id != requestId.get()) return;
                try {
                    LoadedArticles loaded = get();
                    articles = loaded.articles();
                    categories = loaded.categories();
                    populateArticlesTable();
                    summary.setText(articles.size() + " артикулов в выдаче · отмечено: " + selected.size()
                            + " (выбор сохраняется между поисками) · данные синхронизируются в разделе «Задачи».");
                    status.setText(articles.isEmpty()
                            ? "Продажи не найдены. Проверьте запрос или успешность задачи «История продаж из чеков»."
                            : "Показано артикулов: " + articles.size() + (articles.size() == 2000 ? " (лимит выдачи)" : ""));
                    loadAnalytics();
                } catch (Exception error) {
                    updatingTable = false;
                    showError(error);
                }
            }
        }.execute();
    }

    private void loadAnalytics() {
        long id = requestId.incrementAndGet();
        List<String> markings = List.copyOf(selected);
        double[] bounds = periodBounds();
        if (markings.isEmpty()) {
            days = List.of();
            daysModel.setRowCount(0);
            positionsModel.setRowCount(0);
            summary.setText(articles.size() + " артикулов в выборке · отметьте артикулы для общей аналитики.");
            return;
        }
        status.setText("Считаем продажи для " + markings.size() + " артикулов…");
        new SwingWorker<LoadedSales, Void>() {
            @Override protected LoadedSales doInBackground() throws Exception {
                Double from = bounds[0] < 0 ? null : bounds[0];
                Double to = bounds[1] < 0 ? null : bounds[1];
                return new LoadedSales(service.salesHistory(markings, from, to),
                        service.salesPositions(markings, from, to, POSITION_LIMIT));
            }
            @Override protected void done() {
                if (id != requestId.get()) return;
                try {
                    LoadedSales loaded = get();
                    days = loaded.days();
                    daysModel.setRowCount(0);
                    double units = 0, revenue = 0;
                    int checks = 0;
                    for (SalesDay day : days) {
                        daysModel.addRow(new Object[]{new java.util.Date(day.dayEpochSeconds() * 1000),
                                BigDecimal.valueOf(day.units()), BigDecimal.valueOf(day.revenue()), day.checks()});
                        units += day.units(); revenue += day.revenue(); checks += day.checks();
                    }
                    positionsModel.setRowCount(0);
                    for (SalesPosition position : loaded.positions()) {
                        positionsModel.addRow(new Object[]{new java.util.Date((long) (position.soldAt() * 1000)),
                                new ReceiptNumber(position.checkNumber()), position.item(), position.name(),
                                BigDecimal.valueOf(position.quantity()), BigDecimal.valueOf(position.price()),
                                BigDecimal.valueOf(position.cost()), BigDecimal.valueOf(position.discount())});
                    }
                    summary.setText("Выбрано артикулов: " + markings.size() + " · продано: " + quantity(units)
                            + " · выручка: " + MONEY.format(revenue) + " · чеков по дням: " + checks
                            + " · дней с продажами: " + days.size());
                    status.setText("Позиции чеков: " + loaded.positions().size()
                            + (loaded.positions().size() == POSITION_LIMIT ? " (показан лимит последних " + POSITION_LIMIT + ")" : ""));
                } catch (Exception error) { showError(error); }
            }
        }.execute();
    }

    private void populateArticlesTable() {
        updatingTable = true;
        articlesModel.setRowCount(0);
        for (SalesArticle article : articles) {
            articlesModel.addRow(new Object[]{selected.contains(article.marking()), article.marking(), article.name(),
                    article.barcode(), BigDecimal.valueOf(article.units()), BigDecimal.valueOf(article.revenue()),
                    article.checks()});
        }
        populateArticlesTree();
        updatingTable = false;
    }

    private void populateArticlesTree() {
        articlesRoot.removeAllChildren();
        Map<String, DefaultMutableTreeNode> categoryNodes = new HashMap<>();
        List<SalesArticle> sorted = new ArrayList<>(articles);
        sorted.sort(java.util.Comparator.comparing(article ->
                categories.getOrDefault(article.marking(), "Без категории"), String.CASE_INSENSITIVE_ORDER));
        for (SalesArticle article : sorted) {
            DefaultMutableTreeNode parent = articlesRoot;
            String category = categories.getOrDefault(article.marking(), "Без категории");
            StringBuilder path = new StringBuilder();
            for (String part : category.split("\\s*›\\s*")) {
                if (path.length() > 0) path.append(" › ");
                path.append(part);
                String key = path.toString();
                DefaultMutableTreeNode categoryNode = categoryNodes.get(key);
                if (categoryNode == null) {
                    categoryNode = new DefaultMutableTreeNode(new CategoryNode(part));
                    categoryNodes.put(key, categoryNode);
                    parent.add(categoryNode);
                }
                parent = categoryNode;
            }
            parent.add(new DefaultMutableTreeNode(article));
        }
        articlesTreeModel.reload();
        articlesTree.expandPath(new TreePath(articlesRoot.getPath()));
        for (int row = 0; row < articlesTree.getRowCount(); row++) articlesTree.expandRow(row);
        syncTreeSelection();
    }

    private static void collectTreeArticles(DefaultMutableTreeNode node, Set<String> markings) {
        Object value = node.getUserObject();
        if (value instanceof SalesArticle article) {
            markings.add(article.marking());
            return;
        }
        Enumeration<?> children = node.children();
        while (children.hasMoreElements())
            collectTreeArticles((DefaultMutableTreeNode) children.nextElement(), markings);
    }

    private void syncTreeSelection() {
        List<TreePath> paths = new ArrayList<>();
        collectSelectedTreePaths(articlesRoot, paths);
        boolean wasUpdating = updatingTable;
        updatingTable = true;
        articlesTree.setSelectionPaths(paths.toArray(TreePath[]::new));
        updatingTable = wasUpdating;
    }

    private void collectSelectedTreePaths(DefaultMutableTreeNode node, List<TreePath> paths) {
        if (node.getUserObject() instanceof SalesArticle article && selected.contains(article.marking()))
            paths.add(new TreePath(node.getPath()));
        Enumeration<?> children = node.children();
        while (children.hasMoreElements())
            collectSelectedTreePaths((DefaultMutableTreeNode) children.nextElement(), paths);
    }

    private void installTreeContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem showStats = new JMenuItem("Показать статистику продаж");
        JMenuItem openCard = new JMenuItem("Открыть карточку товара");
        JMenuItem queue = new JMenuItem("Добавить в очередь печати");
        menu.add(showStats);
        menu.add(openCard);
        menu.add(queue);
        showStats.addActionListener(event -> {
            SalesArticle article = treeContextArticle();
            if (article == null) return;
            selected.clear();
            selected.add(article.marking());
            syncSelection();
            loadAnalytics();
        });
        openCard.addActionListener(event -> {
            SalesArticle article = treeContextArticle();
            if (article != null) openProductCard.accept(article);
        });
        queue.addActionListener(event -> {
            SalesArticle article = treeContextArticle();
            if (article != null) addToPrintQueue.accept(article);
        });
        articlesTree.addMouseListener(new java.awt.event.MouseAdapter() {
            private void showMenu(java.awt.event.MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                TreePath path = articlesTree.getPathForLocation(event.getX(), event.getY());
                if (path == null) return;
                Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                if (!(value instanceof SalesArticle)) return;
                articlesTree.setSelectionPath(path);
                menu.show(articlesTree, event.getX(), event.getY());
            }
            @Override public void mousePressed(java.awt.event.MouseEvent event) { showMenu(event); }
            @Override public void mouseReleased(java.awt.event.MouseEvent event) { showMenu(event); }
        });
    }

    private SalesArticle treeContextArticle() {
        TreePath path = articlesTree.getSelectionPath();
        if (path == null) return null;
        Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return value instanceof SalesArticle article ? article : null;
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
                                new ReceiptNumber(position.checkNumber()), position.item(), position.name(),
                                BigDecimal.valueOf(position.quantity()), BigDecimal.valueOf(position.price()),
                                BigDecimal.valueOf(position.cost()), BigDecimal.valueOf(position.discount())});
                    }
                    status.setText("Последние продажи: " + rows.size()
                            + (rows.size() == POSITION_LIMIT ? " · достигнут лимит строк" : ""));
                } catch (Exception error) { showError(error); }
            }
        }.execute();
    }

    private void setVisibleSelected(boolean value) {
        if (value) for (SalesArticle article : articles) selected.add(article.marking());
        else selected.clear();
        syncSelection();
        loadAnalytics();
    }

    private void syncSelection() {
        updatingTable = true;
        syncArticleTableSelection();
        syncTreeSelection();
        updatingTable = false;
    }

    private void syncArticleTableSelection() {
        boolean wasUpdating = updatingTable;
        updatingTable = true;
        for (int row = 0; row < articlesModel.getRowCount(); row++)
            articlesModel.setValueAt(selected.contains(String.valueOf(articlesModel.getValueAt(row, 1))), row, 0);
        updatingTable = wasUpdating;
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

    private static String quantity(double value) {
        NUMBER.setMaximumFractionDigits(3);
        return NUMBER.format(value);
    }

    private record LoadedSales(List<SalesDay> days, List<SalesPosition> positions) { }
    private record LoadedArticles(List<SalesArticle> articles, Map<String, String> categories) { }
    private enum Period {
        WEEK("7 дней", 7), MONTH("30 дней", 30), QUARTER("90 дней", 90), ALL("Всё время", 0);
        private final String title;
        private final int days;
        Period(String title, int days) { this.title = title; this.days = days; }
        @Override public String toString() { return title; }
    }

    private static final class TypedTableModel extends DefaultTableModel {
        private final Class<?>[] columnTypes;
        TypedTableModel(Object[] columns, Class<?>[] columnTypes) {
            super(columns, 0);
            this.columnTypes = columnTypes.clone();
        }
        @Override public boolean isCellEditable(int row, int column) { return false; }
        @Override public Class<?> getColumnClass(int column) {
            return columnTypes[column];
        }
    }

    private static final class SalesArticleTableModel extends DefaultTableModel {
        SalesArticleTableModel() {
            super(new Object[]{"✓", "Артикул", "Название", "Штрихкод", "Продано", "Выручка", "Чеков"}, 0);
        }
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }
        @Override public Class<?> getColumnClass(int column) {
            return switch (column) {
                case 0 -> Boolean.class;
                case 4, 5 -> BigDecimal.class;
                case 6 -> Integer.class;
                default -> String.class;
            };
        }
    }

    private record CategoryNode(String name) {
        @Override public String toString() { return name; }
    }

    private static final class SalesTreeRenderer extends javax.swing.tree.DefaultTreeCellRenderer {
        @Override public java.awt.Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean selected, boolean expanded, boolean leaf, int row, boolean focused) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focused);
            Object user = ((DefaultMutableTreeNode) value).getUserObject();
            if (user instanceof SalesArticle article)
                setText(article.name() + "  ·  " + article.marking());
            else if (user instanceof CategoryNode category) setText(category.name());
            return this;
        }
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
                        ? MONEY.format(decimal) : quantity(decimal.doubleValue()));
            } else if (value instanceof ReceiptNumber receipt) setText(receipt.value());
            else setText(value == null ? "" : value.toString());
            setHorizontalAlignment(value instanceof Number || value instanceof BigDecimal
                    ? javax.swing.SwingConstants.RIGHT : javax.swing.SwingConstants.LEFT);
            return this;
        }
    }
}
