package com.setretailx.printer.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setretailx.printer.service.PriceMonitoringTaskService;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Full timeline of prices observed for one product. */
@SuppressWarnings("serial")
final class PriceHistoryPanel extends JPanel {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private final PriceMonitoringTaskService service;
    private final String marking;
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Когда", "Тип цены", "Номер", "Цена"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JLabel status = new JLabel("Загружаем историю цен…");

    PriceHistoryPanel(PriceMonitoringTaskService service, String marking, String name, String barcode) {
        super(new BorderLayout(8, 8));
        this.service = service;
        this.marking = marking;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JLabel product = new JLabel("Артикул: " + marking + " · " + name
                + (barcode == null || barcode.isBlank() ? "" : " · Штрихкод: " + barcode));
        add(product, BorderLayout.NORTH);
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        load();
    }

    private void load() {
        new SwingWorker<List<PriceMonitoringTaskService.PriceSnapshot>, Void>() {
            @Override protected List<PriceMonitoringTaskService.PriceSnapshot> doInBackground() throws Exception {
                return service.priceHistory(marking);
            }

            @Override protected void done() {
                try {
                    List<PriceMonitoringTaskService.PriceSnapshot> snapshots = get();
                    List<Object[]> rows = new ArrayList<>();
                    for (var snapshot : snapshots) appendPrices(rows, snapshot);
                    for (Object[] row : rows) model.addRow(row);
                    status.setText("Снимков цен: " + snapshots.size() + " · записей цен: " + rows.size());
                    if (rows.isEmpty()) status.setText("История пока пуста. Она будет накапливаться после следующих снимков.");
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    status.setText("Не удалось загрузить историю цен: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private static void appendPrices(List<Object[]> rows, PriceMonitoringTaskService.PriceSnapshot snapshot) {
        JsonNode prices;
        try { prices = JSON.readTree(snapshot.pricesJson() == null ? "{}" : snapshot.pricesJson()); }
        catch (Exception ignored) { return; }
        String time = Instant.ofEpochMilli((long) (snapshot.capturedAt() * 1000))
                .atZone(ZoneId.systemDefault()).format(TIME);
        appendMap(rows, time, prices.path("pricesMap"), "Обычная (pricesMap)");
        appendMap(rows, time, prices.path("prices99Map"), "99 (prices99Map)");
    }

    private static void appendMap(List<Object[]> rows, String time, JsonNode map, String type) {
        if (!map.isObject()) return;
        map.fields().forEachRemaining(entry -> rows.add(new Object[]{time, type, "#" + entry.getKey(), entry.getValue().asText()}));
    }
}
