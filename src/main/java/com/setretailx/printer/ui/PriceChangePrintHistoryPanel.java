package com.setretailx.printer.ui;

import com.setretailx.printer.model.PriceChangePrintEntry;
import com.setretailx.printer.model.PriceChangePrintReport;
import com.setretailx.printer.repository.PriceChangePrintHistoryRepository;

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

/** Separate history of printed price-change reports. */
@SuppressWarnings("serial")
final class PriceChangePrintHistoryPanel extends JPanel {
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final PriceChangePrintHistoryRepository repository;
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{
            "Дата печати", "Артикул", "Товар", "Штрихкод", "Ценовая позиция", "Цена до", "Цена после", "Изменение"
    }, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JLabel status = new JLabel(" ");
    private final Timer refreshTimer;

    PriceChangePrintHistoryPanel(PriceChangePrintHistoryRepository repository) {
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
            List<PriceChangePrintReport> reports = repository.reports();
            model.setRowCount(0);
            int entries = 0;
            for (PriceChangePrintReport report : reports) {
                String date = formatDate(report.createdAt());
                for (PriceChangePrintEntry row : report.entries()) {
                    model.addRow(new Object[]{date, row.article(), row.product(), row.barcode(), row.priceType(),
                            row.oldPrice(), row.newPrice(), row.delta()});
                    entries++;
                }
            }
            status.setText("Отчётов: " + reports.size() + " · напечатано строк изменений: " + entries);
        } catch (SQLException error) {
            status.setText("Не удалось загрузить историю печати изменений: " + error.getMessage());
        }
    }

    private static String formatDate(String value) {
        try { return OffsetDateTime.parse(value).format(DISPLAY_DATE); }
        catch (DateTimeParseException ignored) { return value == null ? "" : value.replace('T', ' '); }
    }
}
