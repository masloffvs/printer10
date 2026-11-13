package com.setretailx.printer.ui;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared scan field and the currently open MDI windows that may receive scans. */
@SuppressWarnings("serial")
final class ScannerPanel extends JPanel {
    private final JTextField input = new JTextField();
    private final JPanel recipients = new JPanel(new GridLayout(0, 1, 0, 4));
    private final Map<String, JCheckBox> recipientChecks = new LinkedHashMap<>();

    ScannerPanel(Runnable submitScan) {
        super(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(input, BorderLayout.NORTH);
        recipients.setBorder(BorderFactory.createTitledBorder("Передавать сканы в открытые окна"));
        add(recipients, BorderLayout.CENTER);
        input.addActionListener(event -> submitScan.run());
    }

    void registerRecipient(String key, String title) {
        JCheckBox checkBox = new JCheckBox(title);
        checkBox.setEnabled(false);
        recipientChecks.put(key, checkBox);
        recipients.add(checkBox);
    }

    void setRecipientOpen(String key, boolean open) {
        JCheckBox checkBox = recipientChecks.get(key);
        if (checkBox == null) return;
        if (open) {
            // Opening a recipient makes it the default target. Other open
            // recipients remain available for deliberate multi-routing.
            recipientChecks.forEach((otherKey, otherCheckBox) -> {
                if (!otherKey.equals(key)) otherCheckBox.setSelected(false);
            });
        }
        checkBox.setEnabled(open);
        checkBox.setSelected(open);
    }

    boolean sendsTo(String key) {
        JCheckBox checkBox = recipientChecks.get(key);
        return checkBox != null && checkBox.isEnabled() && checkBox.isSelected();
    }

    String inputText() { return input.getText(); }
    void clearInput() { input.setText(""); }
    void focusInput() { input.requestFocusInWindow(); }
}
