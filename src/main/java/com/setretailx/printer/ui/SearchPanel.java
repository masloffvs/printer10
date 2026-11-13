package com.setretailx.printer.ui;

import com.setretailx.printer.model.Product;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

/** Local catalog search controls and result table. */
@SuppressWarnings("serial")
final class SearchPanel extends JPanel {
    private final JTextField input = new JTextField(28);
    private final DefaultTableModel model = readOnlyModel("Товар", "Артикул", "Штрихкод", "Цена", "Остаток");
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel("Введите название, артикул или штрихкод для локального поиска.");
    private List<Product> results = List.of();

    SearchPanel(Runnable search, Runnable selectionChanged, Runnable addSelected, Consumer<Product> openProduct) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel top = new JPanel(new BorderLayout(8, 8));
        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        input.setToolTipText("Можно вставить обычный штрихкод, строку barcode=… или полный скан Честного знака/GS1.");
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
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.add(status, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }

    String query() { return input.getText().trim(); }
    void setQuery(String query) { input.setText(query); }
    int[] selectedRows() { return table.getSelectedRows(); }
    int selectedCount() { return table.getSelectedRowCount(); }
    DefaultTableModel model() { return model; }
    void setStatus(String message) { status.setText(message); }

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

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }
}
