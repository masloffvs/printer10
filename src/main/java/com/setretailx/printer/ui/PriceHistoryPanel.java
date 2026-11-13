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
            new Object[]{"Когда", "Источник", "Позиция", "Цена"}, 0) {
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
        appendMap(rows, time, prices.path("pricesMap"), "pricesMap · обычные");
        appendMap(rows, time, prices.path("prices99Map"), "prices99Map · 99");
    }

    private static void appendMap(List<Object[]> rows, String time, JsonNode map, String type) {
        if (!map.isObject()) return;
        map.fields().forEachRemaining(entry -> rows.add(new Object[]{time, type,
                positionLabel(entry.getKey()), displayPrice(entry.getValue())}));
        JsonNode finalNode = map.path("4");
        String finalPosition = "price4 · цена после скидки";
        if ("—".equals(displayPrice(finalNode))) {
            finalNode = map.path("2");
            finalPosition = "price2 · до скидки (итог без price4)";
        }
        if (!"—".equals(displayPrice(finalNode))) {
            rows.add(new Object[]{time, type, "Итоговая · " + finalPosition, displayPrice(finalNode)});
        }
    }

    private static String positionLabel(String number) {
        return switch (number) {
            case "1" -> "#1 · обычная (price1)";
            case "2" -> "#2 · до скидки (price2)";
            case "3" -> "#3 · неизвестно (price3)";
            case "4" -> "#4 · со скидкой (price4)";
            default -> "#" + number;
        };
    }

    private static String displayPrice(JsonNode node) {
        JsonNode valueNode = node != null && node.isObject() ? node.path("value") : node;
        if (valueNode == null || valueNode.isMissingNode() || valueNode.isNull()) return "—";
        String value = valueNode.asText("");
        if (value.isBlank()) return "—";
        try {
            return new java.math.BigDecimal(value).stripTrailingZeros().toPlainString().replace('.', ',');
        } catch (NumberFormatException ignored) {
            return value;
        }
    }
}
