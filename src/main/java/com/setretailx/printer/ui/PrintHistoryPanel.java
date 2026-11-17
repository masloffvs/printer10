package com.setretailx.printer.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.setretailx.printer.model.PrintBatch;
import com.setretailx.printer.model.PrintHistoryEntry;
import com.setretailx.printer.config.AppConfig;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Consumer;

/** Local archive view with refresh and queue restoration actions. */
@SuppressWarnings("serial")
final class PrintHistoryPanel extends JPanel {
    private static final ObjectMapper JSON = new ObjectMapper();
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
    private final JButton save = new JButton("Сохранить очередь в файл");
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
        save.addActionListener(event -> saveSelectedQueue());
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "savePrintQueue");
        getActionMap().put("savePrintQueue", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { saveSelectedQueue(); }
        });
        buttons.add(refresh);
        buttons.add(save);
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
        save.setEnabled(!this.batches.isEmpty());
        if (!this.batches.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        } else {
            detailsModel.setRowCount(0);
        }
    }

    void setMessage(String message) {
        info.setText(message);
    }

    private void saveSelectedQueue() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= batches.size()) {
            info.setText("Выберите партию печати для сохранения.");
            return;
        }
        PrintBatch batch = batches.get(table.convertRowIndexToModel(row));
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Сохранить очередь печати");
        chooser.setFileFilter(new FileNameExtensionFilter("Очередь печати (*.json)", "json"));
        chooser.setSelectedFile(new File("print_queue_" + batch.id() + ".json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File selected = chooser.getSelectedFile();
        if (!selected.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
            selected = new File(selected.getParentFile(), selected.getName() + ".json");
        }
        Path path = selected.toPath();
        if (Files.exists(path) && javax.swing.JOptionPane.showConfirmDialog(this,
                "Файл уже существует. Перезаписать его?", "Сохранение очереди",
                javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE)
                != javax.swing.JOptionPane.YES_OPTION) return;

        ObjectNode document = JSON.createObjectNode();
        document.put("format", "setretailx-print-queue");
        document.put("version", 1);
        document.set("entries", JSON.valueToTree(batch.entries()));
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), document);
            info.setText("Очередь сохранена: " + path.getFileName());
        } catch (IOException error) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Не удалось сохранить очередь: " + error.getMessage(),
                    "Сохранение очереди", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
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
