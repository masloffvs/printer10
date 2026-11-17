package com.setretailx.printer.ui;

import com.setretailx.printer.repository.ApiExchangeLogRepository;
import com.setretailx.printer.repository.ApiExchangeLogRepository.ExchangeLogEntry;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Browse saved SetRetailX HTTP exchanges and inspect a selected request in detail. */
@SuppressWarnings("serial")
final class ApiExchangeLogPanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ApiExchangeLogRepository repository = new ApiExchangeLogRepository();
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Время", "Метод", "HTTP", "Длительность", "Результат"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JTextArea details = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private List<ExchangeLogEntry> entries = List.of();
    private boolean refreshing;

    ApiExchangeLogPanel() {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JButton refresh = new JButton("Обновить");
        refresh.addActionListener(event -> reload());
        JPanel header = new JPanel(new BorderLayout());
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.add(refresh);
        controls.add(new JLabel("Хранится до " + ApiExchangeLogRepository.MAX_EXCHANGES + " последних обменов."));
        header.add(controls, BorderLayout.NORTH);
        header.add(status, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !refreshing) showSelected();
        });
        JScrollPane exchanges = new JScrollPane(table);
        exchanges.setBorder(BorderFactory.createTitledBorder("HTTP-запросы к SetRetailX"));
        details.setEditable(false);
        details.setLineWrap(false);
        details.setTabSize(2);
        JScrollPane detailScroll = new JScrollPane(details);
        detailScroll.setBorder(BorderFactory.createTitledBorder("Подробности обмена"));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, exchanges, detailScroll);
        split.setResizeWeight(0.4);
        add(split, BorderLayout.CENTER);
        reload();
    }

    private void reload() {
        Long selectedId = selectedId();
        refreshing = true;
        try {
            entries = repository.recent(ApiExchangeLogRepository.MAX_EXCHANGES);
            model.setRowCount(0);
            int selectedRow = -1;
            for (int index = 0; index < entries.size(); index++) {
                ExchangeLogEntry entry = entries.get(index);
                model.addRow(new Object[]{formatTime(entry.occurredAt()), entry.method(),
                        entry.httpStatus() == null ? "—" : entry.httpStatus(), entry.durationMs() + " мс",
                        "success".equals(entry.outcome()) ? "Успешно" : "Ошибка"});
                if (selectedId != null && selectedId == entry.id()) selectedRow = index;
            }
            if (selectedRow < 0 && !entries.isEmpty()) selectedRow = 0;
            if (selectedRow >= 0) table.setRowSelectionInterval(selectedRow, selectedRow);
            else details.setText("Сохранённых HTTP-обменов пока нет.");
            status.setText("Записей: " + entries.size());
        } catch (SQLException error) {
            status.setText("Не удалось прочитать журнал: " + error.getMessage());
        } finally {
            refreshing = false;
        }
        showSelected();
    }

    private Long selectedId() {
        int row = table.getSelectedRow();
        return row >= 0 && row < entries.size() ? entries.get(row).id() : null;
    }

    private void showSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= entries.size()) return;
        ExchangeLogEntry entry = entries.get(row);
        details.setText("ID: " + entry.id() + "\n"
                + "Время: " + formatTime(entry.occurredAt()) + "\n"
                + "Метод: " + entry.method() + "\n"
                + "HTTP: " + (entry.httpStatus() == null ? "—" : entry.httpStatus()) + "\n"
                + "Длительность: " + entry.durationMs() + " мс\n"
                + "Результат: " + ("success".equals(entry.outcome()) ? "Успешно" : "Ошибка") + "\n"
                + (entry.error() == null || entry.error().isBlank() ? "" : "Ошибка:\n" + entry.error() + "\n")
                + "\nREQUEST\n" + entry.requestBody()
                + "\n\nRESPONSE\n" + entry.responseBody());
        details.setCaretPosition(0);
    }

    private static String formatTime(String value) {
        try {
            return Instant.parse(value).atZone(ZoneId.systemDefault()).format(TIME);
        } catch (RuntimeException ignored) {
            return value == null ? "—" : value;
        }
    }
}
