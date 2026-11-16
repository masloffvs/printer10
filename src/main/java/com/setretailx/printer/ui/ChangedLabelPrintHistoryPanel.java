package com.setretailx.printer.ui;

import com.setretailx.printer.model.ChangedLabelPrint;
import com.setretailx.printer.repository.ChangedLabelPrintHistoryRepository;
import com.setretailx.printer.config.AppConfig;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Separate history of printed labels created from price-change events. */
@SuppressWarnings("serial")
final class ChangedLabelPrintHistoryPanel extends JPanel {
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ChangedLabelPrintHistoryRepository repository;
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{
            "Дата печати", "Артикул", "Товар", "Штрихкод", "Формат", "Тип цены", "Тираж", "Количество"
    }, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JLabel status = new JLabel(" ");
    private final Timer refreshTimer;

    ChangedLabelPrintHistoryPanel(ChangedLabelPrintHistoryRepository repository) {
        super(new BorderLayout(8, 8));
        this.repository = repository;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.add(status, BorderLayout.CENTER);
        JButton refresh = new JButton("Обновить");
        refresh.addActionListener(event -> refresh());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(refresh);
        footer.add(buttons, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        refresh();
        refreshTimer = new Timer(10000, event -> refresh());
        refreshTimer.start();
    }

    void close() { refreshTimer.stop(); }

    private void refresh() {
        try {
            List<ChangedLabelPrint> rows = repository.list();
            model.setRowCount(0);
            for (ChangedLabelPrint printed : rows) {
                var entry = printed.entry();
                var product = entry.product();
                var format = AppConfig.formatByKey(entry.formatKey());
                model.addRow(new Object[]{formatDate(printed.printedAt()), product.marking(), product.name(),
                        product.barcode(), format == null ? "—" : format.name(), entry.priceType().title(),
                        entry.amount() == null ? "—" : entry.amount(), entry.quantity()});
            }
            status.setText("Успешно отправлено ценников по изменениям: " + rows.size());
        } catch (SQLException error) {
            status.setText("Не удалось загрузить историю изменённых ценников: " + error.getMessage());
        }
    }

    private static String formatDate(String value) {
        try { return OffsetDateTime.parse(value).format(DISPLAY_DATE); }
        catch (DateTimeParseException ignored) { return value == null ? "" : value.replace('T', ' '); }
    }
}
