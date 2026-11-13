package com.setretailx.printer.ui;

import com.setretailx.printer.model.PrintBatch;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Consumer;

/** Local archive view with refresh and queue restoration actions. */
@SuppressWarnings("serial")
final class PrintHistoryPanel extends JPanel {
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"№", "Дата", "Статус", "Товаров"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JLabel info = new JLabel("Партии печати сохраняются локально.");
    private final JButton restore = new JButton("Восстановить в очередь");
    private List<PrintBatch> batches = List.of();

    PrintHistoryPanel(Consumer<PrintBatch> restoreAction, Runnable refreshAction) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowSelectionAllowed(true);
        table.setFillsViewportHeight(true);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.add(info, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton refresh = new JButton("Обновить");
        refresh.addActionListener(event -> refreshAction.run());
        restore.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (row >= 0 && row < batches.size()) restoreAction.accept(batches.get(row));
            else info.setText("Выберите партию для восстановления.");
        });
        buttons.add(refresh);
        buttons.add(restore);
        footer.add(buttons, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    void showBatches(List<PrintBatch> batches) {
        this.batches = List.copyOf(batches);
        model.setRowCount(0);
        for (PrintBatch batch : this.batches) {
            model.addRow(new Object[]{batch.id(), formatDate(batch.createdAt()), batch.status(), batch.entries().size()});
        }
        info.setText("Сохранено партий: " + this.batches.size());
        restore.setEnabled(!this.batches.isEmpty());
    }

    void setMessage(String message) {
        info.setText(message);
    }

    private static String formatDate(String value) {
        try {
            return OffsetDateTime.parse(value).format(DISPLAY_DATE);
        } catch (DateTimeParseException ignored) {
            return value == null ? "" : value.replace('T', ' ');
        }
    }
}
