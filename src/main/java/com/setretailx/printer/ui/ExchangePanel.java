package com.setretailx.printer.ui;

import com.setretailx.printer.service.SetRetailXClient;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.FlowLayout;
import java.awt.BorderLayout;
import java.time.ZoneId;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** SetRetailX connection status and asynchronous request/response log. */
@SuppressWarnings("serial")
final class ExchangePanel extends JPanel {
    private final JTextArea log = new JTextArea();
    private final JButton connect = new JButton("Проверить подключение");
    private final JLabel status = new JLabel("Не подключено");

    ExchangePanel(Runnable connectAction) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        connect.addActionListener(event -> connectAction.run());
        header.add(connect);
        header.add(status);
        add(header, BorderLayout.NORTH);
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        add(new JScrollPane(log), BorderLayout.CENTER);
    }

    void append(String message) {
        log.append("[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    void append(SetRetailXClient.ExchangeEvent event) {
        String time = event.timestamp().atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        log.append("[" + time + "] " + event.title() + "\n" + event.details() + "\n\n");
        if (event.tone().equals("error")) status.setText("Ошибка подключения");
        log.setCaretPosition(log.getDocument().getLength());
    }

    void setBusy(boolean busy) {
        connect.setEnabled(!busy);
        if (busy) status.setText("Подключаемся…");
    }

    void setStatus(String text) { status.setText(text); }
}
