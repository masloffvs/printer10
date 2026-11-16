package com.setretailx.printer.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setretailx.printer.service.PriceMonitoringTaskService;
import com.setretailx.printer.model.BarInput;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.FlowLayout;
import java.awt.RenderingHints;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterJob;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Separate MDI view of the detected product price changes. */
@SuppressWarnings("serial")
final class PriceChangesPanel extends JPanel {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter CHANGE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private final PriceMonitoringTaskService service;
    private final HistoryOpener openHistory;
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Когда", "Артикул", "Товар", "Штрихкод", "Ценовая позиция",
                    "Цена до · обычная / 99", "Цена после · обычная / 99", "Изменение · обычная / 99"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JTextField search = new JTextField(18);
    private final JComboBox<PriceFilter> priceType = new JComboBox<>(PriceFilter.values());
    private final JComboBox<String> priceNumber = new JComboBox<>(new String[]{
            "Все позиции", "#1 · обычная", "#2 · до скидки", "#3 · неизвестно", "#4 · со скидкой"});
    private final ChangeOverviewChart chart = new ChangeOverviewChart();
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
        filters.add(new JLabel("Отображение цены:"));
        filters.add(priceType);
        filters.add(new JLabel("Номер:"));
        filters.add(priceNumber);
        add(filters, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.setToolTipText("Щёлкните правой кнопкой по событию, чтобы открыть историю артикула или отправить изменения на печать");
        installChangesContextMenu();
        JSplitPane content = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chart, new JScrollPane(table));
        content.setResizeWeight(.28);
        content.setDividerLocation(155);
        add(content, BorderLayout.CENTER);
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
        priceType.addActionListener(event -> priceNumber.setEnabled(priceType.getSelectedItem() != PriceFilter.FINAL));
        refresh();
        refreshTimer = new Timer(10000, event -> refresh());
        refreshTimer.start();
    }

    private void installChangesContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        ChangeRow[] popupTarget = {null};
        JMenuItem open = new JMenuItem("Открыть историю цен артикула");
        open.addActionListener(event -> {
            ChangeRow row = popupTarget[0];
            if (row != null) openHistory.open(row.marking(), row.name(), row.barcode());
        });
        JMenuItem print = new JMenuItem("Печать выделенных событий…");
        print.addActionListener(event -> printSelectedRows());
        menu.add(open);
        menu.add(print);
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent event) {
                boolean hasSelection = table.getSelectedRowCount() > 0;
                open.setEnabled(hasSelection);
                print.setEnabled(hasSelection);
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent event) { }
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent event) { }
        });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            private void selectPopupRow(java.awt.event.MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                int row = table.rowAtPoint(event.getPoint());
                if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
                popupTarget[0] = row < 0 ? null
                        : (ChangeRow) model.getValueAt(table.convertRowIndexToModel(row), 0);
                open.setEnabled(popupTarget[0] != null);
                print.setEnabled(table.getSelectedRowCount() > 0);
                menu.show(table, event.getX(), event.getY());
            }
            @Override public void mousePressed(java.awt.event.MouseEvent event) { selectPopupRow(event); }
            @Override public void mouseReleased(java.awt.event.MouseEvent event) { selectPopupRow(event); }
        });
    }

    private void refresh() {
        try {
            Set<String> selectedRows = new HashSet<>();
            for (int viewRow : table.getSelectedRows()) {
                Object value = model.getValueAt(table.convertRowIndexToModel(viewRow), 0);
                if (value instanceof ChangeRow row) selectedRows.add(row.selectionKey());
            }
            table.clearSelection();
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            String barcodeQuery = BarInput.barcode(query).orElse("").toLowerCase(Locale.ROOT);
            PriceFilter filter = (PriceFilter) priceType.getSelectedItem();
            int selectedNumber = priceNumber.getSelectedIndex();
            List<ChangeRow> visible = new ArrayList<>();
            List<PriceMonitoringTaskService.PriceChange> changes = service.recentChanges(1000);
            chart.setChanges(changes);
            for (var change : changes) {
                String searchable = (change.marking() + " " + change.name() + " " + change.barcode()).toLowerCase(Locale.ROOT);
                if (!query.isEmpty() && !searchable.contains(query)
                        && (barcodeQuery.isEmpty() || !change.barcode().toLowerCase(Locale.ROOT).contains(barcodeQuery))) continue;
                appendChangedPrices(visible, change, readJson(change.oldPricesJson()), readJson(change.newPricesJson()),
                        filter, selectedNumber);
            }
            visible.sort((left, right) -> Double.compare(right.detectedAt(), left.detectedAt()));
            model.setRowCount(0);
            for (int i = 0; i < visible.size(); i++) {
                ChangeRow entry = visible.get(i);
                model.addRow(new Object[]{entry, entry.marking(), entry.name(), entry.barcode(), entry.priceType(),
                        entry.oldValue(), entry.newValue(), entry.delta()});
                if (selectedRows.contains(entry.selectionKey())) {
                    int viewRow = table.convertRowIndexToView(i);
                    if (viewRow >= 0) table.addRowSelectionInterval(viewRow, viewRow);
                }
            }
            count.setText("Показано изменений цен: " + visible.size()
                    + (changes.size() >= 1000 ? " · просмотрены последние 1000 событий" : ""));
        } catch (SQLException error) { count.setText("Не удалось загрузить историю: " + error.getMessage()); }
    }

    private void printSelectedRows() {
        int[] selected = table.getSelectedRows();
        if (selected.length == 0) return;
        List<ChangeRow> rows = new ArrayList<>(selected.length);
        for (int viewRow : selected) {
            Object value = model.getValueAt(table.convertRowIndexToModel(viewRow), 0);
            if (value instanceof ChangeRow row) rows.add(row);
        }
        if (rows.isEmpty()) return;
        rows.sort((left, right) -> Double.compare(right.detectedAt(), left.detectedAt()));
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("Выбранные изменения цен · " + rows.size() + " строк");
        PageFormat page = job.defaultPage();
        Paper paper = new Paper();
        double width = 210 / 25.4 * 72, height = 297 / 25.4 * 72, margin = 12 / 25.4 * 72;
        paper.setSize(width, height);
        paper.setImageableArea(margin, margin, width - 2 * margin, height - 2 * margin);
        page.setPaper(paper);
        page.setOrientation(PageFormat.LANDSCAPE);
        job.setPrintable(new SelectedChangesPrintable(rows), page);
        if (!job.printDialog()) return;
        try {
            job.print();
            count.setText("На печать отправлено выделенных строк: " + rows.size());
        } catch (Exception error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            count.setText("Не удалось напечатать выбранные изменения: " + cause.getMessage());
        }
    }

    private final class ChangeOverviewChart extends JPanel {
        private List<PriceMonitoringTaskService.PriceChange> changes = List.of();

        private ChangeOverviewChart() {
            setBorder(BorderFactory.createTitledBorder("Обзор изменений цен · последние 7 дней"));
            setPreferredSize(new java.awt.Dimension(600, 155));
            setMinimumSize(new java.awt.Dimension(300, 100));
        }

        private void setChanges(List<PriceMonitoringTaskService.PriceChange> value) {
            changes = List.copyOf(value);
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int left = 45, right = getWidth() - 15, top = 24, bottom = getHeight() - 27;
                if (right <= left || bottom <= top) return;
                LocalDate today = LocalDate.now();
                int[] totals = new int[7];
                ZoneId zone = ZoneId.systemDefault();
                for (var change : changes) {
                    LocalDate day = Instant.ofEpochMilli((long) (change.detectedAt() * 1000)).atZone(zone).toLocalDate();
                    long offset = java.time.temporal.ChronoUnit.DAYS.between(today.minusDays(6), day);
                    if (offset >= 0 && offset < totals.length) totals[(int) offset]++;
                }
                int max = java.util.Arrays.stream(totals).max().orElse(0);
                int scale = Math.max(1, max);
                g.setFont(new Font("DejaVu Sans", Font.PLAIN, 8));
                for (int tick = 0; tick <= 2; tick++) {
                    int y = bottom - (bottom - top) * tick / 2;
                    int value = scale * tick / 2;
                    g.setColor(chartGrid());
                    g.drawLine(left, y, right, y);
                    g.setColor(chartForeground());
                    g.drawString(Integer.toString(value), 8, y + 3);
                }
                int slot = (right - left) / totals.length;
                for (int i = 0; i < totals.length; i++) {
                    int barHeight = (int) Math.round((double) totals[i] / scale * (bottom - top));
                    int barWidth = Math.max(5, Math.min(32, slot - 10));
                    int x = left + i * slot + (slot - barWidth) / 2;
                    int y = bottom - barHeight;
                    g.setColor(new Color(55, 155, 245));
                    g.fillRect(x, y, barWidth, barHeight);
                    g.setFont(new Font("DejaVu Sans", Font.BOLD, 8));
                    String number = Integer.toString(totals[i]);
                    g.setColor(chartForeground());
                    g.drawString(number, x + (barWidth - g.getFontMetrics().stringWidth(number)) / 2, Math.max(top + 9, y - 3));
                    g.setFont(new Font("DejaVu Sans", Font.PLAIN, 8));
                    String day = today.minusDays(6L - i).format(DateTimeFormatter.ofPattern("dd.MM"));
                    g.drawString(day, x + (barWidth - g.getFontMetrics().stringWidth(day)) / 2, getHeight() - 8);
                }
                g.setColor(chartForeground());
                g.drawString("Событий изменения цены", left, 15);
            } finally { g.dispose(); }
        }
    }

    private Color chartForeground() {
        Color foreground = UIManager.getColor("Label.foreground");
        return foreground == null ? getForeground() : foreground;
    }

    private Color chartGrid() {
        Color background = UIManager.getColor("Panel.background");
        if (background == null) background = getBackground();
        Color foreground = chartForeground();
        return new Color(Math.round(background.getRed() * .76f + foreground.getRed() * .24f),
                Math.round(background.getGreen() * .76f + foreground.getGreen() * .24f),
                Math.round(background.getBlue() * .76f + foreground.getBlue() * .24f));
    }

    private static final class SelectedChangesPrintable implements Printable {
        private static final int HEADER = 70, TABLE_HEADER = 22, ROW_HEIGHT = 25, FOOTER = 22;
        private final List<ChangeRow> rows;

        private SelectedChangesPrintable(List<ChangeRow> rows) { this.rows = List.copyOf(rows); }

        @Override public int print(Graphics graphics, PageFormat page, int pageIndex) {
            double width = page.getImageableWidth(), height = page.getImageableHeight();
            int capacity = Math.max(1, (int) ((height - HEADER - TABLE_HEADER - FOOTER) / ROW_HEIGHT));
            int pages = (rows.size() + capacity - 1) / capacity;
            if (pageIndex >= pages) return Printable.NO_SUCH_PAGE;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(page.getImageableX(), page.getImageableY());
                g.setColor(Color.BLACK);
                g.setFont(new Font("DejaVu Sans", Font.BOLD, 15));
                g.drawString("ВЫБРАННЫЕ ИЗМЕНЕНИЯ ЦЕН", 0, 18);
                g.setFont(new Font("DejaVu Sans", Font.PLAIN, 8));
                g.drawString("Строк: " + rows.size() + " · " + LocalDate.now(), 0, 35);
                g.setStroke(new java.awt.BasicStroke(1.5f));
                g.drawLine(0, 47, (int) width, 47);
                double[] x = {0, width * .12, width * .40, width * .55, width * .70, width * .85};
                double[] w = {width * .12, width * .28, width * .15, width * .15, width * .15, width * .15};
                g.setColor(new Color(217, 217, 217));
                g.fillRect(0, HEADER, (int) width, TABLE_HEADER);
                g.setColor(Color.BLACK);
                g.setFont(new Font("DejaVu Sans", Font.BOLD, 8));
                String[] heads = {"КОГДА", "ТОВАР · АРТИКУЛ", "ПОЗИЦИЯ", "ЦЕНА ДО", "ЦЕНА ПОСЛЕ", "ИЗМЕНЕНИЕ"};
                for (int col = 0; col < heads.length; col++) g.drawString(heads[col], (int) x[col] + 3, HEADER + 14);
                int start = pageIndex * capacity, end = Math.min(rows.size(), start + capacity);
                for (int i = start; i < end; i++) {
                    ChangeRow row = rows.get(i);
                    int y = HEADER + TABLE_HEADER + (i - start) * ROW_HEIGHT;
                    String[] cells = {formatEpoch(row.detectedAt()), row.name() + " · " + row.marking(), row.priceType(),
                            row.oldValue(), row.newValue(), row.delta()};
                    g.setColor(Color.BLACK);
                    g.setFont(new Font("DejaVu Sans", Font.PLAIN, 8));
                    for (int col = 0; col < cells.length; col++) g.drawString(clip(g, cells[col], w[col] - 6), (int) x[col] + 3, y + 16);
                    g.setColor(new Color(187, 187, 187));
                    g.drawLine(0, y + ROW_HEIGHT, (int) width, y + ROW_HEIGHT);
                }
                int footerY = (int) height - FOOTER;
                g.setColor(new Color(187, 187, 187));
                g.drawLine(0, footerY, (int) width, footerY);
                g.setColor(new Color(51, 51, 51));
                g.setFont(new Font("DejaVu Sans", Font.PLAIN, 7));
                g.drawString("Источник: история изменений цен · Обычная цена — pricesMap · Цена 99 — prices99Map",
                        0, footerY + 10);
                String pageLabel = "Лист " + (pageIndex + 1) + " / " + pages;
                g.drawString(pageLabel, (int) (width - g.getFontMetrics().stringWidth(pageLabel)), footerY + 19);
                return Printable.PAGE_EXISTS;
            } finally { g.dispose(); }
        }

        private static String clip(Graphics2D g, String value, double width) {
            String text = value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ");
            if (g.getFontMetrics().stringWidth(text) <= width) return text;
            while (!text.isEmpty() && g.getFontMetrics().stringWidth(text + "…") > width)
                text = text.substring(0, text.length() - 1);
            return text.isEmpty() ? "" : text + "…";
        }
    }

    private static final class ChangeRow {
        private final double detectedAt;
        private final String marking, name, barcode, priceType, oldValue, newValue, delta;

        private ChangeRow(double detectedAt, String marking, String name, String barcode,
                          String priceType, String oldValue, String newValue, String delta) {
            this.detectedAt = detectedAt; this.marking = marking; this.name = name; this.barcode = barcode;
            this.priceType = priceType; this.oldValue = oldValue; this.newValue = newValue; this.delta = delta;
        }

        String marking() { return marking; }
        String name() { return name; }
        String barcode() { return barcode; }
        String priceType() { return priceType; }
        String oldValue() { return oldValue; }
        String newValue() { return newValue; }
        String delta() { return delta; }
        double detectedAt() { return detectedAt; }
        String selectionKey() {
            return detectedAt + "\u0000" + marking + "\u0000" + priceType + "\u0000" + oldValue + "\u0000" + newValue;
        }
        @Override public String toString() { return formatEpoch(detectedAt); }
    }

    private static void appendChangedPrices(List<ChangeRow> output, PriceMonitoringTaskService.PriceChange change,
                                             JsonNode oldPrices, JsonNode newPrices, PriceFilter filter, int selectedNumber) {
        JsonNode oldNormal = oldPrices.path("pricesMap"), newNormal = newPrices.path("pricesMap");
        JsonNode oldPromo = oldPrices.path("prices99Map"), newPromo = newPrices.path("prices99Map");
        if (filter == PriceFilter.FINAL) {
            if (selectedNumber != 0) return;
            String beforeNormal = effectiveFinal(oldNormal), afterNormal = effectiveFinal(newNormal);
            String beforePromo = effectiveFinal(oldPromo), afterPromo = effectiveFinal(newPromo);
            if (beforeNormal.equals(afterNormal) && beforePromo.equals(afterPromo)) return;
            output.add(changeRow(change, "Итоговая цена · price4, иначе price2",
                    beforeNormal, afterNormal, beforePromo, afterPromo));
            return;
        }

        java.util.TreeSet<String> numbers = new java.util.TreeSet<>();
        for (JsonNode map : List.of(oldNormal, newNormal, oldPromo, newPromo)) {
            if (map.isObject()) map.fieldNames().forEachRemaining(numbers::add);
        }
        for (String number : numbers) {
            if (selectedNumber > 0 && !Integer.toString(selectedNumber).equals(number)) continue;
            String beforeNormal = displayPrice(oldNormal.get(number));
            String afterNormal = displayPrice(newNormal.get(number));
            String beforePromo = displayPrice(oldPromo.get(number));
            String afterPromo = displayPrice(newPromo.get(number));
            boolean normalChanged = !beforeNormal.equals(afterNormal);
            boolean promoChanged = !beforePromo.equals(afterPromo);
            if (filter == PriceFilter.NORMAL && !normalChanged || filter == PriceFilter.PROMO && !promoChanged
                    || filter == PriceFilter.ALL && !normalChanged && !promoChanged) continue;
            if (filter == PriceFilter.NORMAL) beforePromo = afterPromo = "—";
            if (filter == PriceFilter.PROMO) beforeNormal = afterNormal = "—";
            output.add(changeRow(change, positionLabel(number), beforeNormal, afterNormal, beforePromo, afterPromo));
        }
    }

    private static ChangeRow changeRow(PriceMonitoringTaskService.PriceChange change, String label,
                                       String beforeNormal, String afterNormal, String beforePromo, String afterPromo) {
        String oldValue = pairedPrices(beforeNormal, beforePromo);
        String newValue = pairedPrices(afterNormal, afterPromo);
        String delta = pairedPrices(priceDifference(beforeNormal, afterNormal), priceDifference(beforePromo, afterPromo));
        return new ChangeRow(change.detectedAt(), change.marking(), change.name(), change.barcode(),
                label, oldValue, newValue, delta);
    }

    private static String pairedPrices(String normal, String promo) {
        return "Обычная: " + normal + " · 99: " + promo;
    }

    private static String priceDifference(String before, String after) {
        if ("—".equals(before) || "—".equals(after)) return "—";
        try {
            java.math.BigDecimal difference = new java.math.BigDecimal(after.replace(',', '.'))
                    .subtract(new java.math.BigDecimal(before.replace(',', '.')));
            if (difference.signum() == 0) return "0";
            return (difference.signum() > 0 ? "+" : "")
                    + difference.stripTrailingZeros().toPlainString().replace('.', ',');
        } catch (NumberFormatException ignored) {
            return "—";
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

    private static String effectiveFinal(JsonNode map) {
        JsonNode node = map.path("4");
        String source = "price4";
        if ("—".equals(displayPrice(node))) {
            node = map.path("2");
            source = "price2";
        }
        if (node.isMissingNode() || node.isNull()) return "—";
        String price = displayPrice(node);
        return price;
    }

    private static String displayPrice(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "—";
        String value = node.isObject() ? node.path("value").asText("") : node.asText("");
        if (value.isBlank()) return "—";
        try {
            return new java.math.BigDecimal(value).stripTrailingZeros().toPlainString().replace('.', ',');
        } catch (NumberFormatException ignored) {
            return value;
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
        ALL("Все изменения"), NORMAL("Обычные · pricesMap"),
        PROMO("99 · prices99Map"), FINAL("Итоговая цена");
        private final String label;
        PriceFilter(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    @FunctionalInterface interface HistoryOpener {
        void open(String marking, String name, String barcode);
    }
}
