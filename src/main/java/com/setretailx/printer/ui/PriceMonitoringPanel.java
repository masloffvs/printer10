package com.setretailx.printer.ui;

import com.setretailx.printer.service.PriceMonitoringTaskService;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
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
import java.util.List;

/** Controls persistent price-monitor tasks and their schedules. */
@SuppressWarnings("serial")
public final class PriceMonitoringPanel extends JPanel implements AutoCloseable {
    private static final DateTimeFormatter TASK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final PriceMonitoringTaskService service;
    private final DefaultTableModel taskModel = model("Задача", "Состояние", "Расписание",
            "Последний запуск", "Следующий запуск", "Результат");
    private final JTable taskTable = new JTable(taskModel);
    private final MdiWorkspace workspace;
    private final JLabel summary = new JLabel("Загрузка задач…");
    private final JLabel status = new JLabel(" ");
    private final JCheckBox enabled = new JCheckBox("Включена");
    private final JSpinner intervalMinutes = new JSpinner(new SpinnerNumberModel(60, 1, 10080, 1));
    private final JButton saveSchedule = new JButton("Сохранить расписание");
    private final JButton runNow = new JButton("Запустить сейчас");
    private final Timer refreshTimer;
    private List<PriceMonitoringTaskService.TaskInfo> tasks = List.of();
    private boolean refreshing;

    public PriceMonitoringPanel(PriceMonitoringTaskService service, MdiWorkspace workspace) {
        super(new BorderLayout(8, 8));
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.workspace = java.util.Objects.requireNonNull(workspace, "workspace");
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(createTasksTab(), BorderLayout.CENTER);

        saveSchedule.addActionListener(event -> saveSchedule());
        runNow.addActionListener(event -> runSelectedNow());
        taskTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) updateTaskControls();
        });
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
        JButton logs = new JButton("Просмотр логов");
        logs.addActionListener(event -> showTaskLogs());
        controls.add(logs);
        bottom.add(controls, BorderLayout.NORTH);
        status.setVerticalAlignment(JLabel.TOP);
        bottom.add(status, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
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

    /** Refresh task state from the shared SQLite store. */
    public void refresh() {
        if (refreshing) return;
        refreshing = true;
        String selected = selectedTaskId();
        try {
            tasks = service.listTasks();
            int enabledCount = (int) tasks.stream().filter(PriceMonitoringTaskService.TaskInfo::enabled).count();
            int runningCount = (int) tasks.stream().filter(task -> "Выполняется".equals(task.lastStatus())).count();
            summary.setText("Всего задач: " + tasks.size() + " · включено: " + enabledCount
                    + " · сейчас выполняется: " + runningCount + " · база истории: " + service.databasePath());
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

    private void showTaskLogs() {
        PriceMonitoringTaskService.TaskInfo task = selectedTask();
        if (task == null) return;
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        DefaultTableModel logsModel = model("Начало", "Завершение", "Статус", "Результат");
        JTable logsTable = new JTable(logsModel);
        logsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JTextArea details = new JTextArea();
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        JLabel heading = new JLabel("Журнал запусков: " + task.name());
        panel.add(heading, BorderLayout.NORTH);
        panel.add(new JScrollPane(logsTable), BorderLayout.CENTER);
        JScrollPane detailScroll = new JScrollPane(details);
        detailScroll.setPreferredSize(new java.awt.Dimension(300, 100));
        detailScroll.setBorder(BorderFactory.createTitledBorder("Подробности запуска"));
        panel.add(detailScroll, BorderLayout.SOUTH);
        try {
            for (var run : service.taskRuns(task.taskId(), 500)) {
                logsModel.addRow(new Object[]{formatEpoch(run.startedAt(), TASK_TIME),
                        run.finishedAt() == null ? "Выполняется" : formatEpoch(run.finishedAt(), TASK_TIME),
                        run.status(), run.result()});
            }
            if (logsModel.getRowCount() == 0 && task.lastFinishedAt() != null) {
                logsModel.addRow(new Object[]{formatEpoch(task.lastStartedAt() == null
                                ? task.lastFinishedAt() : task.lastStartedAt(), TASK_TIME),
                        formatEpoch(task.lastFinishedAt(), TASK_TIME), task.lastStatus(), task.lastResult()});
            }
            if (logsModel.getRowCount() == 0) details.setText("Для этой задачи ещё нет сохранённых запусков.");
            logsTable.getSelectionModel().addListSelectionListener(event -> {
                int row = logsTable.getSelectedRow();
                if (!event.getValueIsAdjusting() && row >= 0) details.setText(String.valueOf(logsModel.getValueAt(row, 3)));
            });
            if (logsModel.getRowCount() > 0) logsTable.setRowSelectionInterval(0, 0);
        } catch (SQLException error) {
            details.setText("Не удалось загрузить логи: " + error.getMessage());
        }
        workspace.addDynamicWindow("Логи задачи — " + task.name(), panel, 760, 440);
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

}
