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
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.HierarchyEvent;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Settings, manual execution, and run history for one background task. */
@SuppressWarnings("serial")
public final class PriceTaskSettingsPanel extends JPanel {
    private static final DateTimeFormatter TASK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PriceMonitoringTaskService service;
    private final String taskId;
    private final JCheckBox enabled = new JCheckBox("Задача включена");
    private final JSpinner intervalMinutes = new JSpinner(new SpinnerNumberModel(60, 1, 10080, 1));
    private final JButton save = new JButton("Сохранить настройки");
    private final JButton runNow = new JButton("Запустить сейчас");
    private final JLabel status = new JLabel("Загрузка состояния задачи…");
    private final DefaultTableModel runsModel = model("Начало", "Завершение", "Статус", "Результат");
    private final JTable runsTable = new JTable(runsModel);
    private final JTextArea details = new JTextArea();
    private final javax.swing.Timer refreshTimer;
    private List<PriceMonitoringTaskService.TaskRun> runs = List.of();
    private boolean refreshing;
    private boolean settingsLoaded;

    public PriceTaskSettingsPanel(PriceMonitoringTaskService service, String taskId) {
        super(new BorderLayout(8, 8));
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.taskId = java.util.Objects.requireNonNull(taskId, "taskId");
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.add(enabled);
        controls.add(new JLabel("Интервал, минут:"));
        controls.add(intervalMinutes);
        controls.add(save);
        controls.add(runNow);

        JPanel heading = new JPanel(new BorderLayout(4, 8));
        heading.add(controls, BorderLayout.NORTH);
        heading.add(status, BorderLayout.SOUTH);
        add(heading, BorderLayout.NORTH);

        runsTable.setFillsViewportHeight(true);
        runsTable.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        runsTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelectedRun();
        });
        add(new JScrollPane(runsTable), BorderLayout.CENTER);

        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        JScrollPane detailScroll = new JScrollPane(details);
        detailScroll.setPreferredSize(new java.awt.Dimension(300, 110));
        detailScroll.setBorder(BorderFactory.createTitledBorder("Подробности запуска"));
        add(detailScroll, BorderLayout.SOUTH);

        save.addActionListener(event -> saveSettings());
        runNow.addActionListener(event -> startNow());
        refresh();
        refreshTimer = new javax.swing.Timer(3000, event -> refresh());
        refreshTimer.start();
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0
                    && !isDisplayable()) refreshTimer.stop();
        });
    }

    private void saveSettings() {
        try {
            service.configure(taskId, enabled.isSelected(),
                    ((Number) intervalMinutes.getValue()).intValue() * 60);
            settingsLoaded = false;
            status.setText("Настройки сохранены.");
            refresh();
        } catch (SQLException | IllegalStateException error) {
            status.setText("Не удалось сохранить настройки: " + error.getMessage());
        }
    }

    private void startNow() {
        try {
            if (service.runNow(taskId)) {
                status.setText("Задача запущена вручную.");
                refresh();
            } else {
                status.setText("Задача уже выполняется или не найдена.");
            }
        } catch (IllegalStateException error) {
            status.setText("Не удалось запустить задачу: " + error.getMessage());
        }
    }

    private void refresh() {
        if (refreshing) return;
        refreshing = true;
        Long selectedRunId = selectedRunId();
        try {
            PriceMonitoringTaskService.TaskInfo task = service.listTasks().stream()
                    .filter(item -> item.taskId().equals(taskId)).findFirst().orElse(null);
            if (task == null) {
                status.setText("Задача не найдена.");
                enabled.setEnabled(false);
                intervalMinutes.setEnabled(false);
                save.setEnabled(false);
                runNow.setEnabled(false);
                return;
            }
            if (!settingsLoaded) {
                enabled.setSelected(task.enabled());
                intervalMinutes.setValue(Math.max(1, Math.min(10080, task.intervalSeconds() / 60)));
                settingsLoaded = true;
            }
            boolean busy = "Выполняется".equals(task.lastStatus());
            enabled.setEnabled(!busy);
            intervalMinutes.setEnabled(!busy);
            save.setEnabled(!busy);
            runNow.setEnabled(!busy);
            status.setText("Состояние: " + task.lastStatus() + " · последний запуск: "
                    + formatTime(task.lastFinishedAt(), "ещё не запускалась")
                    + (task.lastResult().isEmpty() ? "" : " · " + task.lastResult()));

            runs = service.taskRuns(taskId, 500);
            runsModel.setRowCount(0);
            int selectedRow = -1;
            for (int row = 0; row < runs.size(); row++) {
                PriceMonitoringTaskService.TaskRun run = runs.get(row);
                runsModel.addRow(new Object[]{formatEpoch(run.startedAt()),
                        run.finishedAt() == null ? "Выполняется" : formatEpoch(run.finishedAt()),
                        run.status(), run.result()});
                if (selectedRunId != null && run.id() == selectedRunId) selectedRow = row;
            }
            if (selectedRow < 0 && !runs.isEmpty()) selectedRow = 0;
            if (selectedRow >= 0) runsTable.setRowSelectionInterval(selectedRow, selectedRow);
            else details.setText("Для этой задачи ещё нет сохранённых запусков.");
        } catch (SQLException error) {
            status.setText("Не удалось загрузить задачу или журнал: " + error.getMessage());
        } finally {
            refreshing = false;
        }
    }

    private Long selectedRunId() {
        int row = runsTable.getSelectedRow();
        return row >= 0 && row < runs.size() ? runs.get(row).id() : null;
    }

    private void showSelectedRun() {
        int row = runsTable.getSelectedRow();
        if (row >= 0 && row < runs.size()) details.setText(runs.get(row).result());
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }

    private static String formatTime(Double value, String fallback) {
        return value == null ? fallback : formatEpoch(value);
    }

    private static String formatEpoch(double value) {
        return Instant.ofEpochMilli((long) (value * 1000)).atZone(ZoneId.systemDefault()).format(TASK_TIME);
    }
}
