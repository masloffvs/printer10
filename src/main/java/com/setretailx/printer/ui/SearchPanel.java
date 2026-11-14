package com.setretailx.printer.ui;

import com.setretailx.printer.model.Product;
import com.setretailx.printer.repository.ProductRepository.SearchOptions;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Local catalog search with results on the left and explicit filters on the right. */
@SuppressWarnings("serial")
final class SearchPanel extends JPanel {
    private static final int PRICE_SLIDER_STEPS = 1000;

    private final JTextField input = new JTextField();
    private final JComboBox<String> category = new JComboBox<>();
    private final JComboBox<String> attributeName = new JComboBox<>();
    private final JComboBox<String> attributeValue = new JComboBox<>();
    private final JSlider minimumPrice = new JSlider();
    private final JSlider maximumPrice = new JSlider();
    private final JLabel minimumPriceLabel = new JLabel("Без ограничения");
    private final JLabel maximumPriceLabel = new JLabel("Без ограничения");
    private final JLabel status = new JLabel("Задайте запрос или фильтры и выполните поиск.");
    private final DefaultTableModel model = readOnlyModel("Товар", "Артикул", "Штрихкод", "Цена", "Остаток");
    private final JTable table = new JTable(model);
    private final JPanel filters = new JPanel(new GridBagLayout());
    private final JButton find = new JButton("Найти товары");
    private final JButton clear = new JButton("Сбросить");
    private List<Product> results = List.of();
    private Map<String, List<String>> attributeOptions = Map.of();
    private long priceMinimum;
    private long priceMaximum;
    private boolean updatingControls;

    SearchPanel(Runnable search, Runnable selectionChanged, Runnable addSelected, Consumer<Product> openProduct) {
        super(new BorderLayout(12, 10));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel resultPane = new JPanel(new BorderLayout(0, 8));
        JLabel resultsHeading = new JLabel("Результаты поиска");
        resultsHeading.setFont(resultsHeading.getFont().deriveFont(java.awt.Font.BOLD, 15f));
        resultPane.add(resultsHeading, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        table.setRowHeight(25);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) selectionChanged.run();
        });
        addContextMenu(addSelected, openProduct);
        resultPane.add(new JScrollPane(table), BorderLayout.CENTER);
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

        addSectionLabel("Категория товара", c, 2);
        category.addItem("Все категории");
        filters.add(category, at(c, 3));

        addSectionLabel("Цена от", c, 4);
        minimumPrice.setFocusable(false);
        minimumPrice.setPaintTicks(true);
        minimumPrice.setMajorTickSpacing(250);
        filters.add(minimumPrice, at(c, 5));
        filters.add(minimumPriceLabel, at(c, 6));

        addSectionLabel("Цена до", c, 7);
        maximumPrice.setFocusable(false);
        maximumPrice.setPaintTicks(true);
        maximumPrice.setMajorTickSpacing(250);
        filters.add(maximumPrice, at(c, 8));
        filters.add(maximumPriceLabel, at(c, 9));

        addSectionLabel("Характеристика", c, 10);
        attributeName.addItem("Не выбрана");
        attributeName.addActionListener(event -> updateAttributeValues());
        filters.add(attributeName, at(c, 11));

        addSectionLabel("Значение", c, 12);
        attributeValue.addItem("Любое значение");
        filters.add(attributeValue, at(c, 13));

        JLabel hint = new JLabel("Характеристики применяются до поиска и сужают выборку.");
        hint.setVerticalAlignment(SwingConstants.TOP);
        hint.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        filters.add(hint, at(c, 14));

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
            category.setSelectedIndex(0);
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
    void setQuery(String query) { input.setText(query); }
    int[] selectedRows() {
        return java.util.Arrays.stream(table.getSelectedRows())
                .map(table::convertRowIndexToModel).toArray();
    }
    int selectedCount() { return table.getSelectedRowCount(); }
    DefaultTableModel model() { return model; }
    void setStatus(String message) { status.setText(message); }
    void setSearchEnabled(boolean enabled) { find.setEnabled(enabled); }

    String categoryFilter() { return selected(category, "Все категории"); }
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
            category.removeAllItems();
            category.addItem("Все категории");
            options.categories().forEach(category::addItem);
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

    void showResults(List<Product> products) {
        results = List.copyOf(products);
        model.setRowCount(0);
        for (Product product : results) {
            model.addRow(new Object[]{product.name(), product.vendorCode(), product.barcode(),
                    product.price() == null ? "—" : product.price().format(),
                    product.totalQuantity().isEmpty() ? "—" : product.totalQuantity()});
        }
    }

    private Product productAt(int row) {
        int modelRow = table.convertRowIndexToModel(row);
        return modelRow >= 0 && modelRow < results.size() ? results.get(modelRow) : null;
    }

    private static String selected(JComboBox<String> combo, String defaultValue) {
        String value = (String) combo.getSelectedItem();
        return value == null || value.equals(defaultValue) ? "" : value;
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }
}
