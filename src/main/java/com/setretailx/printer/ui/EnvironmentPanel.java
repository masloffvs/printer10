package com.setretailx.printer.ui;

import com.setretailx.printer.config.AppConfig;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Files;

/** Editor for the .env file used by the application. */
@SuppressWarnings("serial")
final class EnvironmentPanel extends JPanel {
    private final JTextArea editor = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private final Runnable settingsSaved;

    EnvironmentPanel(Runnable settingsSaved) {
        super(new BorderLayout(8, 8));
        this.settingsSaved = settingsSaved == null ? () -> { } : settingsSaved;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel path = new JLabel("Файл настроек: " + AppConfig.DOT_ENV_PATH);
        path.setToolTipText(AppConfig.DOT_ENV_PATH.toString());
        add(path, BorderLayout.NORTH);

        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        editor.setTabSize(4);
        add(new JScrollPane(editor), BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 8));
        JLabel hint = new JLabel("Настройки подключения применяются сразу; изменение пути SQLite потребует перезапуска. "
                + "Переменные ОС имеют приоритет; пароль хранится в файле открытым текстом.");
        footer.add(hint, BorderLayout.NORTH);
        status.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        footer.add(status, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton reload = new JButton("Перечитать файл");
        reload.addActionListener(event -> loadFile());
        JButton save = new JButton("Сохранить и применить");
        save.addActionListener(event -> saveFile());
        actions.add(reload);
        actions.add(save);
        footer.add(actions, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);

        editor.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("control S"), "save-env");
        editor.getActionMap().put("save-env", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { saveFile(); }
        });
        loadFile();
    }

    private void loadFile() {
        try {
            editor.setText(AppConfig.readDotEnv());
            editor.setCaretPosition(0);
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
            if (!Files.exists(AppConfig.DOT_ENV_PATH) && editor.getText().isBlank()) {
                editor.setText(AppConfig.readDotEnv());
            }
            AppConfig.saveDotEnv(editor.getText());
            settingsSaved.run();
            status.setText("Сохранено и применено: " + AppConfig.DOT_ENV_PATH);
        } catch (IOException | RuntimeException error) {
            status.setText("Не удалось сохранить настройки.");
            JOptionPane.showMessageDialog(this,
                    "Не удалось сохранить " + AppConfig.DOT_ENV_PATH + ":\n" + error.getMessage(),
                    "Ошибка сохранения .env", JOptionPane.ERROR_MESSAGE);
        }
    }
}
