package com.setretailx.printer.ui;

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

/** Daily sales totals and receipt lines for one product article. */
@SuppressWarnings("serial")
final class SalesHistoryPanel extends JPanel {
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
            @Override protected LoadedSales doInBackground() throws SQLException {
                return new LoadedSales(service.salesHistory(marking), service.salesPositions(marking));
            }

            @Override protected void done() {
                if (generation != loadGeneration) return;
                try {
                    LoadedSales loaded = get();
                    days = loaded.days();
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
                    else status.setText("Дней с продажами: " + days.size() + " · строк чеков: " + loaded.positions().size()
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

    private record LoadedSales(List<PriceMonitoringTaskService.SalesDay> days,
                               List<PriceMonitoringTaskService.SalesPosition> positions) { }

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
                int left = 42, right = 12, top = 14, bottom = 24;
                int width = getWidth() - left - right;
                int height = getHeight() - top - bottom;
                double max = days.stream().mapToDouble(PriceMonitoringTaskService.SalesDay::units).max().orElse(1);
                if (max <= 0) max = 1;
                g.setColor(chartForeground());
                g.drawLine(left, top + height, left + width, top + height);
                int count = Math.min(days.size(), Math.max(1, width / 30));
                double slot = (double) width / count;
                for (int i = 0; i < count; i++) {
                    var value = days.get(count - i - 1);
                    int barHeight = (int) Math.round(height * value.units() / max);
                    int barWidth = Math.max(4, (int) (slot * .62));
                    int x = left + (int) (i * slot + (slot - barWidth) / 2);
                    int y = top + height - barHeight;
                    g.setColor(new Color(55, 155, 245));
                    g.fillRoundRect(x, y, barWidth, barHeight, 5, 5);
                    g.setColor(chartForeground());
                    String label = Instant.ofEpochSecond(value.dayEpochSeconds()).atZone(ZoneId.of("UTC")).format(DAY);
                    if (count <= 12) g.drawString(label, x - 8, top + height + 16);
                }
            } finally { g.dispose(); }
        }

        private Color chartForeground() {
            Color foreground = UIManager.getColor("Label.foreground");
            return foreground == null ? getForeground() : foreground;
        }
    }
}
