package com.setretailx.printer.ui;

import com.setretailx.printer.service.PriceMonitoringTaskService;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
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
    private final JLabel status = new JLabel("Выберите задачу и откройте её окно настроек.");
    private final Timer refreshTimer;
    private List<PriceMonitoringTaskService.TaskInfo> tasks = List.of();
    private boolean refreshing;

    public PriceMonitoringPanel(PriceMonitoringTaskService service, MdiWorkspace workspace) {
        super(new BorderLayout(8, 8));
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.workspace = java.util.Objects.requireNonNull(workspace, "workspace");
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(createTasksTab(), BorderLayout.CENTER);

        taskTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    openSelectedSettings();
                }
            }
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

        JPanel bottom = new JPanel(new BorderLayout(8, 6));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        javax.swing.JButton open = new javax.swing.JButton("Открыть настройки задачи…");
        open.addActionListener(event -> openSelectedSettings());
        controls.add(open);
        status.setVerticalAlignment(JLabel.CENTER);
        bottom.add(controls, BorderLayout.NORTH);
        bottom.add(status, BorderLayout.SOUTH);
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
            status.setText("Дважды щёлкните задачу или выберите её и нажмите «Открыть настройки задачи».");
        } catch (SQLException error) {
            status.setText("Не удалось обновить задачи: " + error.getMessage());
        } finally {
            refreshing = false;
        }
    }

    private void openSelectedSettings() {
        PriceMonitoringTaskService.TaskInfo task = selectedTask();
        if (task == null) return;
        workspace.showDynamicWindow("price-task:" + task.taskId(),
                "Настройки задачи — " + task.name(),
                new PriceTaskSettingsPanel(service, task.taskId()), 760, 520);
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
        return value == null ? fallback : Instant.ofEpochMilli((long) (value * 1000))
                .atZone(ZoneId.systemDefault()).format(TASK_TIME);
    }

    @Override public void close() {
        refreshTimer.stop();
        service.close();
    }

}
