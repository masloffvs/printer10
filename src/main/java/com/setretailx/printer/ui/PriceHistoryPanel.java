package com.setretailx.printer.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setretailx.printer.service.PriceMonitoringTaskService;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/** Full timeline of prices observed for one product. */
@SuppressWarnings("serial")
final class PriceHistoryPanel extends JPanel {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private final PriceMonitoringTaskService service;
    private final String marking;
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Когда", "Источник", "Цена до · без скидки / со скидкой",
                    "Цена после · без скидки / со скидкой", "Изменение · без скидки / со скидкой"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JComboBox<SourceFilter> sourceFilter = new JComboBox<>(SourceFilter.values());
    private final JComboBox<PriceKind> kindFilter = new JComboBox<>(PriceKind.values());
    private List<HistoryRow> historyRows = List.of();
    private final JLabel status = new JLabel("Загружаем историю цен…");

    PriceHistoryPanel(PriceMonitoringTaskService service, String marking, String name, String barcode) {
        super(new BorderLayout(8, 8));
        this.service = service;
        this.marking = marking;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JLabel product = new JLabel("Артикул: " + marking + " · " + name
                + (barcode == null || barcode.isBlank() ? "" : " · Штрихкод: " + barcode));
        JPanel header = new JPanel(new BorderLayout(6, 6));
        header.add(product, BorderLayout.NORTH);
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 0));
        filters.add(new JLabel("Источник:"));
        filters.add(sourceFilter);
        filters.add(new JLabel("Цены:"));
        filters.add(kindFilter);
        header.add(filters, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        sourceFilter.addActionListener(event -> renderRows());
        kindFilter.addActionListener(event -> renderRows());
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
                    historyRows = buildRows(snapshots);
                    renderRows();
                    if (historyRows.isEmpty()) status.setText("История пока пуста. Она будет накапливаться после следующих снимков.");
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    status.setText("Не удалось загрузить историю цен: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void renderRows() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::renderRows);
            return;
        }
        SourceFilter selectedSource = (SourceFilter) sourceFilter.getSelectedItem();
        PriceKind selectedKind = (PriceKind) kindFilter.getSelectedItem();
        model.setRowCount(0);
        int shown = 0;
        for (HistoryRow row : historyRows) {
            if (selectedSource != SourceFilter.ALL && row.source() != selectedSource) continue;
            model.addRow(new Object[]{row.time(), row.source().label,
                    priceCell(selectedKind, "Без скидки", row.beforeRegular(), "Со скидкой", row.beforePromo()),
                    priceCell(selectedKind, "Без скидки", row.afterRegular(), "Со скидкой", row.afterPromo()),
                    priceCell(selectedKind, "Без скидки", row.regularChange(), "Со скидкой", row.promoChange())});
            shown++;
        }
        status.setText("Показано строк: " + shown + " · снимков с изменением: " + historyRows.size());
    }

    private static String priceCell(PriceKind kind, String regularLabel, String regular,
                                    String promoLabel, String promo) {
        if (kind == PriceKind.REGULAR) return regularLabel + ": " + regular;
        if (kind == PriceKind.PROMO) return promoLabel + ": " + promo;
        return regularLabel + ": " + regular + " · " + promoLabel + ": " + promo;
    }

    private static List<HistoryRow> buildRows(List<PriceMonitoringTaskService.PriceSnapshot> snapshots) {
        java.util.ArrayList<HistoryRow> rows = new java.util.ArrayList<>();
        for (SourceFilter source : List.of(SourceFilter.NORMAL, SourceFilter.PROMO)) {
            PricePair previous = null;
            for (int index = snapshots.size() - 1; index >= 0; index--) {
                var snapshot = snapshots.get(index);
                JsonNode root;
                try { root = JSON.readTree(snapshot.pricesJson() == null ? "{}" : snapshot.pricesJson()); }
                catch (Exception ignored) { continue; }
                PricePair current = readPair(root.path(source.mapName));
                if (previous != null && !current.equals(previous)) {
                    String time = Instant.ofEpochMilli((long) (snapshot.capturedAt() * 1000))
                            .atZone(ZoneId.systemDefault()).format(TIME);
                    rows.add(new HistoryRow(time, source, previous.regular(), current.regular(),
                            previous.promo(), current.promo(), change(previous.regular(), current.regular()),
                            change(previous.promo(), current.promo()), snapshot.capturedAt()));
                }
                previous = current;
            }
        }
        rows.sort(Comparator.comparingDouble(HistoryRow::capturedAt).reversed());
        return List.copyOf(rows);
    }

    private static PricePair readPair(JsonNode map) {
        String regular = displayPrice(map.path("2"));
        String promo = displayPrice(map.path("4"));
        return new PricePair(regular, promo);
    }

    private static String change(String before, String after) {
        if ("—".equals(before) || "—".equals(after)) return "—";
        try {
            BigDecimal difference = new BigDecimal(after.replace(',', '.')).subtract(new BigDecimal(before.replace(',', '.')));
            if (difference.signum() == 0) return "0";
            return (difference.signum() > 0 ? "+" : "") + difference.stripTrailingZeros().toPlainString().replace('.', ',');
        } catch (NumberFormatException ignored) {
            return "—";
        }
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

    private record PricePair(String regular, String promo) { }
    private record HistoryRow(String time, SourceFilter source, String beforeRegular, String afterRegular,
                              String beforePromo, String afterPromo, String regularChange, String promoChange,
                              double capturedAt) { }

    private enum SourceFilter {
        ALL("Все источники", ""), NORMAL("Обычные · pricesMap", "pricesMap"), PROMO("99 · prices99Map", "prices99Map");
        private final String label;
        private final String mapName;
        SourceFilter(String label, String mapName) { this.label = label; this.mapName = mapName; }
        @Override public String toString() { return label; }
    }

    private enum PriceKind {
        BOTH("Без скидки и со скидкой"), REGULAR("Только без скидки"), PROMO("Только со скидкой");
        private final String label;
        PriceKind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
}
