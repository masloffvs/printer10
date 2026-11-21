package com.setretailx.printer.ui;

import com.setretailx.printer.service.PriceMonitoringTaskService.SalesPointPriceChange;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Explicit per-event processing view for price changes on a sales point. */
@SuppressWarnings("serial")
final class SalesPointChangesPanel extends JPanel {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final int SELECT = 0;

    private final Consumer<List<SalesPointPriceChange>> processSelected;
    private final Runnable refreshAction;
    private final JLabel heading = new JLabel("Выберите точку на карте.");
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{
            "Обработать", "Статус", "Время события", "Артикул", "Товар", "Было · обычная / акция", "Стало · обычная / акция", "ID"
    }, 0) {
        @Override public boolean isCellEditable(int row, int column) {
            return column == SELECT && "Не обработано".equals(getValueAt(row, 1));
        }
        @Override public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == SELECT ? Boolean.class : String.class;
        }
    };
    private final JTable table = new JTable(model);
    private List<SalesPointPriceChange> changes = List.of();
    private long pointId = -1;

    SalesPointChangesPanel(Consumer<List<SalesPointPriceChange>> processSelected, Runnable refreshAction) {
        super(new BorderLayout(8, 8));
        this.processSelected = processSelected == null ? ignored -> { } : processSelected;
        this.refreshAction = refreshAction == null ? () -> { } : refreshAction;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JButton refresh = new JButton("Обновить");
        refresh.addActionListener(event -> this.refreshAction.run());
        JButton process = new JButton("Обработать выбранные события");
        process.addActionListener(event -> this.processSelected.accept(selectedChanges()));
        process.putClientProperty("JButton.buttonType", "default");
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING));
        actions.add(refresh);
        actions.add(process);

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.add(heading, BorderLayout.CENTER);
        top.add(actions, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(76);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);
        table.getColumnModel().getColumn(2).setPreferredWidth(145);
        table.getColumnModel().getColumn(3).setPreferredWidth(115);
        table.getColumnModel().getColumn(4).setPreferredWidth(210);
        table.getColumnModel().getColumn(5).setPreferredWidth(150);
        table.getColumnModel().getColumn(6).setPreferredWidth(150);
        table.getColumnModel().getColumn(7).setMinWidth(0);
        table.getColumnModel().getColumn(7).setMaxWidth(0);
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    void setPoint(long id, String name) {
        pointId = id;
        heading.setText(id < 0 ? "Выберите точку на карте."
                : "События изменения цен за последние 24 часа · " + name);
        setChanges(List.of());
    }

    long pointId() { return pointId; }

    void setChanges(List<SalesPointPriceChange> value) {
        changes = value == null ? List.of() : List.copyOf(value);
        model.setRowCount(0);
        for (SalesPointPriceChange change : changes) {
            String status = change.processedAt() == null ? "Не обработано"
                    : "Обработано · " + DATE.format(Instant.ofEpochMilli(change.processedAt().longValue()));
            model.addRow(new Object[]{change.processedAt() == null, status,
                    DATE.format(Instant.ofEpochMilli((long) (change.detectedAt() * 1000))),
                    change.marking(), change.pointItemName().isBlank() ? change.name() : change.pointItemName(),
                    prices(change.oldPricesJson()), prices(change.newPricesJson()), String.valueOf(change.id())});
        }
        heading.setText(pointId < 0 ? "Выберите точку на карте."
                : "События изменения цен за последние 24 часа · " + changes.size() + " событий");
    }

    private List<SalesPointPriceChange> selectedChanges() {
        List<SalesPointPriceChange> selected = new ArrayList<>();
        for (int viewRow = 0; viewRow < table.getRowCount(); viewRow++) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            if (!Boolean.TRUE.equals(model.getValueAt(modelRow, SELECT))) continue;
            if (modelRow >= 0 && modelRow < changes.size()) selected.add(changes.get(modelRow));
        }
        if (selected.isEmpty()) heading.setText("Отметьте флажками события, которые нужно обработать.");
        return List.copyOf(selected);
    }

    private static String prices(String json) {
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json == null ? "{}" : json);
            var normal = root.path("pricesMap");
            return price(normal.path("2")) + " / " + price(normal.path("4"));
        } catch (Exception ignored) { return "— / —"; }
    }

    private static String price(com.fasterxml.jackson.databind.JsonNode value) {
        if (value.isObject()) value = value.path("value");
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) return "—";
        try {
            return new java.math.BigDecimal(value.asText()).stripTrailingZeros().toPlainString().replace('.', ',');
        } catch (NumberFormatException ignored) { return "—"; }
    }
}
