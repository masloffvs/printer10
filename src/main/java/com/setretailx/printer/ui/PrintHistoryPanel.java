package com.setretailx.printer.ui;

import com.setretailx.printer.model.PrintBatch;
import com.setretailx.printer.model.PrintHistoryEntry;
import com.setretailx.printer.config.AppConfig;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JSplitPane;
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
    private final DefaultTableModel detailsModel = new DefaultTableModel(
            new Object[]{"Артикул", "Товар", "Формат", "Тип ценника", "Тираж", "Экземпляров", "Результат"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable detailsTable = new JTable(detailsModel);
    private final JLabel info = new JLabel("Партии печати сохраняются локально.");
    private final JButton restore = new JButton("Восстановить в очередь");
    private List<PrintBatch> batches = List.of();

    PrintHistoryPanel(Consumer<PrintBatch> restoreAction, Runnable refreshAction) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowSelectionAllowed(true);
        table.setFillsViewportHeight(true);
        detailsTable.setFillsViewportHeight(true);
        detailsTable.setAutoCreateRowSorter(true);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelectedBatchDetails();
        });
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(detailsTable));
        split.setResizeWeight(0.56);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

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
        if (!this.batches.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        } else {
            detailsModel.setRowCount(0);
        }
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

    private void showSelectedBatchDetails() {
        int row = table.getSelectedRow();
        detailsModel.setRowCount(0);
        if (row < 0 || row >= batches.size()) return;
        PrintBatch batch = batches.get(table.convertRowIndexToModel(row));
        java.util.Set<Integer> successful = new java.util.HashSet<>(batch.successfulIndexes());
        for (int index = 0; index < batch.entries().size(); index++) {
            PrintHistoryEntry entry = batch.entries().get(index);
            AppConfig.PrintFormat format = AppConfig.formatByKey(entry.formatKey());
            String result = successful.contains(index) || (batch.successfulIndexes().isEmpty()
                    && "Отправлено".equals(batch.status())) ? "Отправлено"
                    : "Частично отправлено".equals(batch.status()) ? "Не отправлено" : batch.status();
            detailsModel.addRow(new Object[]{article(entry), entry.product().name(),
                    format == null ? "—" : format.name(), entry.priceType().title(),
                    entry.amount() == null ? "—" : entry.amount(), entry.quantity(), result});
        }
    }

    private static String article(PrintHistoryEntry entry) {
        String marking = entry.product().marking();
        return marking.isBlank() ? entry.product().barcode() : marking;
    }
}
