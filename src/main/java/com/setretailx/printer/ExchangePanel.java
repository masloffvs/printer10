package com.setretailx.printer;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** SetRetailX connection placeholder and request/response log. */
@SuppressWarnings("serial")
final class ExchangePanel extends JPanel {
    private final JTextArea log = new JTextArea();

    ExchangePanel() {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(new JLabel("Журнал запросов и ответов появится после переноса авторизации и JSON-RPC клиента."),
                BorderLayout.NORTH);
        log.setEditable(false);
        add(new JScrollPane(log), BorderLayout.CENTER);
    }

    void append(String message) {
        log.append("[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }
}
