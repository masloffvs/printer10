package com.setretailx.printer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;

/** Local catalog search controls and result table. */
@SuppressWarnings("serial")
final class SearchPanel extends JPanel {
    private final JCheckBox subscription = new JCheckBox("Искать товары по штрихкоду из общего потока", false);
    private final JTextField input = new JTextField(28);
    private final DefaultTableModel model = readOnlyModel("Товар", "Артикул", "Штрихкод", "Цена", "Остаток");
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel("Введите название, артикул или штрихкод для локального поиска.");

    SearchPanel(Runnable search, Runnable selectionChanged, Runnable addSelected) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(subscription, BorderLayout.NORTH);
        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        form.add(input);
        JButton find = new JButton("Найти");
        find.addActionListener(event -> search.run());
        input.addActionListener(event -> search.run());
        form.add(find);
        top.add(form, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) selectionChanged.run();
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.add(status, BorderLayout.CENTER);
        JButton add = new JButton("Добавить выбранные в очередь");
        add.addActionListener(event -> addSelected.run());
        footer.add(add, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    boolean isSubscribed() { return subscription.isSelected(); }
    String query() { return input.getText().trim(); }
    void setQuery(String query) { input.setText(query); }
    int[] selectedRows() { return table.getSelectedRows(); }
    int selectedCount() { return table.getSelectedRowCount(); }
    DefaultTableModel model() { return model; }
    void setStatus(String message) { status.setText(message); }

    void showResults(List<Product> products) {
        model.setRowCount(0);
        for (Product product : products) {
            model.addRow(new Object[]{product.name(), product.vendorCode(), product.barcode(),
                    product.price().isEmpty() ? "—" : product.price(),
                    product.totalQuantity().isEmpty() ? "—" : product.totalQuantity()});
        }
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }
}
