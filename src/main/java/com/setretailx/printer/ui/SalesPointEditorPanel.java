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

/** Editor for the current label prices of one selected sales point. */
@SuppressWarnings("serial")
final class SalesPointEditorPanel extends JPanel {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());
    private final SalesPointRepository repository;
    private final JComboBox<SalesPoint> pointSelector = new JComboBox<>();
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Артикул", "Товар", "Цена на ценнике", "Обновлено"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JLabel heading = new JLabel("Выберите торговую точку для редактирования.");
    private long selectedPointId = -1;
    private boolean updatingSelector;

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
        actions.add(add);
        actions.add(remove);
        actions.add(refreshItems);

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(selector, BorderLayout.NORTH);
        top.add(heading, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        table.setFillsViewportHeight(true);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(actions, BorderLayout.SOUTH);
        refreshPoints();
    }

    long selectedPointId() { return selectedPointId; }

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
        String article = product.vendorCode().isBlank() ? product.stCode() : product.vendorCode();
        if (article.isBlank()) {
            heading.setText("В найденном товаре нет артикула.");
            return;
        }
        try {
            var scanned = probe.pricesAsDecimals();
            BigDecimal price = scanned.get(4);
            if (price == null) price = scanned.get(2);
            if (price == null) {
                heading.setText("На ценнике нет price4 или price2 — нечего записать.");
                return;
            }
            repository.putItem(selectedPointId, article, product.name(), price.toPlainString());
            refreshItems();
            heading.setText("Ценник «" + article + "» обновлён для точки.");
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
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Артикул:"));
        form.add(article);
        form.add(new JLabel("Название (необязательно):"));
        form.add(name);
        form.add(new JLabel("Цена ценника:"));
        form.add(price);
        if (JOptionPane.showConfirmDialog(this, form, "Ценник торговой точки",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            BigDecimal parsed = new BigDecimal(price.getText().trim().replace(',', '.'));
            if (parsed.signum() < 0) throw new NumberFormatException();
            repository.putItem(selectedPointId, article.getText(), name.getText(), parsed.toPlainString());
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
                model.addRow(new Object[]{item.article(), item.name(), item.price(),
                        DATE.format(Instant.ofEpochMilli(item.updatedAt()))});
            }
        } catch (Exception error) {
            showError(error);
        }
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
}
