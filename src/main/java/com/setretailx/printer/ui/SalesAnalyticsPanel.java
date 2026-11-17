package com.setretailx.printer.ui;

import com.setretailx.printer.service.PriceMonitoringTaskService;
import com.setretailx.printer.service.PriceMonitoringTaskService.SalesArticle;
import com.setretailx.printer.service.PriceMonitoringTaskService.SalesDay;
import com.setretailx.printer.service.PriceMonitoringTaskService.SalesPosition;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Search and analyze receipt sales across all locally synchronized articles. */
@SuppressWarnings("serial")
final class SalesAnalyticsPanel extends JPanel {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final NumberFormat NUMBER = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"));
    private static final NumberFormat MONEY = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("ru-RU"));
    private static final int POSITION_LIMIT = 20_000;

    private final PriceMonitoringTaskService service;
    private final JTextField query = new JTextField(24);
    private final JComboBox<Period> period = new JComboBox<>(Period.values());
    private final DefaultTableModel articlesModel = new DefaultTableModel(
            new Object[]{"✓", "Артикул", "Название", "Штрихкод", "Продано", "Выручка", "Чеков"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : Object.class; }
    };
    private final DefaultTableModel daysModel = model("День", "Продано", "Выручка", "Чеков");
    private final DefaultTableModel positionsModel = model("Дата", "Чек", "Артикул", "Товар", "Кол-во", "Цена", "Сумма", "Скидка");
    private final JTable articlesTable = new JTable(articlesModel);
    private final JLabel summary = new JLabel("Выберите артикулы, чтобы увидеть общие продажи.");
    private final JLabel status = new JLabel(" ");
    private final SalesChart chart = new SalesChart();
    private final AtomicLong requestId = new AtomicLong();
    private List<SalesArticle> articles = List.of();
    private List<SalesDay> days = List.of();
    private Set<String> selected = new LinkedHashSet<>();
    private boolean updatingTable;

    SalesAnalyticsPanel(PriceMonitoringTaskService service) {
        super(new BorderLayout(8, 8));
        this.service = service;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JButton search = new JButton("Найти");
        search.addActionListener(event -> loadArticles());
        query.addActionListener(event -> loadArticles());
        period.addActionListener(event -> loadArticles());
        JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2));
        controls.add(new JLabel("Поиск товара / артикула / штрихкода:"));
        controls.add(query);
        controls.add(search);
        controls.add(new JLabel("Период:"));
        controls.add(period);
        JButton refresh = new JButton("Обновить");
        refresh.addActionListener(event -> loadArticles());
        controls.add(refresh);
        JPanel header = new JPanel(new BorderLayout(4, 4));
        header.add(controls, BorderLayout.NORTH);
        header.add(summary, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        articlesTable.setFillsViewportHeight(true);
        articlesTable.setAutoCreateRowSorter(true);
        articlesTable.getColumnModel().getColumn(0).setMaxWidth(34);
        articlesTable.getModel().addTableModelListener(event -> {
            if (updatingTable || event.getType() != javax.swing.event.TableModelEvent.UPDATE
                    || event.getColumn() != 0 || event.getFirstRow() < 0) return;
            int row = event.getFirstRow();
            if (row >= articles.size()) return;
            String marking = articles.get(row).marking();
            if (Boolean.TRUE.equals(articlesModel.getValueAt(row, 0))) selected.add(marking);
            else selected.remove(marking);
            loadAnalytics();
        });
        JScrollPane articleScroll = new JScrollPane(articlesTable);
        articleScroll.setBorder(BorderFactory.createTitledBorder("Артикулы с продажами · отметьте один или несколько"));
        JButton selectVisible = new JButton("Выбрать всё"), clearSelection = new JButton("Снять выбор");
        selectVisible.addActionListener(event -> setVisibleSelected(true));
        clearSelection.addActionListener(event -> {
            selected.clear();
            syncSelection();
            loadAnalytics();
        });
        JPanel articleControls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        articleControls.add(selectVisible);
        articleControls.add(clearSelection);
        JPanel articlePanel = new JPanel(new BorderLayout());
        articlePanel.add(articleScroll, BorderLayout.CENTER);
        articlePanel.add(articleControls, BorderLayout.SOUTH);

        JTable daysTable = new JTable(daysModel);
        daysTable.setFillsViewportHeight(true);
        daysTable.setAutoCreateRowSorter(true);
        JTable positionsTable = new JTable(positionsModel);
        positionsTable.setFillsViewportHeight(true);
        positionsTable.setAutoCreateRowSorter(true);
        JScrollPane positionsScroll = new JScrollPane(positionsTable);
        positionsScroll.setBorder(BorderFactory.createTitledBorder("Строки чеков по выбранным артикулам"));
        JSplitPane chartAndDays = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chart, new JScrollPane(daysTable));
        chartAndDays.setResizeWeight(.55);
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chartAndDays, positionsScroll);
        right.setResizeWeight(.57);
        JSplitPane content = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, articlePanel, right);
        content.setResizeWeight(.36);
        content.setDividerLocation(410);
        add(content, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        loadArticles();
    }

    private void loadArticles() {
        long id = requestId.incrementAndGet();
        double[] bounds = periodBounds();
        String search = query.getText();
        summary.setText("Ищем артикулы в загруженной истории чеков…");
        new SwingWorker<List<SalesArticle>, Void>() {
            @Override protected List<SalesArticle> doInBackground() throws SQLException {
                return service.searchSalesArticles(search, bounds[0] < 0 ? null : bounds[0],
                        bounds[1] < 0 ? null : bounds[1], 2000);
            }
            @Override protected void done() {
                if (id != requestId.get()) return;
                try {
                    articles = get();
                    Set<String> available = new LinkedHashSet<>();
                    for (SalesArticle article : articles) available.add(article.marking());
                    updatingTable = true;
                    articlesModel.setRowCount(0);
                    for (SalesArticle article : articles) {
                        articlesModel.addRow(new Object[]{selected.contains(article.marking()), article.marking(),
                                article.name(), article.barcode(), quantity(article.units()), MONEY.format(article.revenue()),
                                article.checks()});
                    }
                    updatingTable = false;
                    summary.setText(articles.size() + " артикулов в выдаче · отмечено: " + selected.size()
                            + " (выбор сохраняется между поисками) · данные синхронизируются в разделе «Задачи».");
                    status.setText(articles.isEmpty()
                            ? "Продажи не найдены. Проверьте запрос или успешность задачи «История продаж из чеков»."
                            : "Показано артикулов: " + articles.size() + (articles.size() == 2000 ? " (лимит выдачи)" : ""));
                    loadAnalytics();
                } catch (Exception error) {
                    updatingTable = false;
                    showError(error);
                }
            }
        }.execute();
    }

    private void loadAnalytics() {
        long id = requestId.incrementAndGet();
        List<String> markings = List.copyOf(selected);
        double[] bounds = periodBounds();
        if (markings.isEmpty()) {
            days = List.of();
            daysModel.setRowCount(0);
            positionsModel.setRowCount(0);
            chart.repaint();
            summary.setText(articles.size() + " артикулов в выборке · отметьте артикулы для общей аналитики.");
            return;
        }
        status.setText("Считаем продажи для " + markings.size() + " артикулов…");
        new SwingWorker<LoadedSales, Void>() {
            @Override protected LoadedSales doInBackground() throws SQLException {
                Double from = bounds[0] < 0 ? null : bounds[0];
                Double to = bounds[1] < 0 ? null : bounds[1];
                return new LoadedSales(service.salesHistory(markings, from, to),
                        service.salesPositions(markings, from, to, POSITION_LIMIT));
            }
            @Override protected void done() {
                if (id != requestId.get()) return;
                try {
                    LoadedSales loaded = get();
                    days = loaded.days();
                    daysModel.setRowCount(0);
                    double units = 0, revenue = 0;
                    int checks = 0;
                    for (SalesDay day : days) {
                        daysModel.addRow(new Object[]{Instant.ofEpochSecond(day.dayEpochSeconds())
                                .atZone(ZoneId.systemDefault()).format(DAY), quantity(day.units()),
                                MONEY.format(day.revenue()), day.checks()});
                        units += day.units(); revenue += day.revenue(); checks += day.checks();
                    }
                    positionsModel.setRowCount(0);
                    for (SalesPosition position : loaded.positions()) {
                        positionsModel.addRow(new Object[]{Instant.ofEpochMilli((long) (position.soldAt() * 1000))
                                .atZone(ZoneId.systemDefault()).format(TIME), position.checkNumber(), position.item(),
                                position.name(), quantity(position.quantity())
                                + (position.unit().isBlank() ? "" : " " + position.unit()), MONEY.format(position.price()),
                                MONEY.format(position.cost()), MONEY.format(position.discount())});
                    }
                    chart.repaint();
                    summary.setText("Выбрано артикулов: " + markings.size() + " · продано: " + quantity(units)
                            + " · выручка: " + MONEY.format(revenue) + " · чеков по дням: " + checks
                            + " · дней с продажами: " + days.size());
                    status.setText("Позиции чеков: " + loaded.positions().size()
                            + (loaded.positions().size() == POSITION_LIMIT ? " (показан лимит последних " + POSITION_LIMIT + ")" : ""));
                } catch (Exception error) { showError(error); }
            }
        }.execute();
    }

    private void setVisibleSelected(boolean value) {
        if (value) for (SalesArticle article : articles) selected.add(article.marking());
        else selected.clear();
        syncSelection();
        loadAnalytics();
    }

    private void syncSelection() {
        updatingTable = true;
        for (int row = 0; row < articles.size(); row++)
            articlesModel.setValueAt(selected.contains(articles.get(row).marking()), row, 0);
        updatingTable = false;
    }

    /** Bounds are epoch seconds; negative values represent an unbounded side. */
    private double[] periodBounds() {
        Period chosen = (Period) period.getSelectedItem();
        if (chosen == null || chosen.days == 0) return new double[]{-1, -1};
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        double from = today.minusDays(chosen.days - 1L).atStartOfDay(zone).toEpochSecond();
        double to = today.plusDays(1).atStartOfDay(zone).toEpochSecond();
        return new double[]{from, to};
    }

    private void showError(Exception error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        status.setText("Не удалось загрузить продажи: "
                + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
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

    private record LoadedSales(List<SalesDay> days, List<SalesPosition> positions) { }
    private enum Period {
        WEEK("7 дней", 7), MONTH("30 дней", 30), QUARTER("90 дней", 90), ALL("Всё время", 0);
        private final String title;
        private final int days;
        Period(String title, int days) { this.title = title; this.days = days; }
        @Override public String toString() { return title; }
    }

    private final class SalesChart extends JPanel {
        SalesChart() {
            setPreferredSize(new Dimension(500, 210));
            setMinimumSize(new Dimension(320, 140));
            setBorder(BorderFactory.createTitledBorder("Общая выручка по дням · выбранные артикулы"));
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (days.isEmpty()) return;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int left = 68, right = 12, top = 18, bottom = 28;
                int width = getWidth() - left - right, height = getHeight() - top - bottom;
                double max = days.stream().mapToDouble(SalesDay::revenue).max().orElse(1);
                if (max <= 0) max = 1;
                g.setColor(UIManagerColor.foreground(this));
                g.drawLine(left, top + height, left + width, top + height);
                int count = Math.min(days.size(), Math.max(1, width / 32));
                double slot = (double) width / count;
                for (int i = 0; i < count; i++) {
                    int sampleIndex = count == 1 ? 0
                            : (int) Math.round((double) (count - i - 1) * (days.size() - 1) / (count - 1));
                    SalesDay value = days.get(sampleIndex);
                    int barHeight = (int) Math.round(height * value.revenue() / max);
                    int barWidth = Math.max(4, (int) (slot * .62));
                    int x = left + (int) (i * slot + (slot - barWidth) / 2);
                    g.setColor(new Color(55, 155, 245));
                    g.fillRoundRect(x, top + height - barHeight, barWidth, Math.max(1, barHeight), 5, 5);
                    g.setColor(UIManagerColor.foreground(this));
                    if (count <= 14) g.drawString(Instant.ofEpochSecond(value.dayEpochSeconds())
                            .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM")), x - 4, top + height + 17);
                }
                g.drawString(MONEY.format(max), 5, top + 8);
                g.drawString("0 ₽", 24, top + height);
            } finally { g.dispose(); }
        }
    }

    private static final class UIManagerColor {
        static Color foreground(JPanel component) {
            Color color = javax.swing.UIManager.getColor("Label.foreground");
            return color == null ? component.getForeground() : color;
        }
    }
}
