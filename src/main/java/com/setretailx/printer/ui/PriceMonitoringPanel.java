package com.setretailx.printer.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setretailx.printer.service.PriceMonitoringTaskService;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
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

/** Controls persistent price-monitor tasks and displays the recent price-change history. */
@SuppressWarnings("serial")
public final class PriceMonitoringPanel extends JPanel implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TASK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter CHANGE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private final PriceMonitoringTaskService service;
    private final DefaultTableModel taskModel = model("Задача", "Состояние", "Расписание",
            "Последний запуск", "Следующий запуск", "Результат");
    private final JTable taskTable = new JTable(taskModel);
    private final DefaultTableModel changeModel = model("Когда", "Артикул", "Товар", "Штрихкод",
            "Тип цены", "Было", "Стало");
    private final JTable changeTable = new JTable(changeModel);
    private final JLabel summary = new JLabel("Загрузка задач…");
    private final JLabel status = new JLabel(" ");
    private final JCheckBox enabled = new JCheckBox("Включена");
    private final JSpinner intervalMinutes = new JSpinner(new SpinnerNumberModel(60, 1, 10080, 1));
    private final JButton saveSchedule = new JButton("Сохранить расписание");
    private final JButton runNow = new JButton("Запустить сейчас");
    private final JTextField changeSearch = new JTextField(18);
    private final JComboBox<PriceFilter> priceType = new JComboBox<>(PriceFilter.values());
    private final JLabel changesCount = new JLabel(" ");
    private final Timer refreshTimer;
    private List<PriceMonitoringTaskService.TaskInfo> tasks = List.of();
    private boolean refreshing;

    public PriceMonitoringPanel(PriceMonitoringTaskService service) {
        super(new BorderLayout(8, 8));
        this.service = java.util.Objects.requireNonNull(service, "service");
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Задачи", createTasksTab());
        tabs.addTab("История цен", createChangesTab());
        add(tabs, BorderLayout.CENTER);

        saveSchedule.addActionListener(event -> saveSchedule());
        runNow.addActionListener(event -> runSelectedNow());
        taskTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) updateTaskControls();
        });
        changeSearch.addActionListener(event -> refreshChanges());
        changeSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { refreshChanges(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { refreshChanges(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { refreshChanges(); }
        });
        priceType.addActionListener(event -> refreshChanges());

        service.start();
        refresh();
        refreshTimer = new Timer(5000, event -> refresh());
        refreshTimer.start();
    }

    private JPanel createTasksTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(summary, BorderLayout.NORTH);
        configureTable(taskTable);
        taskTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.add(new JScrollPane(taskTable), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.add(enabled);
        controls.add(new JLabel("Интервал, минут:"));
        controls.add(intervalMinutes);
        controls.add(saveSchedule);
        controls.add(runNow);
        bottom.add(controls, BorderLayout.NORTH);
        status.setVerticalAlignment(JLabel.TOP);
        bottom.add(status, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createChangesTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filters.add(new JLabel("Поиск товара:"));
        changeSearch.setToolTipText("Артикул, название или штрихкод");
        filters.add(changeSearch);
        filters.add(new JLabel("Тип цены:"));
        filters.add(priceType);
        panel.add(filters, BorderLayout.NORTH);
        configureTable(changeTable);
        panel.add(new JScrollPane(changeTable), BorderLayout.CENTER);
        panel.add(changesCount, BorderLayout.SOUTH);
        return panel;
    }

    private static void configureTable(JTable table) {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowSelectionAllowed(true);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(false);
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }

    /** Refresh both views; database reads are short and confined to this UI action. */
    public void refresh() {
        if (refreshing) return;
        refreshing = true;
        String selected = selectedTaskId();
        try {
            tasks = service.listTasks();
            int enabledCount = (int) tasks.stream().filter(PriceMonitoringTaskService.TaskInfo::enabled).count();
            int runningCount = (int) tasks.stream().filter(task -> "Выполняется".equals(task.lastStatus())).count();
            summary.setText("Всего задач: " + tasks.size() + " · включено: " + enabledCount
                    + " · сейчас выполняется: " + runningCount);
            taskModel.setRowCount(0);
            int selectedRow = -1;
            for (int row = 0; row < tasks.size(); row++) {
                var task = tasks.get(row);
                int interval = Math.max(1, task.intervalSeconds() / 60);
                taskModel.addRow(new Object[]{task.name(), task.lastStatus(),
                        (task.enabled() ? "Включена" : "Выключена") + " · каждые " + interval + " мин",
                        formatTime(task.lastFinishedAt(), "ещё не запускалась"),
                        task.enabled() ? formatTime(task.nextRunAt(), "не запланирован") : "—",
                        task.lastResult().isEmpty() ? "—" : task.lastResult()});
                if (task.taskId().equals(selected)) selectedRow = row;
            }
            if (selectedRow < 0 && !tasks.isEmpty()) selectedRow = 0;
            if (selectedRow >= 0) taskTable.setRowSelectionInterval(selectedRow, selectedRow);
            updateTaskControls();
            refreshChanges();
        } catch (SQLException error) {
            status.setText("Не удалось обновить задачи: " + error.getMessage());
        } finally {
            refreshing = false;
        }
    }

    private void saveSchedule() {
        PriceMonitoringTaskService.TaskInfo task = selectedTask();
        if (task == null) return;
        try {
            service.configure(task.taskId(), enabled.isSelected(),
                    ((Number) intervalMinutes.getValue()).intValue() * 60);
            status.setText("Расписание сохранено.");
            refresh();
        } catch (SQLException error) {
            status.setText("Не удалось сохранить расписание: " + error.getMessage());
        }
    }

    private void runSelectedNow() {
        PriceMonitoringTaskService.TaskInfo task = selectedTask();
        if (task == null) return;
        if (service.runNow(task.taskId())) {
            status.setText("Задача выполняется…");
            updateTaskControls();
        } else status.setText("Другая задача уже выполняется или задача не найдена.");
    }

    private void updateTaskControls() {
        PriceMonitoringTaskService.TaskInfo task = selectedTask();
        boolean busy = tasks.stream().anyMatch(item -> "Выполняется".equals(item.lastStatus()));
        boolean editable = task != null && !busy;
        enabled.setEnabled(editable);
        intervalMinutes.setEnabled(editable);
        saveSchedule.setEnabled(editable);
        runNow.setEnabled(task != null && !busy);
        if (task != null) {
            enabled.setSelected(task.enabled());
            intervalMinutes.setValue(Math.max(1, Math.min(10080, task.intervalSeconds() / 60)));
            if (!busy) {
                status.setText("Выбрана задача: " + task.name() + " · статус: " + task.lastStatus()
                        + "\nРезультат последнего запуска: "
                        + (task.lastResult().isEmpty() ? "—" : task.lastResult()));
            }
        } else {
            saveSchedule.setEnabled(false);
            runNow.setEnabled(false);
        }
    }

    private void refreshChanges() {
        try {
            String query = changeSearch.getText().trim().toLowerCase(Locale.ROOT);
            PriceFilter filter = (PriceFilter) priceType.getSelectedItem();
            List<ChangeRow> visible = new ArrayList<>();
            List<PriceMonitoringTaskService.PriceChange> changes = service.recentChanges(1000);
            for (var change : changes) {
                String searchable = (change.marking() + " " + change.name() + " " + change.barcode())
                        .toLowerCase(Locale.ROOT);
                if (!query.isEmpty() && !searchable.contains(query)) continue;
                JsonNode oldPrices = readJson(change.oldPricesJson());
                JsonNode newPrices = readJson(change.newPricesJson());
                appendChangedPrices(visible, change, oldPrices, newPrices, "Обычные", "pricesMap", filter);
                appendChangedPrices(visible, change, oldPrices, newPrices, "99", "prices99Map", filter);
            }
            visible.sort((left, right) -> Double.compare(right.detectedAt(), left.detectedAt()));
            changeModel.setRowCount(0);
            for (ChangeRow entry : visible) {
                changeModel.addRow(new Object[]{formatEpoch(entry.detectedAt(), CHANGE_TIME), entry.marking(),
                        entry.name(), entry.barcode(), entry.priceType(), entry.oldValue(), entry.newValue()});
            }
            changesCount.setText("Показано изменений цен: " + visible.size()
                    + (changes.size() >= 1000 ? " · просмотрены последние 1000 событий" : ""));
        } catch (SQLException error) {
            changesCount.setText("Не удалось загрузить историю: " + error.getMessage());
        }
    }

    private static void appendChangedPrices(List<ChangeRow> output,
                                            PriceMonitoringTaskService.PriceChange change,
                                            JsonNode oldPrices, JsonNode newPrices,
                                            String label, String mapName, PriceFilter filter) {
        if (filter != PriceFilter.ALL && !filter.mapName.equals(mapName)) return;
        JsonNode oldMap = oldPrices.path(mapName);
        JsonNode newMap = newPrices.path(mapName);
        java.util.TreeSet<String> numbers = new java.util.TreeSet<>();
        if (oldMap.isObject()) oldMap.fieldNames().forEachRemaining(numbers::add);
        if (newMap.isObject()) newMap.fieldNames().forEachRemaining(numbers::add);
        for (String number : numbers) {
            JsonNode oldNode = oldMap.get(number), newNode = newMap.get(number);
            String oldValue = oldNode == null ? "—" : oldNode.asText();
            String newValue = newNode == null ? "—" : newNode.asText();
            if (!oldValue.equals(newValue)) output.add(new ChangeRow(change.detectedAt(), change.marking(),
                    change.name(), change.barcode(), label + " #" + number, oldValue, newValue));
        }
    }

    private static JsonNode readJson(String value) {
        try { return JSON.readTree(value == null ? "{}" : value); }
        catch (Exception ignored) { return JSON.createObjectNode(); }
    }

    private String selectedTaskId() {
        PriceMonitoringTaskService.TaskInfo task = selectedTask();
        return task == null ? null : task.taskId();
    }

    private PriceMonitoringTaskService.TaskInfo selectedTask() {
        int row = taskTable.getSelectedRow();
        return row >= 0 && row < tasks.size() ? tasks.get(row) : null;
    }

    private static String formatTime(Double value, String fallback) {
        return value == null ? fallback : formatEpoch(value, TASK_TIME);
    }

    private static String formatEpoch(double value, DateTimeFormatter formatter) {
        return Instant.ofEpochMilli((long) (value * 1000)).atZone(ZoneId.systemDefault()).format(formatter);
    }

    @Override public void close() {
        refreshTimer.stop();
        service.close();
    }

    private enum PriceFilter {
        ALL("Все", null), NORMAL("Обычная", "pricesMap"), PROMO("99", "prices99Map");
        private final String label;
        private final String mapName;
        PriceFilter(String label, String mapName) { this.label = label; this.mapName = mapName; }
        @Override public String toString() { return label; }
    }

    private record ChangeRow(double detectedAt, String marking, String name, String barcode,
                             String priceType, String oldValue, String newValue) { }
}
