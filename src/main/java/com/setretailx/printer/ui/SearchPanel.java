package com.setretailx.printer.ui;

import com.setretailx.printer.model.Product;
import com.setretailx.printer.repository.ProductRepository.SearchOptions;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.Scrollable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local catalog search with results on the left and explicit filters on the right. */
@SuppressWarnings("serial")
final class SearchPanel extends JPanel {
    private static final int PRICE_SLIDER_STEPS = 1000;

    private final JTextField input = new JTextField();
    private final JCheckBox advancedSearch = new JCheckBox("Расширенный поиск");
    private final JButton category = new JButton("Выбрать категорию…");
    private final JLabel selectedCategory = new JLabel("Все категории");
    private final JComboBox<String> attributeName = new CompactComboBox();
    private final JComboBox<String> attributeValue = new CompactComboBox();
    private final JSlider minimumPrice = new JSlider();
    private final JSlider maximumPrice = new JSlider();
    private final JLabel minimumPriceLabel = new JLabel("Без ограничения");
    private final JLabel maximumPriceLabel = new JLabel("Без ограничения");
    private final JLabel status = new JLabel("Задайте запрос или фильтры и выполните поиск.");
    private final JTextField resultSearchInput = new JTextField(22);
    private final JLabel resultSearchCount = new JLabel(" ");
    private final JPanel resultSearchBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final DefaultTableModel model = readOnlyModel("Товар", "Артикул", "Штрихкод", "Цена", "Остаток");
    private final JTable table = new JTable(model);
    private final JPanel filters = new FilterPanel();
    private final JButton find = new JButton("Найти товары");
    private final JButton clear = new JButton("Сбросить");
    private final Consumer<JPanel> openCategoryPicker;
    private List<Product> results = List.of();
    private List<Product> unrankedResults = List.of();
    private List<String> categories = List.of();
    private String categoryPath = "";
    private Map<String, List<String>> attributeOptions = Map.of();
    private long priceMinimum;
    private long priceMaximum;
    private boolean updatingControls;

    SearchPanel(Runnable search, Runnable selectionChanged, Runnable addSelected, Consumer<Product> openProduct,
                Consumer<JPanel> openCategoryPicker) {
        super(new BorderLayout(12, 10));
        this.openCategoryPicker = openCategoryPicker;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel resultPane = new JPanel(new BorderLayout(0, 8));
        JLabel resultsHeading = new JLabel("Результаты поиска");
        resultsHeading.setFont(resultsHeading.getFont().deriveFont(java.awt.Font.BOLD, 15f));
        resultPane.add(resultsHeading, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        table.setRowHeight(25);
        table.setDefaultRenderer(Object.class, new HighlightRenderer());
        installResultSearch();
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) selectionChanged.run();
        });
        addContextMenu(addSelected, openProduct);
        JPanel tableArea = new JPanel(new BorderLayout(0, 6));
        resultSearchBar.setOpaque(false);
        JLabel resultSearchLabel = new JLabel("Найти в результатах:");
        JButton closeResultSearch = new JButton("Закрыть");
        closeResultSearch.setFocusable(false);
        closeResultSearch.addActionListener(event -> closeResultSearch());
        resultSearchBar.add(resultSearchLabel);
        resultSearchBar.add(resultSearchInput);
        resultSearchBar.add(resultSearchCount);
        resultSearchBar.add(closeResultSearch);
        resultSearchBar.setVisible(false);
        tableArea.add(resultSearchBar, BorderLayout.NORTH);
        tableArea.add(new JScrollPane(table), BorderLayout.CENTER);
        resultPane.add(tableArea, BorderLayout.CENTER);
        resultPane.add(status, BorderLayout.SOUTH);
        add(resultPane, BorderLayout.CENTER);

        JPanel filterPane = new JPanel(new BorderLayout(0, 10));
        filterPane.setPreferredSize(new Dimension(280, 10));
        filterPane.setMinimumSize(new Dimension(250, 10));
        filterPane.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, javax.swing.UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(8, 14, 8, 2)));
        JLabel filterHeading = new JLabel("Фильтры");
        filterHeading.setFont(filterHeading.getFont().deriveFont(java.awt.Font.BOLD, 15f));
        filterPane.add(filterHeading, BorderLayout.NORTH);
        buildFilters();
        JScrollPane filterScroll = new JScrollPane(filters);
        filterScroll.setBorder(null);
        filterScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        filterScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        filterScroll.getVerticalScrollBar().setUnitIncrement(14);
        filterPane.add(filterScroll, BorderLayout.CENTER);

        JPanel actions = new JPanel(new java.awt.GridLayout(0, 1, 0, 7));
        find.setToolTipText("Выполнить предварительный поиск по выбранным условиям");
        find.addActionListener(event -> search.run());
        input.addActionListener(event -> search.run());
        clear.addActionListener(event -> resetFilters(search));
        actions.add(find);
        actions.add(clear);
        filterPane.add(actions, BorderLayout.SOUTH);
        add(filterPane, BorderLayout.EAST);
    }

    private void buildFilters() {
        filters.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 10, 0);

        addSectionLabel("Поиск по тексту", c, 0);
        input.setToolTipText("Название, артикул или штрихкод");
        input.setPreferredSize(new Dimension(240, input.getPreferredSize().height));
        filters.add(input, at(c, 1));
        advancedSearch.setToolTipText("Ищет каждое слово отдельно и учитывает варианты размеров: 1/2, 1x2, 1х2");
        filters.add(advancedSearch, at(c, 2));

        addSectionLabel("Категория товара", c, 3);
        category.addActionListener(event -> chooseCategory());
        JPanel categoryChoice = new JPanel(new BorderLayout(0, 5));
        categoryChoice.setOpaque(false);
        selectedCategory.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        selectedCategory.setPreferredSize(new Dimension(240, selectedCategory.getPreferredSize().height));
        categoryChoice.add(category, BorderLayout.NORTH);
        categoryChoice.add(selectedCategory, BorderLayout.CENTER);
        filters.add(categoryChoice, at(c, 4));

        addSectionLabel("Цена от", c, 5);
        minimumPrice.setFocusable(false);
        minimumPrice.setPaintTicks(true);
        minimumPrice.setMajorTickSpacing(250);
        filters.add(minimumPrice, at(c, 6));
        filters.add(minimumPriceLabel, at(c, 7));

        addSectionLabel("Цена до", c, 8);
        maximumPrice.setFocusable(false);
        maximumPrice.setPaintTicks(true);
        maximumPrice.setMajorTickSpacing(250);
        filters.add(maximumPrice, at(c, 9));
        filters.add(maximumPriceLabel, at(c, 10));

        addSectionLabel("Характеристика", c, 11);
        attributeName.addItem("Не выбрана");
        attributeName.addActionListener(event -> updateAttributeValues());
        filters.add(attributeName, at(c, 12));

        addSectionLabel("Значение", c, 13);
        attributeValue.addItem("Любое значение");
        filters.add(attributeValue, at(c, 14));

        minimumPrice.addChangeListener(event -> {
            if (minimumPrice.getValue() > maximumPrice.getValue()) maximumPrice.setValue(minimumPrice.getValue());
            updatePriceLabels();
        });
        maximumPrice.addChangeListener(event -> {
            if (maximumPrice.getValue() < minimumPrice.getValue()) minimumPrice.setValue(maximumPrice.getValue());
            updatePriceLabels();
        });
        c.gridy = 15;
        c.weighty = 1;
        filters.add(new JPanel(), c);
    }

    private void addSectionLabel(String text, GridBagConstraints c, int row) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD));
        filters.add(label, at(c, row));
    }

    private GridBagConstraints at(GridBagConstraints source, int row) {
        GridBagConstraints c = (GridBagConstraints) source.clone();
        c.gridy = row;
        return c;
    }

    private void addContextMenu(Runnable addSelected, Consumer<Product> openProduct) {
        JPopupMenu contextMenu = new JPopupMenu();
        JMenuItem addToQueue = new JMenuItem("Добавить выбранные в очередь печати");
        addToQueue.addActionListener(event -> addSelected.run());
        contextMenu.add(addToQueue);
        int[] popupRow = {-1};
        JMenuItem openCard = new JMenuItem("Открыть карточку товара");
        openCard.addActionListener(event -> {
            int row = popupRow[0] >= 0 ? popupRow[0] : table.getSelectedRow();
            Product product = row < 0 ? null : productAt(row);
            if (product != null) openProduct.accept(product);
        });
        contextMenu.add(openCard);
        table.addMouseListener(new MouseAdapter() {
            private void showMenu(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                int row = table.rowAtPoint(event.getPoint());
                if (row < 0) return;
                if (!table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
                popupRow[0] = row;
                contextMenu.show(table, event.getX(), event.getY());
            }
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() != 2 || !javax.swing.SwingUtilities.isLeftMouseButton(event)) return;
                int row = table.rowAtPoint(event.getPoint());
                if (row >= 0 && row < model.getRowCount()) openProduct.accept(productAt(row));
            }
            @Override public void mousePressed(MouseEvent event) { showMenu(event); }
            @Override public void mouseReleased(MouseEvent event) { showMenu(event); }
        });
    }

    private void resetFilters(Runnable search) {
        updatingControls = true;
        try {
            input.setText("");
            categoryPath = "";
            selectedCategory.setText("Все категории");
            selectedCategory.setToolTipText(null);
            attributeName.setSelectedIndex(0);
            minimumPrice.setValue(0);
            maximumPrice.setValue(PRICE_SLIDER_STEPS);
        } finally {
            updatingControls = false;
        }
        updateAttributeValues();
        updatePriceLabels();
        search.run();
    }

    private void updateAttributeValues() {
        String selected = (String) attributeName.getSelectedItem();
        List<String> values = attributeOptions.getOrDefault(selected, List.of());
        attributeValue.removeAllItems();
        attributeValue.addItem("Любое значение");
        for (String value : values) attributeValue.addItem(value);
    }

    private void updatePriceLabels() {
        if (updatingControls) return;
        long min = priceAt(minimumPrice.getValue());
        long max = priceAt(maximumPrice.getValue());
        minimumPriceLabel.setText(minimumPrice.getValue() == 0 ? "Без минимума" : money(min));
        maximumPriceLabel.setText(maximumPrice.getValue() == maximumPrice.getMaximum() ? "Без максимума" : money(max));
    }

    private static String money(long kopecks) {
        return new java.text.DecimalFormat("#,##0.00 ₽").format(kopecks / 100.0);
    }

    private long priceAt(int sliderValue) {
        return priceMinimum + Math.round((priceMaximum - priceMinimum)
                * (sliderValue / (double) PRICE_SLIDER_STEPS));
    }

    String query() { return input.getText().trim(); }
    boolean advancedSearch() { return advancedSearch.isSelected(); }
    void setQuery(String query) { input.setText(query); }
    int[] selectedRows() {
        return java.util.Arrays.stream(table.getSelectedRows())
                .map(table::convertRowIndexToModel).toArray();
    }
    int selectedCount() { return table.getSelectedRowCount(); }
    DefaultTableModel model() { return model; }
    void setStatus(String message) { status.setText(message); }
    void setSearchEnabled(boolean enabled) { find.setEnabled(enabled); }

    String categoryFilter() { return categoryPath; }
    String attributeNameFilter() { return selected(attributeName, "Не выбрана"); }
    String attributeValueFilter() { return selected(attributeValue, "Любое значение"); }

    Long minimumPriceFilter() {
        return minimumPrice.getValue() == 0 ? null
                : priceAt(minimumPrice.getValue());
    }

    Long maximumPriceFilter() {
        return maximumPrice.getValue() == PRICE_SLIDER_STEPS ? null
                : priceAt(maximumPrice.getValue());
    }

    void setSearchOptions(SearchOptions options) {
        updatingControls = true;
        try {
            categories = options.categories();
            if (!categoryPath.isBlank() && !categories.contains(categoryPath)) {
                categoryPath = "";
                selectedCategory.setText("Все категории");
                selectedCategory.setToolTipText(null);
            }
            attributeOptions = options.attributes();
            attributeName.removeAllItems();
            attributeName.addItem("Не выбрана");
            attributeOptions.keySet().forEach(attributeName::addItem);
            priceMinimum = Math.max(0, options.minimumPrice());
            priceMaximum = Math.max(priceMinimum, options.maximumPrice());
            minimumPrice.setMinimum(0);
            minimumPrice.setMaximum(PRICE_SLIDER_STEPS);
            minimumPrice.setValue(0);
            maximumPrice.setMinimum(0);
            maximumPrice.setMaximum(PRICE_SLIDER_STEPS);
            maximumPrice.setValue(PRICE_SLIDER_STEPS);
            minimumPrice.setMajorTickSpacing(PRICE_SLIDER_STEPS / 4);
            maximumPrice.setMajorTickSpacing(PRICE_SLIDER_STEPS / 4);
        } finally {
            updatingControls = false;
        }
        updateAttributeValues();
        updatePriceLabels();
    }

    private void chooseCategory() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JTextField filter = new JTextField();
        filter.setToolTipText("Введите часть названия категории");
        content.add(filter, BorderLayout.NORTH);

        JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("Категории")));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setPreferredSize(new Dimension(430, 360));
        content.add(scroll, BorderLayout.CENTER);

        Runnable rebuild = () -> {
            DefaultMutableTreeNode newRoot = new DefaultMutableTreeNode("Категории");
            String query = filter.getText().trim().toLowerCase(Locale.ROOT);
            for (String fullPath : categories) {
                if (!query.isEmpty() && !fullPath.toLowerCase(Locale.ROOT).contains(query)) continue;
                DefaultMutableTreeNode parent = newRoot;
                StringBuilder path = new StringBuilder();
                for (String part : fullPath.split("\\s+›\\s+")) {
                    if (path.length() > 0) path.append(" › ");
                    path.append(part);
                    String currentPath = path.toString();
                    DefaultMutableTreeNode child = null;
                    for (int index = 0; index < parent.getChildCount(); index++) {
                        DefaultMutableTreeNode existing = (DefaultMutableTreeNode) parent.getChildAt(index);
                        if (existing.getUserObject() instanceof CategoryNode existingCategory
                                && currentPath.equals(existingCategory.path())) {
                            child = existing;
                            break;
                        }
                    }
                    if (child == null) {
                        child = new DefaultMutableTreeNode(new CategoryNode(currentPath, part));
                        parent.add(child);
                    }
                    parent = child;
                }
            }
            tree.setModel(new DefaultTreeModel(newRoot));
            for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
            if (!categoryPath.isBlank()) {
                DefaultMutableTreeNode node = findNode(newRoot, categoryPath);
                if (node != null) tree.setSelectionPath(new TreePath(node.getPath()));
            }
        };
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { rebuild.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { rebuild.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { rebuild.run(); }
        });
        rebuild.run();

        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        JButton clear = new JButton("Все категории");
        clear.addActionListener(event -> {
            setCategory("");
            closeCategoryPicker(content);
        });
        JButton cancel = new JButton("Отмена");
        cancel.addActionListener(event -> closeCategoryPicker(content));
        JButton apply = new JButton("Выбрать");
        apply.addActionListener(event -> {
            TreePath path = tree.getSelectionPath();
            if (path == null) return;
            Object node = path.getLastPathComponent();
            if (node instanceof DefaultMutableTreeNode categoryNode
                    && categoryNode.getUserObject() instanceof CategoryNode value) setCategory(value.path());
            else return;
            closeCategoryPicker(content);
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    apply.doClick();
                }
            }
        });
        actions.add(clear);
        actions.add(cancel);
        actions.add(apply);
        content.add(actions, BorderLayout.SOUTH);
        openCategoryPicker.accept(content);
    }

    private void setCategory(String path) {
        categoryPath = path;
        selectedCategory.setText(categoryPath.isBlank() ? "Все категории" : categoryPath);
        selectedCategory.setToolTipText(categoryPath.isBlank() ? null : categoryPath);
    }

    private static void closeCategoryPicker(JPanel content) {
        JInternalFrame frame = (JInternalFrame) javax.swing.SwingUtilities.getAncestorOfClass(
                JInternalFrame.class, content);
        if (frame != null) frame.dispose();
    }

    private static DefaultMutableTreeNode findNode(DefaultMutableTreeNode root, String path) {
        if (root.getUserObject() instanceof CategoryNode category && path.equals(category.path())) return root;
        for (int index = 0; index < root.getChildCount(); index++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(index);
            DefaultMutableTreeNode found = findNode(child, path);
            if (found != null) return found;
        }
        return null;
    }

    private record CategoryNode(String path, String label) {
        @Override public String toString() { return label; }
    }

    void showResults(List<Product> products) {
        unrankedResults = List.copyOf(products);
        updateResultSearch();
    }

    private void installResultSearch() {
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_F, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                        "openResultSearch");
        getActionMap().put("openResultSearch", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                resultSearchBar.setVisible(true);
                resultSearchInput.requestFocusInWindow();
                resultSearchInput.selectAll();
            }
        });
        resultSearchInput.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { updateResultSearch(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { updateResultSearch(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { updateResultSearch(); }
        });
        resultSearchInput.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        resultSearchInput.getActionMap().put("close", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { closeResultSearch(); }
        });
    }

    private void closeResultSearch() {
        resultSearchInput.setText("");
        resultSearchBar.setVisible(false);
        table.requestFocusInWindow();
        revalidate();
        repaint();
    }

    private void updateResultSearch() {
        String query = resultSearchInput.getText().trim();
        if (query.isEmpty()) {
            results = unrankedResults;
        } else {
            String normalized = query.toLowerCase(Locale.ROOT);
            results = unrankedResults.stream()
                    .sorted((left, right) -> Boolean.compare(matches(right, normalized), matches(left, normalized)))
                    .toList();
        }
        model.setRowCount(0);
        for (Product product : results) {
            model.addRow(new Object[]{product.name(), product.marking(), product.barcode(),
                    product.price() == null ? "—" : product.price().format(),
                    product.totalQuantity().isEmpty() ? "—" : product.totalQuantity()});
        }
        int count = query.isEmpty() ? 0 : (int) results.stream()
                .filter(product -> matches(product, query.toLowerCase(Locale.ROOT))).count();
        resultSearchCount.setText(query.isEmpty() ? " " : count + " совпад.");
        if (table.getRowSorter() instanceof javax.swing.table.TableRowSorter<?> sorter) sorter.setSortKeys(null);
    }

    private static boolean matches(Product product, String normalizedQuery) {
        return contains(product.name(), normalizedQuery)
                || contains(product.marking(), normalizedQuery)
                || contains(product.barcode(), normalizedQuery)
                || contains(product.price() == null ? "—" : product.price().format(), normalizedQuery)
                || contains(product.totalQuantity(), normalizedQuery);
    }

    private static boolean contains(String value, String normalizedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private Product productAt(int row) {
        int modelRow = table.convertRowIndexToModel(row);
        return modelRow >= 0 && modelRow < results.size() ? results.get(modelRow) : null;
    }

    private static String selected(JComboBox<String> combo, String defaultValue) {
        String value = (String) combo.getSelectedItem();
        return value == null || value.equals(defaultValue) ? "" : value;
    }

    private static final class CompactComboBox extends JComboBox<String> {
        private static final int MAX_PREFERRED_WIDTH = 240;

        @Override public Dimension getPreferredSize() {
            Dimension preferred = super.getPreferredSize();
            preferred.width = Math.min(preferred.width, MAX_PREFERRED_WIDTH);
            return preferred;
        }
    }

    private final class HighlightRenderer extends DefaultTableCellRenderer {
        @Override public java.awt.Component getTableCellRendererComponent(JTable source, Object value,
                boolean selected, boolean focused, int row, int column) {
            super.getTableCellRendererComponent(source, value, selected, focused, row, column);
            String text = value == null ? "" : value.toString();
            String query = resultSearchInput.getText().trim();
            setText(query.isEmpty() ? text : highlight(text, query));
            return this;
        }

        private String highlight(String text, String query) {
            Pattern pattern = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Matcher matcher = pattern.matcher(text);
            StringBuilder html = new StringBuilder("<html>");
            int previous = 0;
            while (matcher.find()) {
                html.append(escapeHtml(text.substring(previous, matcher.start())))
                        .append("<span style='background-color:#fff176;color:#111;font-weight:bold'>")
                        .append(escapeHtml(text.substring(matcher.start(), matcher.end())))
                        .append("</span>");
                previous = matcher.end();
            }
            if (previous == 0) return "<html>" + escapeHtml(text) + "</html>";
            return html.append(escapeHtml(text.substring(previous))).append("</html>").toString();
        }

        private String escapeHtml(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&#39;");
        }
    }

    private static final class FilterPanel extends JPanel implements Scrollable {
        private FilterPanel() { super(new GridBagLayout()); }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 14;
        }
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(14, visibleRect.height - 14);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }
}
