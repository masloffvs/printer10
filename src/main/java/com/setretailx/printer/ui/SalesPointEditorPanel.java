package com.setretailx.printer.ui;

import com.setretailx.printer.model.Product;
import com.setretailx.printer.model.ScannedProbe;
import com.setretailx.printer.repository.SalesPointRepository;
import com.setretailx.printer.repository.SalesPointRepository.SalesPoint;
import com.setretailx.printer.repository.SalesPointRepository.ShelfItem;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Editor for the current label prices of one selected sales point. */
@SuppressWarnings("serial")
final class SalesPointEditorPanel extends JPanel {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());
    private final SalesPointRepository repository;
    private final JComboBox<SalesPoint> pointSelector = new JComboBox<>();
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Артикул", "Товар", "Обычная цена", "Скидочная цена", "Обновлено"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JLabel heading = new JLabel("Выберите торговую точку для редактирования.");
    private long selectedPointId = -1;
    private boolean updatingSelector;
    private Consumer<List<ShelfItem>> historyCheckHandler = ignored -> { };
    private Consumer<List<ShelfItem>> forcedCheckHandler = ignored -> { };

    SalesPointEditorPanel(SalesPointRepository repository) {
        super(new BorderLayout(8, 8));
        this.repository = repository;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        pointSelector.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setText(value instanceof SalesPoint point ? point.name() : "Нет торговых точек");
                return this;
            }
        });
        pointSelector.addActionListener(event -> {
            if (!updatingSelector) selectPoint((SalesPoint) pointSelector.getSelectedItem());
        });
        JButton refresh = new JButton("Обновить список");
        refresh.addActionListener(event -> refreshPoints());
        JPanel selector = new JPanel(new BorderLayout(8, 0));
        selector.add(new JLabel("Точка:"), BorderLayout.WEST);
        selector.add(pointSelector, BorderLayout.CENTER);
        selector.add(refresh, BorderLayout.EAST);
        selector.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING));
        JButton add = new JButton("Добавить ценник");
        add.addActionListener(event -> addItem());
        JButton remove = new JButton("Удалить выбранный артикул");
        remove.addActionListener(event -> removeItem());
        JButton refreshItems = new JButton("Обновить ценники");
        refreshItems.addActionListener(event -> refreshItems());
        JButton checkHistory = new JButton("Проверить историю цен");
        checkHistory.addActionListener(event -> historyCheckHandler.accept(selectedItems()));
        JButton forceCheck = new JButton("Принудительно сверить");
        forceCheck.addActionListener(event -> forcedCheckHandler.accept(selectedItems()));
        actions.add(add);
        actions.add(remove);
        actions.add(refreshItems);
        actions.add(checkHistory);
        actions.add(forceCheck);

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(selector, BorderLayout.NORTH);
        top.add(heading, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(actions, BorderLayout.SOUTH);
        refreshPoints();
    }

    long selectedPointId() { return selectedPointId; }

    void setHistoryCheckHandler(Consumer<List<ShelfItem>> handler) {
        historyCheckHandler = handler == null ? ignored -> { } : handler;
    }

    void setForcedCheckHandler(Consumer<List<ShelfItem>> handler) {
        forcedCheckHandler = handler == null ? ignored -> { } : handler;
    }

    void selectPoint(SalesPoint point) {
        selectedPointId = point == null ? -1 : point.id();
        if (point != null) {
            updatingSelector = true;
            try {
                pointSelector.setSelectedItem(findPoint(point.id()));
            } finally {
                updatingSelector = false;
            }
        }
        heading.setText(point == null ? "Выберите торговую точку для редактирования." : "Ценники точки: " + point.name());
        refreshItems();
    }

    void refreshPoints() {
        long keepId = selectedPointId;
        try {
            var points = repository.points();
            updatingSelector = true;
            pointSelector.setModel(new DefaultComboBoxModel<>(points.toArray(SalesPoint[]::new)));
            SalesPoint selected = points.stream().filter(point -> point.id() == keepId).findFirst().orElse(null);
            pointSelector.setSelectedItem(selected);
            updatingSelector = false;
            selectPoint(selected);
        } catch (Exception error) {
            updatingSelector = false;
            showError(error);
        }
    }

    void acceptPriceScan(long expectedPointId, Product product, ScannedProbe probe) {
        if (selectedPointId < 0 || selectedPointId != expectedPointId) {
            heading.setText("Выбор точки изменился во время поиска. Повторите скан ценника.");
            return;
        }
        String article = product.marking();
        if (article.isBlank()) {
            heading.setText("В найденном товаре нет артикула.");
            return;
        }
        try {
            var scanned = probe.pricesAsDecimals();
            BigDecimal regular = scanned.get(1);
            if (regular == null) regular = scanned.get(2);
            BigDecimal discount = scanned.get(4);
            if (regular == null && discount == null) {
                heading.setText("На ценнике нет обычной (price1/price2) или скидочной (price4) цены.");
                return;
            }
            repository.putItem(selectedPointId, article, product.name(),
                    regular == null ? null : regular.toPlainString(), discount == null ? null : discount.toPlainString());
            refreshItems();
            heading.setText("Ценник «" + article + "» обновлён: обычная " + display(regular)
                    + ", скидочная " + display(discount) + ".");
        } catch (Exception error) {
            showError(error);
        }
    }

    private void addItem() {
        if (selectedPointId < 0) {
            heading.setText("Сначала выберите или создайте точку на карте.");
            return;
        }
        JTextField article = new JTextField(18);
        JTextField name = new JTextField(18);
        JTextField price = new JTextField(12);
        JTextField discountPrice = new JTextField(12);
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Артикул:"));
        form.add(article);
        form.add(new JLabel("Название (необязательно):"));
        form.add(name);
        form.add(new JLabel("Обычная цена:"));
        form.add(price);
        form.add(new JLabel("Скидочная цена (необязательно):"));
        form.add(discountPrice);
        if (JOptionPane.showConfirmDialog(this, form, "Ценник торговой точки",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            BigDecimal parsed = parseOptionalPrice(price.getText());
            BigDecimal parsedDiscount = parseOptionalPrice(discountPrice.getText());
            repository.putItem(selectedPointId, article.getText(), name.getText(),
                    parsed == null ? "" : parsed.toPlainString(),
                    parsedDiscount == null ? "" : parsedDiscount.toPlainString());
            refreshItems();
        } catch (NumberFormatException error) {
            JOptionPane.showMessageDialog(this, "Введите корректную цену.");
        } catch (Exception error) {
            showError(error);
        }
    }

    private void removeItem() {
        if (selectedPointId < 0 || table.getSelectedRow() < 0) return;
        String article = String.valueOf(model.getValueAt(table.convertRowIndexToModel(table.getSelectedRow()), 0));
        try {
            repository.removeItem(selectedPointId, article);
            refreshItems();
        } catch (Exception error) {
            showError(error);
        }
    }

    private void refreshItems() {
        model.setRowCount(0);
        if (selectedPointId < 0) return;
        try {
            for (ShelfItem item : repository.items(selectedPointId)) {
                model.addRow(new Object[]{item.article(), item.name(), displayPrice(item.price()),
                        displayPrice(item.discountPrice()),
                        DATE.format(Instant.ofEpochMilli(item.updatedAt()))});
            }
        } catch (Exception error) {
            showError(error);
        }
    }

    private List<ShelfItem> selectedItems() {
        int[] selectedRows = table.getSelectedRows();
        if (selectedRows.length == 0) {
            heading.setText("Выберите один или несколько ценников в таблице.");
            return List.of();
        }
        List<ShelfItem> selected = new ArrayList<>();
        try {
            List<ShelfItem> items = repository.items(selectedPointId);
            for (int row : selectedRows) {
                String article = String.valueOf(model.getValueAt(table.convertRowIndexToModel(row), 0));
                items.stream().filter(item -> item.article().equals(article)).findFirst().ifPresent(selected::add);
            }
        } catch (Exception error) {
            showError(error);
        }
        return List.copyOf(selected);
    }

    private SalesPoint findPoint(long id) {
        for (int index = 0; index < pointSelector.getItemCount(); index++) {
            SalesPoint point = pointSelector.getItemAt(index);
            if (point.id() == id) return point;
        }
        return null;
    }

    private void showError(Exception error) {
        heading.setText("Ошибка: " + (error.getMessage() == null ? error : error.getMessage()));
    }

    private static BigDecimal parseOptionalPrice(String text) {
        if (text == null || text.isBlank()) return null;
        BigDecimal parsed = new BigDecimal(text.trim().replace(',', '.'));
        if (parsed.signum() < 0) throw new NumberFormatException();
        return parsed;
    }

    private static String display(BigDecimal price) { return price == null ? "—" : price.toPlainString(); }

    private static String displayPrice(String price) {
        return price == null || price.isBlank() ? "—" : price;
    }
}
