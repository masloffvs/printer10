package com.setretailx.printer.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setretailx.printer.service.PriceMonitoringTaskService;
import com.setretailx.printer.model.BarInput;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Separate MDI view of the detected product price changes. */
@SuppressWarnings("serial")
final class PriceChangesPanel extends JPanel {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter CHANGE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private final PriceMonitoringTaskService service;
    private final HistoryOpener openHistory;
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Когда", "Артикул", "Товар", "Штрихкод", "Тип цены", "Было", "Стало"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JTextField search = new JTextField(18);
    private final JComboBox<PriceFilter> priceType = new JComboBox<>(PriceFilter.values());
    private final JComboBox<String> priceNumber = new JComboBox<>(new String[]{"Все номера", "#1", "#2", "#3", "#4"});
    private final JLabel count = new JLabel(" ");
    private final Timer refreshTimer;

    PriceChangesPanel(PriceMonitoringTaskService service, HistoryOpener openHistory) {
        super(new BorderLayout(8, 8));
        this.service = service;
        this.openHistory = openHistory;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filters.add(new JLabel("Поиск товара:"));
        search.setToolTipText("Артикул, название, штрихкод или полный скан (barcode=… / Честный знак)");
        filters.add(search);
        filters.add(new JLabel("Тип цены:"));
        filters.add(priceType);
        filters.add(new JLabel("Номер:"));
        filters.add(priceNumber);
        add(filters, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setToolTipText("Нажмите на цену «Было» или «Стало», чтобы открыть полную историю цен артикула");
        table.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                int row = table.rowAtPoint(event.getPoint());
                int column = table.columnAtPoint(event.getPoint());
                if (row < 0 || (column != 5 && column != 6)) return;
                ChangeRow change = (ChangeRow) model.getValueAt(row, 0);
                if (change != null) openHistory.open(change.marking(), change.name(), change.barcode());
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(4, 4));
        footer.add(count, BorderLayout.NORTH);
        JLabel path = new JLabel("База истории: " + service.databasePath());
        path.setToolTipText(service.databasePath().toString());
        footer.add(path, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { refresh(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { refresh(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { refresh(); }
        });
        priceType.addActionListener(event -> refresh());
        priceNumber.addActionListener(event -> refresh());
        refresh();
        refreshTimer = new Timer(10000, event -> refresh());
        refreshTimer.start();
    }

    private void refresh() {
        try {
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            String barcodeQuery = BarInput.barcode(query).orElse("").toLowerCase(Locale.ROOT);
            PriceFilter filter = (PriceFilter) priceType.getSelectedItem();
            int selectedNumber = priceNumber.getSelectedIndex();
            List<ChangeRow> visible = new ArrayList<>();
            List<PriceMonitoringTaskService.PriceChange> changes = service.recentChanges(1000);
            for (var change : changes) {
                String searchable = (change.marking() + " " + change.name() + " " + change.barcode()).toLowerCase(Locale.ROOT);
                if (!query.isEmpty() && !searchable.contains(query)
                        && (barcodeQuery.isEmpty() || !change.barcode().toLowerCase(Locale.ROOT).contains(barcodeQuery))) continue;
                appendChangedPrices(visible, change, readJson(change.oldPricesJson()), readJson(change.newPricesJson()),
                        "Обычная (pricesMap)", "pricesMap", filter, selectedNumber);
                appendChangedPrices(visible, change, readJson(change.oldPricesJson()), readJson(change.newPricesJson()),
                        "99 (prices99Map)", "prices99Map", filter, selectedNumber);
            }
            visible.sort((left, right) -> Double.compare(right.detectedAt(), left.detectedAt()));
            model.setRowCount(0);
            for (ChangeRow entry : visible) model.addRow(new Object[]{entry, entry.marking(),
                    entry.name(), entry.barcode(), entry.priceType(), entry.oldValue(), entry.newValue()});
            count.setText("Показано изменений цен: " + visible.size()
                    + (changes.size() >= 1000 ? " · просмотрены последние 1000 событий" : ""));
        } catch (SQLException error) { count.setText("Не удалось загрузить историю: " + error.getMessage()); }
    }

    private static final class ChangeRow {
        private final double detectedAt;
        private final String marking, name, barcode, priceType, oldValue, newValue;

        private ChangeRow(double detectedAt, String marking, String name, String barcode,
                          String priceType, String oldValue, String newValue) {
            this.detectedAt = detectedAt; this.marking = marking; this.name = name; this.barcode = barcode;
            this.priceType = priceType; this.oldValue = oldValue; this.newValue = newValue;
        }

        String marking() { return marking; }
        String name() { return name; }
        String barcode() { return barcode; }
        String priceType() { return priceType; }
        String oldValue() { return oldValue; }
        String newValue() { return newValue; }
        double detectedAt() { return detectedAt; }
        @Override public String toString() { return formatEpoch(detectedAt); }
    }

    private static void appendChangedPrices(List<ChangeRow> output, PriceMonitoringTaskService.PriceChange change,
                                             JsonNode oldPrices, JsonNode newPrices, String label, String mapName,
                                             PriceFilter filter, int selectedNumber) {
        if (filter != PriceFilter.ALL && !filter.mapName.equals(mapName)) return;
        JsonNode oldMap = oldPrices.path(mapName), newMap = newPrices.path(mapName);
        java.util.TreeSet<String> numbers = new java.util.TreeSet<>();
        if (oldMap.isObject()) oldMap.fieldNames().forEachRemaining(numbers::add);
        if (newMap.isObject()) newMap.fieldNames().forEachRemaining(numbers::add);
        for (String number : numbers) {
            if (selectedNumber > 0 && !Integer.toString(selectedNumber).equals(number)) continue;
            JsonNode oldNode = oldMap.get(number), newNode = newMap.get(number);
            String oldValue = oldNode == null ? "—" : oldNode.asText();
            String newValue = newNode == null ? "—" : newNode.asText();
            if (!oldValue.equals(newValue)) output.add(new ChangeRow(change.detectedAt(), change.marking(), change.name(),
                    change.barcode(), label + " · #" + number, oldValue, newValue));
        }
    }

    private static JsonNode readJson(String value) {
        try { return JSON.readTree(value == null ? "{}" : value); }
        catch (Exception ignored) { return JSON.createObjectNode(); }
    }

    private static String formatEpoch(double value) {
        return Instant.ofEpochMilli((long) (value * 1000)).atZone(ZoneId.systemDefault()).format(CHANGE_TIME);
    }

    void close() { refreshTimer.stop(); }

    private enum PriceFilter {
        ALL("Все", null), NORMAL("Обычная", "pricesMap"), PROMO("99", "prices99Map");
        private final String label;
        private final String mapName;
        PriceFilter(String label, String mapName) { this.label = label; this.mapName = mapName; }
        @Override public String toString() { return label; }
    }
    @FunctionalInterface interface HistoryOpener {
        void open(String marking, String name, String barcode);
    }
}
