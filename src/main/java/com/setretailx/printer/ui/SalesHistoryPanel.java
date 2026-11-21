package com.setretailx.printer.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setretailx.printer.service.PriceMonitoringTaskService;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;

/** Daily sales totals and receipt lines for one product article. */
@SuppressWarnings("serial")
final class SalesHistoryPanel extends JPanel {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final NumberFormat NUMBER = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"));
    private static final NumberFormat MONEY = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("ru-RU"));

    private final PriceMonitoringTaskService service;
    private final String marking;
    private final DefaultTableModel daysModel = model("День", "Продано", "Выручка", "Чеков");
    private final DefaultTableModel positionsModel = model("Дата чека", "Дата позиции", "Чек", "Название в чеке", "Кол-во", "Цена", "Стоимость", "Скидка");
    private final JLabel status = new JLabel("Загружаем историю продаж…");
    private final SalesChart chart = new SalesChart();
    private List<PriceMonitoringTaskService.SalesDay> days = List.of();
    private List<PriceMarker> priceMarkers = List.of();
    private long loadGeneration;

    SalesHistoryPanel(PriceMonitoringTaskService service, String marking, String name, String barcode) {
        super(new BorderLayout(8, 8));
        this.service = service;
        this.marking = marking;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel header = new JPanel(new BorderLayout(4, 4));
        JButton refresh = new JButton("Обновить");
        refresh.addActionListener(event -> load());
        header.add(refresh, BorderLayout.EAST);
        header.add(new JLabel("История формируется из всех позиций чеков; фоновое обновление — каждый час."), BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        JTable daysTable = new JTable(daysModel);
        daysTable.setFillsViewportHeight(true);
        daysTable.setAutoCreateRowSorter(true);
        JTable positionsTable = new JTable(positionsModel);
        positionsTable.setFillsViewportHeight(true);
        positionsTable.setAutoCreateRowSorter(true);
        JSplitPane dayContent = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chart, new JScrollPane(daysTable));
        dayContent.setResizeWeight(.56);
        dayContent.setDividerLocation(170);
        dayContent.setContinuousLayout(true);
        JSplitPane allContent = new JSplitPane(JSplitPane.VERTICAL_SPLIT, dayContent, new JScrollPane(positionsTable));
        allContent.setResizeWeight(.55);
        allContent.setDividerLocation(280);
        allContent.setContinuousLayout(true);
        add(allContent, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        load();
    }

    private void load() {
        long generation = ++loadGeneration;
        new SwingWorker<LoadedSales, Void>() {
            @Override protected LoadedSales doInBackground() throws Exception {
                List<PriceMonitoringTaskService.PriceSnapshot> snapshots = service.priceHistory(marking);
                return new LoadedSales(service.salesHistory(marking), service.salesPositions(marking),
                        buildPriceMarkers(snapshots));
            }

            @Override protected void done() {
                if (generation != loadGeneration) return;
                try {
                    LoadedSales loaded = get();
                    days = loaded.days();
                    priceMarkers = loaded.priceMarkers();
                    daysModel.setRowCount(0);
                    for (var day : days) {
                        daysModel.addRow(new Object[]{Instant.ofEpochSecond(day.dayEpochSeconds())
                                .atZone(ZoneId.of("UTC")).format(DAY), quantity(day.units()),
                                MONEY.format(day.revenue()), day.checks()});
                    }
                    positionsModel.setRowCount(0);
                    for (var position : loaded.positions()) {
                        positionsModel.addRow(new Object[]{Instant.ofEpochMilli((long) (position.soldAt() * 1000))
                                .atZone(ZoneId.systemDefault()).format(TIME), Instant.ofEpochMilli((long) (position.dateCommit() * 1000))
                                .atZone(ZoneId.systemDefault()).format(TIME), position.checkNumber(), position.name(),
                                quantity(position.quantity()) + (position.unit().isBlank() ? "" : " " + position.unit()),
                                MONEY.format(position.price()), MONEY.format(position.cost()), MONEY.format(position.discount())});
                    }
                    chart.repaint();
                    if (days.isEmpty()) status.setText("История пока пуста. Первичная загрузка чеков за последние 6 дней выполняется фоновой задачей.");
                    else status.setText("Дней с продажами: " + days.size() + " · изменений цен на графике: " + priceMarkers.size()
                            + " · строк чеков: " + loaded.positions().size()
                            + (loaded.positions().size() == 2000 ? " (показаны последние 2000)" : ""));
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    status.setText("Не удалось загрузить историю продаж: "
                            + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
                }
            }
        }.execute();
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }

    private static String quantity(double value) {
        NUMBER.setMaximumFractionDigits(3);
        return NUMBER.format(value);
    }

    private static List<PriceMarker> buildPriceMarkers(List<PriceMonitoringTaskService.PriceSnapshot> snapshots) {
        List<PriceMonitoringTaskService.PriceSnapshot> chronological = new ArrayList<>(snapshots);
        chronological.sort(Comparator.comparingDouble(PriceMonitoringTaskService.PriceSnapshot::capturedAt));
        List<PriceMarker> result = new ArrayList<>();
        String previous = null;
        for (var snapshot : chronological) {
            String signature;
            try {
                JsonNode root = JSON.readTree(snapshot.pricesJson() == null ? "{}" : snapshot.pricesJson());
                signature = root.path("pricesMap").path("2").toString() + "|"
                        + root.path("pricesMap").path("4").toString() + "|"
                        + root.path("prices99Map").path("2").toString() + "|"
                        + root.path("prices99Map").path("4").toString();
            } catch (Exception ignored) { continue; }
            if (previous != null && !previous.equals(signature)) {
                long epoch = (long) snapshot.capturedAt();
                java.time.LocalDate date = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDate();
                result.add(new PriceMarker(date.toEpochDay(), date.format(DAY)));
            }
            previous = signature;
        }
        return List.copyOf(result);
    }

    private record LoadedSales(List<PriceMonitoringTaskService.SalesDay> days,
                               List<PriceMonitoringTaskService.SalesPosition> positions,
                               List<PriceMarker> priceMarkers) { }
    private record PriceMarker(long day, String label) { }

    private final class SalesChart extends JPanel {
        private SalesChart() {
            setPreferredSize(new Dimension(300, 170));
            setMinimumSize(new Dimension(240, 110));
            setBorder(BorderFactory.createTitledBorder("Продажи по дням · количество"));
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (days.isEmpty()) return;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int left = 48, right = 14, top = 18, bottom = 34;
                int width = getWidth() - left - right;
                int height = getHeight() - top - bottom;
                if (width <= 0 || height <= 0) return;
                List<PriceMonitoringTaskService.SalesDay> chronological = new ArrayList<>(days);
                chronological.sort(Comparator.comparingLong(PriceMonitoringTaskService.SalesDay::dayEpochSeconds));
                double max = chronological.stream().mapToDouble(PriceMonitoringTaskService.SalesDay::units).max().orElse(1);
                if (max <= 0) max = 1;
                g.setColor(chartForeground());
                g.drawLine(left, top + height, left + width, top + height);
                g.setColor(new Color(55, 155, 245));
                g.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                long first = Math.floorDiv(chronological.get(0).dayEpochSeconds(), 86_400);
                long last = Math.floorDiv(chronological.get(chronological.size() - 1).dayEpochSeconds(), 86_400);
                long span = Math.max(1, last - first);
                int priorX = -1, priorY = -1;
                for (PriceMonitoringTaskService.SalesDay value : chronological) {
                    long day = Math.floorDiv(value.dayEpochSeconds(), 86_400);
                    int x = left + (int) Math.round((double) (day - first) / span * width);
                    int y = top + height - (int) Math.round(height * value.units() / max);
                    if (priorX >= 0) g.drawLine(priorX, priorY, x, y);
                    g.fillOval(x - 3, y - 3, 6, 6);
                    priorX = x;
                    priorY = y;
                }
                g.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT,
                        java.awt.BasicStroke.JOIN_MITER, 10f, new float[]{4f, 3f}, 0f));
                List<PriceMarker> visibleMarkers = priceMarkers.stream()
                        .filter(marker -> marker.day() >= first && marker.day() <= last).toList();
                for (PriceMarker marker : visibleMarkers) {
                    int x = left + (int) Math.round((double) (marker.day() - first) / span * width);
                    g.setColor(new Color(220, 76, 70));
                    g.drawLine(x, top, x, top + height);
                }
                g.setColor(chartForeground());
                int tickCount = Math.min(5, chronological.size());
                for (int tick = 0; tick < tickCount; tick++) {
                    int index = tickCount == 1 ? 0 : (int) Math.round((double) tick * (chronological.size() - 1) / (tickCount - 1));
                    var value = chronological.get(index);
                    long day = Math.floorDiv(value.dayEpochSeconds(), 86_400);
                    int x = left + (int) Math.round((double) (day - first) / span * width);
                    String label = Instant.ofEpochSecond(value.dayEpochSeconds()).atZone(ZoneId.of("UTC")).format(DAY);
                    g.drawString(label, Math.max(left, Math.min(left + width - g.getFontMetrics().stringWidth(label), x - 20)), top + height + 18);
                }
                g.setColor(new Color(55, 155, 245));
                g.drawLine(left, 10, left + 14, 10);
                g.setColor(chartForeground());
                g.drawString("Продажи, шт.", left + 19, 13);
                g.setColor(new Color(220, 76, 70));
                g.drawLine(left + 118, 10, left + 132, 10);
                g.setColor(chartForeground());
                g.drawString("Изменение цены", left + 137, 13);
            } finally { g.dispose(); }
        }

        private Color chartForeground() {
            Color foreground = UIManager.getColor("Label.foreground");
            return foreground == null ? getForeground() : foreground;
        }
    }
}
