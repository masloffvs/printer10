package com.setretailx.printer.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setretailx.printer.service.PriceMonitoringTaskService;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterJob;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Full timeline of prices observed for one product. */
@SuppressWarnings("serial")
final class PriceHistoryPanel extends JPanel {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private final PriceMonitoringTaskService service;
    private final String marking;
    private final String name;
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Когда", "Источник", "Цена до · без скидки / со скидкой",
                    "Цена после · без скидки / со скидкой", "Изменение · без скидки / со скидкой"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JComboBox<SourceFilter> sourceFilter = new JComboBox<>(SourceFilter.values());
    private final JComboBox<PriceKind> kindFilter = new JComboBox<>(PriceKind.values());
    private final PriceChart chart = new PriceChart();
    private List<HistoryRow> historyRows = List.of();
    private List<PriceMonitoringTaskService.PriceSnapshot> snapshots = List.of();
    private List<ChartPoint> chartPoints = List.of();
    private final JLabel status = new JLabel("Загружаем историю цен…");

    PriceHistoryPanel(PriceMonitoringTaskService service, String marking, String name, String barcode) {
        super(new BorderLayout(8, 8));
        this.service = service;
        this.marking = marking;
        this.name = name == null || name.isBlank() ? "Без названия" : name;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JLabel product = new JLabel("Артикул: " + marking + " · " + this.name
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
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        installHistoryContextMenu();
        JSplitPane content = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chart, new JScrollPane(table));
        content.setResizeWeight(0.36);
        content.setDividerLocation(190);
        content.setContinuousLayout(true);
        add(content, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        sourceFilter.addActionListener(event -> { renderRows(); chart.repaint(); });
        kindFilter.addActionListener(event -> { renderRows(); chart.repaint(); });
        load();
    }

    private void installHistoryContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem print = new JMenuItem("Печать выделенных событий…");
        print.addActionListener(event -> printSelectedRows());
        menu.add(print);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            private void selectPopupRow(java.awt.event.MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                int row = table.rowAtPoint(event.getPoint());
                if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
                print.setEnabled(table.getSelectedRowCount() > 0);
                menu.show(table, event.getX(), event.getY());
            }
            @Override public void mousePressed(java.awt.event.MouseEvent event) { selectPopupRow(event); }
            @Override public void mouseReleased(java.awt.event.MouseEvent event) { selectPopupRow(event); }
        });
    }

    private void load() {
        new SwingWorker<LoadedHistory, Void>() {
            @Override protected LoadedHistory doInBackground() throws Exception {
                List<PriceMonitoringTaskService.PriceSnapshot> loaded = service.priceHistory(marking);
                return new LoadedHistory(loaded, buildRows(loaded), buildChartPoints(loaded));
            }

            @Override protected void done() {
                try {
                    LoadedHistory loaded = get();
                    snapshots = loaded.snapshots();
                    historyRows = loaded.rows();
                    chartPoints = loaded.chartPoints();
                    renderRows();
                    chart.repaint();
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
            model.addRow(new Object[]{row, row.source().label,
                    priceCell(selectedKind, "Без скидки", row.beforeRegular(), "Со скидкой", row.beforePromo()),
                    priceCell(selectedKind, "Без скидки", row.afterRegular(), "Со скидкой", row.afterPromo()),
                    priceCell(selectedKind, "Без скидки", row.regularChange(), "Со скидкой", row.promoChange())});
            shown++;
        }
        status.setText("Показано строк: " + shown + " · снимков с изменением: " + historyRows.size());
    }

    private void printSelectedRows() {
        int[] selected = table.getSelectedRows();
        if (selected.length == 0) return;
        List<HistoryRow> rows = new ArrayList<>(selected.length);
        for (int viewRow : selected) {
            Object value = model.getValueAt(table.convertRowIndexToModel(viewRow), 0);
            if (value instanceof HistoryRow row) rows.add(row);
        }
        if (rows.isEmpty()) return;
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("История цен · " + marking + " · " + rows.size() + " строк");
        PageFormat page = job.defaultPage();
        Paper paper = new Paper();
        double width = 210 / 25.4 * 72, height = 297 / 25.4 * 72, margin = 14 / 25.4 * 72;
        paper.setSize(width, height);
        paper.setImageableArea(margin, margin, width - 2 * margin, height - 2 * margin);
        page.setPaper(paper);
        page.setOrientation(PageFormat.LANDSCAPE);
        job.setPrintable(new HistoryPrintable(marking, name, rows, (PriceKind) kindFilter.getSelectedItem()), page);
        if (!job.printDialog()) return;
        try {
            job.print();
            status.setText("На печать отправлено выделенных строк: " + rows.size());
        } catch (Exception error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            status.setText("Не удалось напечатать историю цен: " + cause.getMessage());
        }
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
                              double capturedAt) {
        @Override public String toString() { return time; }
    }

    private final class PriceChart extends JPanel {
        private PriceChart() {
            setBorder(BorderFactory.createTitledBorder("Обзор изменения цен по снимкам"));
            setPreferredSize(new java.awt.Dimension(500, 190));
            setMinimumSize(new java.awt.Dimension(250, 120));
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int left = 58, right = getWidth() - 18, top = 52, bottom = getHeight() - 34;
                if (right <= left || bottom <= top) return;
                List<ChartPoint> points = chartPoints;
                List<ChartSeries> series = chartSeries(points);
                List<BigDecimal> values = new ArrayList<>();
                for (ChartSeries line : series) for (BigDecimal value : line.values()) if (value != null) values.add(value);
                if (points.size() < 2 || values.isEmpty()) {
                    g.setColor(chartForeground());
                    g.drawString("Недостаточно снимков цен для построения графика", left, top + 35);
                    return;
                }
                BigDecimal min = values.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
                BigDecimal max = values.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
                double minValue = min.doubleValue(), maxValue = max.doubleValue();
                if (maxValue == minValue) { minValue -= 1; maxValue += 1; }
                double padding = (maxValue - minValue) * .08;
                minValue = Math.max(0, minValue - padding);
                maxValue += padding;
                g.setFont(new Font("DejaVu Sans", Font.PLAIN, 9));
                g.setStroke(new java.awt.BasicStroke(.7f));
                for (int tick = 0; tick <= 4; tick++) {
                    int y = bottom - (bottom - top) * tick / 4;
                    double value = minValue + (maxValue - minValue) * tick / 4;
                    g.setColor(chartGrid());
                    g.drawLine(left, y, right, y);
                    g.setColor(chartForeground());
                    g.drawString(BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP)
                            .stripTrailingZeros().toPlainString().replace('.', ','), 4, y + 3);
                }
                long firstTime = points.get(0).capturedAt();
                long lastTime = points.get(points.size() - 1).capturedAt();
                for (int tick = 0; tick <= 2; tick++) {
                    int x = left + (right - left) * tick / 2;
                    long time = firstTime + (lastTime - firstTime) * tick / 2;
                    g.setColor(chartForeground());
                    String label = Instant.ofEpochSecond(time).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("dd.MM HH:mm"));
                    g.drawString(label, Math.max(left, Math.min(right - 74, x - 28)), getHeight() - 9);
                }
                Color[] colors = {new Color(50, 150, 255), new Color(255, 166, 64),
                        new Color(69, 190, 130), new Color(209, 112, 235)};
                for (int s = 0; s < series.size(); s++) {
                    ChartSeries line = series.get(s);
                    g.setColor(colors[s % colors.length]);
                    g.setStroke(new java.awt.BasicStroke(s % 2 == 0 ? 1.8f : 1.5f,
                            java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND,
                            10, s % 2 == 0 ? null : new float[]{4, 3}, 0));
                    int previousX = -1, previousY = -1;
                    for (int i = 0; i < points.size(); i++) {
                        BigDecimal value = line.values().get(i);
                        if (value == null) { previousX = previousY = -1; continue; }
                        int x = left + (int) Math.round((double) (points.get(i).capturedAt() - firstTime)
                                / Math.max(1, lastTime - firstTime) * (right - left));
                        int y = bottom - (int) Math.round((value.doubleValue() - minValue)
                                / (maxValue - minValue) * (bottom - top));
                        if (previousX >= 0) g.drawLine(previousX, previousY, x, y);
                        g.fillOval(x - 2, y - 2, 4, 4);
                        previousX = x;
                        previousY = y;
                    }
                    String label = line.label();
                    g.setFont(new Font("DejaVu Sans", Font.PLAIN, 8));
                    int columnWidth = Math.max(1, (right - left) / 2);
                    int labelX = left + (s % 2) * columnWidth;
                    int labelY = 27 + (s / 2) * 12;
                    label = clipLegendLabel(g, label, columnWidth - 21);
                    g.drawLine(labelX, labelY - 3, labelX + 13, labelY - 3);
                    g.drawString(label, labelX + 17, labelY);
                }
            } finally { g.dispose(); }
        }

        private List<ChartSeries> chartSeries(List<ChartPoint> points) {
            SourceFilter selectedSource = (SourceFilter) sourceFilter.getSelectedItem();
            PriceKind selectedKind = (PriceKind) kindFilter.getSelectedItem();
            List<ChartSeries> result = new ArrayList<>();
            if (selectedSource != SourceFilter.PROMO && selectedKind != PriceKind.PROMO) {
                result.add(new ChartSeries("Обычная · price2", points.stream().map(ChartPoint::normalRegular).toList()));
            }
            if (selectedSource != SourceFilter.PROMO && selectedKind != PriceKind.REGULAR) {
                result.add(new ChartSeries("Обычная · price4", points.stream().map(ChartPoint::normalPromo).toList()));
            }
            if (selectedSource != SourceFilter.NORMAL && selectedKind != PriceKind.PROMO) {
                result.add(new ChartSeries("99 · price2", points.stream().map(ChartPoint::promoRegular).toList()));
            }
            if (selectedSource != SourceFilter.NORMAL && selectedKind != PriceKind.REGULAR) {
                result.add(new ChartSeries("99 · price4", points.stream().map(ChartPoint::promoPromo).toList()));
            }
            return result;
        }

        private String clipLegendLabel(Graphics2D g, String label, int maxWidth) {
            if (g.getFontMetrics().stringWidth(label) <= maxWidth) return label;
            while (!label.isEmpty() && g.getFontMetrics().stringWidth(label + "…") > maxWidth) {
                label = label.substring(0, label.length() - 1);
            }
            return label.isEmpty() ? "" : label + "…";
        }
    }

    private Color chartForeground() {
        Color foreground = UIManager.getColor("Label.foreground");
        return foreground == null ? getForeground() : foreground;
    }

    private Color chartGrid() {
        Color background = UIManager.getColor("Panel.background");
        Color foreground = chartForeground();
        if (background == null) background = getBackground();
        return mix(background, foreground, .24f);
    }

    private static Color mix(Color base, Color other, float amount) {
        if (base == null) base = Color.GRAY;
        return new Color(Math.round(base.getRed() * (1 - amount) + other.getRed() * amount),
                Math.round(base.getGreen() * (1 - amount) + other.getGreen() * amount),
                Math.round(base.getBlue() * (1 - amount) + other.getBlue() * amount));
    }

    private static BigDecimal readChartValue(JsonNode node) {
        String display = displayPrice(node);
        if ("—".equals(display)) return null;
        try { return new BigDecimal(display.replace(',', '.')); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static List<ChartPoint> buildChartPoints(List<PriceMonitoringTaskService.PriceSnapshot> snapshots) {
        List<PriceMonitoringTaskService.PriceSnapshot> ordered = new ArrayList<>(snapshots);
        ordered.sort(Comparator.comparingDouble(PriceMonitoringTaskService.PriceSnapshot::capturedAt));
        List<ChartPoint> result = new ArrayList<>(Math.min(240, ordered.size()));
        int stride = Math.max(1, (int) Math.ceil(ordered.size() / 240.0));
        for (int i = 0; i < ordered.size(); i += stride) {
            var snapshot = ordered.get(i);
            JsonNode root;
            try { root = JSON.readTree(snapshot.pricesJson() == null ? "{}" : snapshot.pricesJson()); }
            catch (Exception ignored) { continue; }
            result.add(new ChartPoint((long) snapshot.capturedAt(),
                    readChartValue(root.path("pricesMap").path("2")),
                    readChartValue(root.path("pricesMap").path("4")),
                    readChartValue(root.path("prices99Map").path("2")),
                    readChartValue(root.path("prices99Map").path("4"))));
        }
        if (!ordered.isEmpty() && (ordered.size() - 1) % stride != 0) {
            var snapshot = ordered.get(ordered.size() - 1);
            try {
                JsonNode root = JSON.readTree(snapshot.pricesJson() == null ? "{}" : snapshot.pricesJson());
                result.add(new ChartPoint((long) snapshot.capturedAt(),
                        readChartValue(root.path("pricesMap").path("2")),
                        readChartValue(root.path("pricesMap").path("4")),
                        readChartValue(root.path("prices99Map").path("2")),
                        readChartValue(root.path("prices99Map").path("4"))));
            } catch (Exception ignored) { }
        }
        return List.copyOf(result);
    }

    private record LoadedHistory(List<PriceMonitoringTaskService.PriceSnapshot> snapshots,
                                 List<HistoryRow> rows, List<ChartPoint> chartPoints) { }

    private record ChartPoint(long capturedAt, BigDecimal normalRegular, BigDecimal normalPromo,
                              BigDecimal promoRegular, BigDecimal promoPromo) { }
    private record ChartSeries(String label, List<BigDecimal> values) { }

    private static final class HistoryPrintable implements Printable {
        private static final int HEADER_HEIGHT = 78, TABLE_HEADER = 23, ROW_HEIGHT = 25, FOOTER = 24;
        private final String marking, name;
        private final List<HistoryRow> rows;
        private final PriceKind kind;

        private HistoryPrintable(String marking, String name, List<HistoryRow> rows, PriceKind kind) {
            this.marking = marking;
            this.name = name;
            this.rows = List.copyOf(rows);
            this.kind = kind == null ? PriceKind.BOTH : kind;
        }

        @Override public int print(java.awt.Graphics graphics, PageFormat page, int pageIndex) {
            double width = page.getImageableWidth(), height = page.getImageableHeight();
            int capacity = Math.max(1, (int) ((height - HEADER_HEIGHT - TABLE_HEADER - FOOTER) / ROW_HEIGHT));
            int pages = (rows.size() + capacity - 1) / capacity;
            if (pageIndex >= pages) return NO_SUCH_PAGE;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(page.getImageableX(), page.getImageableY());
                g.setColor(Color.BLACK);
                g.setFont(new Font("DejaVu Sans", Font.BOLD, 15));
                g.drawString("ИСТОРИЯ ИЗМЕНЕНИЯ ЦЕН", 0, 17);
                g.setFont(new Font("DejaVu Sans", Font.PLAIN, 9));
                g.drawString("Артикул: " + marking + " · " + name, 0, 34);
                g.setFont(new Font("DejaVu Sans", Font.PLAIN, 8));
                g.drawString("Выделено строк: " + rows.size() + " · " + kind.label + " · "
                        + java.time.LocalDate.now(), 0, 49);
                g.setStroke(new java.awt.BasicStroke(1.5f));
                g.drawLine(0, 59, (int) width, 59);
                double[] x = {0, width * .23, width * .38, width * .59, width * .80};
                double[] w = {width * .23, width * .15, width * .21, width * .21, width * .20};
                g.setColor(new Color(217, 217, 217));
                g.fillRect(0, HEADER_HEIGHT, (int) width, TABLE_HEADER);
                g.setFont(new Font("DejaVu Sans", Font.BOLD, 8));
                g.setColor(Color.BLACK);
                String[] labels = {"КОГДА", "ИСТОЧНИК", "ЦЕНА ДО", "ЦЕНА ПОСЛЕ", "ИЗМЕНЕНИЕ"};
                for (int col = 0; col < labels.length; col++) g.drawString(labels[col], (int) x[col] + 4, HEADER_HEIGHT + 15);
                int start = pageIndex * capacity, end = Math.min(rows.size(), start + capacity);
                for (int index = start; index < end; index++) {
                    HistoryRow row = rows.get(index);
                    int y = HEADER_HEIGHT + TABLE_HEADER + (index - start) * ROW_HEIGHT;
                    String before = printPriceCell(kind, row.beforeRegular(), row.beforePromo());
                    String after = printPriceCell(kind, row.afterRegular(), row.afterPromo());
                    String delta = printPriceCell(kind, row.regularChange(), row.promoChange());
                    String[] cells = {row.time(), row.source().label, before, after, delta};
                    g.setFont(new Font("DejaVu Sans", Font.PLAIN, 8));
                    g.setColor(Color.BLACK);
                    for (int col = 0; col < cells.length; col++) {
                        String text = clip(g, cells[col], w[col] - 8);
                        g.drawString(text, (int) x[col] + 4, y + 16);
                    }
                    g.setColor(new Color(187, 187, 187));
                    g.drawLine(0, y + ROW_HEIGHT, (int) width, y + ROW_HEIGHT);
                }
                int footerY = (int) height - FOOTER;
                g.setColor(new Color(187, 187, 187));
                g.drawLine(0, footerY, (int) width, footerY);
                g.setColor(new Color(51, 51, 51));
                g.setFont(new Font("DejaVu Sans", Font.PLAIN, 7));
                g.drawString("Источник: история снимков цен · Обычная цена — pricesMap · Цена 99 — prices99Map",
                        0, footerY + 11);
                String pageText = "Лист " + (pageIndex + 1) + " / " + pages;
                g.drawString(pageText, (int) (width - g.getFontMetrics().stringWidth(pageText)), footerY + 20);
                return PAGE_EXISTS;
            } finally { g.dispose(); }
        }

        private static String clip(Graphics2D g, String value, double width) {
            String text = value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ");
            if (g.getFontMetrics().stringWidth(text) <= width) return text;
            while (!text.isEmpty() && g.getFontMetrics().stringWidth(text + "…") > width)
                text = text.substring(0, text.length() - 1);
            return text.isEmpty() ? "" : text + "…";
        }

        private static String printPriceCell(PriceKind kind, String regular, String promo) {
            if (kind == PriceKind.REGULAR) return "Без скидки: " + regular;
            if (kind == PriceKind.PROMO) return "Со скидкой: " + promo;
            return "Без скидки " + regular + " · со скидкой " + promo;
        }
    }

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
