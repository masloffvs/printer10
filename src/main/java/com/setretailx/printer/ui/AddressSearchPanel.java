package com.setretailx.printer.ui;

import com.setretailx.printer.repository.AddressStorageRepository;
import com.setretailx.printer.repository.AddressStorageRepository.LocationResult;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;

/** Standalone search window for finding products in address storage. */
@SuppressWarnings("serial")
final class AddressSearchPanel extends JPanel {
    private final AddressStorageRepository repository;
    private final JTextField query = new JTextField(28);
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Адрес", "Коробка", "Товар", "Артикул", "Штрихкод", "Кол-во"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JLabel status = new JLabel("Поиск по адресу, коробке, артикулу, штрихкоду или названию.");

    AddressSearchPanel(AddressStorageRepository repository) {
        super(new BorderLayout(8, 8));
        this.repository = repository;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 6, 0));
        controls.add(new JLabel("Запрос:"));
        controls.add(query);
        JButton find = new JButton("Найти");
        find.addActionListener(event -> find());
        controls.add(find);
        query.addActionListener(event -> find());
        add(controls, BorderLayout.NORTH);
        add(new JScrollPane(new JTable(model)), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    private void find() {
        String text = query.getText().trim();
        if (text.isEmpty()) {
            status.setText("Введите запрос для поиска.");
            return;
        }
        status.setText("Ищем размещения…");
        new SwingWorker<java.util.List<LocationResult>, Void>() {
            @Override protected java.util.List<LocationResult> doInBackground() throws Exception {
                return repository.findLocations(text);
            }
            @Override protected void done() {
                try {
                    var rows = get();
                    model.setRowCount(0);
                    for (LocationResult row : rows) model.addRow(new Object[]{
                            row.addressId() + " — " + row.addressName(), row.boxCode(), row.name(),
                            row.vendorCode(), row.barcode(), row.quantity()});
                    status.setText(rows.isEmpty() ? "Ничего не найдено." : "Найдено размещений: " + rows.size());
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    status.setText("Ошибка поиска: " + cause.getMessage());
                }
            }
        }.execute();
    }
}
