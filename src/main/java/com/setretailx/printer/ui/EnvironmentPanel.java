package com.setretailx.printer.ui;

import com.setretailx.printer.config.AppConfig;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Table editor for the .env settings used by the application. */
@SuppressWarnings("serial")
final class EnvironmentPanel extends JPanel {
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"Переменная", "Значение"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return true; }
    };
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");
    private final Runnable settingsSaved;
    private final List<String> comments = new ArrayList<>();

    EnvironmentPanel(Runnable settingsSaved) {
        super(new BorderLayout(8, 8));
        this.settingsSaved = settingsSaved == null ? () -> { } : settingsSaved;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel path = new JLabel("Файл настроек: " + AppConfig.DOT_ENV_PATH);
        path.setToolTipText(AppConfig.DOT_ENV_PATH.toString());
        add(path, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.setRowHeight(Math.max(table.getRowHeight(), 26));
        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        table.getColumnModel().getColumn(1).setPreferredWidth(500);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 8));
        JLabel hint = new JLabel("Настройки подключения применяются сразу; изменение пути SQLite потребует перезапуска. "
                + "Переменные ОС имеют приоритет; пароль хранится в файле открытым текстом.");
        footer.add(hint, BorderLayout.NORTH);
        status.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        footer.add(status, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 0));
        JButton add = new JButton("Добавить переменную");
        add.addActionListener(event -> {
            int row = model.getRowCount();
            model.addRow(new Object[]{"", ""});
            table.setRowSelectionInterval(row, row);
            table.editCellAt(row, 0);
            table.requestFocusInWindow();
        });
        JButton remove = new JButton("Удалить выбранные");
        remove.addActionListener(event -> removeSelectedRows());
        JButton reload = new JButton("Перечитать файл");
        reload.addActionListener(event -> loadFile());
        JButton save = new JButton("Сохранить и применить");
        save.addActionListener(event -> saveFile());
        actions.add(add);
        actions.add(remove);
        actions.add(reload);
        actions.add(save);
        footer.add(actions, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);

        loadFile();
    }

    private void loadFile() {
        try {
            String contents = AppConfig.readDotEnv();
            comments.clear();
            model.setRowCount(0);
            for (String line : contents.split("\\R", -1)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    if (!line.isEmpty()) comments.add(line);
                    continue;
                }
                int split = line.indexOf('=');
                if (split <= 0) {
                    comments.add("# " + line);
                    continue;
                }
                String key = line.substring(0, split).trim();
                String value = line.substring(split + 1).trim();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                model.addRow(new Object[]{key, value});
            }
            status.setText("Файл загружен.");
        } catch (IOException error) {
            status.setText("Не удалось прочитать файл.");
            JOptionPane.showMessageDialog(this,
                    "Не удалось прочитать " + AppConfig.DOT_ENV_PATH + ":\n" + error.getMessage(),
                    "Ошибка чтения .env", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveFile() {
        try {
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) {
                throw new IllegalArgumentException("Завершите редактирование текущей ячейки.");
            }
            String contents = serialize();
            if (!Files.exists(AppConfig.DOT_ENV_PATH) && model.getRowCount() == 0) contents = AppConfig.readDotEnv();
            AppConfig.saveDotEnv(contents);
            settingsSaved.run();
            status.setText("Сохранено и применено: " + AppConfig.DOT_ENV_PATH);
        } catch (IOException | RuntimeException error) {
            status.setText("Не удалось сохранить настройки.");
            JOptionPane.showMessageDialog(this,
                    "Не удалось сохранить " + AppConfig.DOT_ENV_PATH + ":\n" + error.getMessage(),
                    "Ошибка сохранения .env", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void removeSelectedRows() {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        int[] selected = table.getSelectedRows();
        for (int index = selected.length - 1; index >= 0; index--) model.removeRow(selected[index]);
    }

    private String serialize() {
        StringBuilder result = new StringBuilder();
        for (String comment : comments) result.append(comment).append('\n');
        Set<String> keys = new HashSet<>();
        for (int row = 0; row < model.getRowCount(); row++) {
            String key = String.valueOf(model.getValueAt(row, 0)).trim();
            String value = String.valueOf(model.getValueAt(row, 1)).trim();
            if (key.isEmpty() && value.isEmpty()) continue;
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Некорректное имя переменной в строке " + (row + 1) + ".");
            }
            if (!keys.add(key)) throw new IllegalArgumentException("Переменная «" + key + "» указана несколько раз.");
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Значение «" + key + "» не должно содержать перевод строки.");
            }
            result.append(key).append('=').append(value).append('\n');
        }
        return result.toString();
    }
}
