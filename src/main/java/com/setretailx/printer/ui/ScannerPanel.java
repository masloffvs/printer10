package com.setretailx.printer.ui;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.LinkedHashMap;
import java.util.Map;

import com.setretailx.printer.model.ScannedProbe;

/** Shared scan field and the currently open MDI windows that may receive scans. */
@SuppressWarnings("serial")
final class ScannerPanel extends JPanel {
    private final JTextField input = new JTextField();
    private final JPanel recipients = new JPanel();
    private final Map<String, JCheckBox> recipientChecks = new LinkedHashMap<>();
    private final JTextArea lastScan = new JTextArea(5, 36);

    ScannerPanel(Runnable submitScan) {
        super(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        JPanel content = new JPanel();
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));
        content.setAlignmentX(LEFT_ALIGNMENT);
        JLabel inputLabel = new JLabel("Поле сканирования");
        inputLabel.setAlignmentX(LEFT_ALIGNMENT);
        content.add(inputLabel);
        input.setToolTipText("Сканируйте штрихкод товара или ценник; Enter отправит его в выбранные окна.");
        input.setMaximumSize(new Dimension(Integer.MAX_VALUE, input.getPreferredSize().height + 8));
        input.setAlignmentX(LEFT_ALIGNMENT);
        content.add(input);
        JLabel hint = new JLabel("Откройте нужное окно-получатель и отметьте его. Можно выбрать несколько окон.");
        hint.setToolTipText("Тип скана учитывается автоматически: товарный штрихкод, ценник с ценами или данные для сверки.");
        hint.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
        hint.setAlignmentX(LEFT_ALIGNMENT);
        content.add(hint);

        recipients.setLayout(new javax.swing.BoxLayout(recipients, javax.swing.BoxLayout.Y_AXIS));
        recipients.setBorder(BorderFactory.createTitledBorder("Передавать сканы в открытые окна"));
        recipients.setAlignmentX(LEFT_ALIGNMENT);
        content.add(recipients);

        lastScan.setEditable(false);
        lastScan.setLineWrap(true);
        lastScan.setWrapStyleWord(true);
        lastScan.setText("Пока сканов нет");
        JScrollPane parsedScroll = new JScrollPane(lastScan);
        parsedScroll.setBorder(BorderFactory.createTitledBorder("Последний скан · ScannedProbe"));
        parsedScroll.setAlignmentX(LEFT_ALIGNMENT);
        parsedScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        content.add(parsedScroll);

        JLabel submitHint = new JLabel("Нажмите Enter после сканирования. Для возврата к вводу просто активируйте это окно.");
        submitHint.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        submitHint.setAlignmentX(LEFT_ALIGNMENT);
        content.add(submitHint);
        add(content, BorderLayout.NORTH);
        input.addActionListener(event -> submitScan.run());
    }

    void registerRecipient(String key, String title) {
        JCheckBox checkBox = new JCheckBox(title);
        checkBox.setEnabled(false);
        checkBox.setAlignmentX(LEFT_ALIGNMENT);
        checkBox.setFocusable(false);
        checkBox.setMaximumSize(checkBox.getPreferredSize());
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

    void showParsedScan(ScannedProbe probe) {
        lastScan.setText("barcode: " + display(probe.barcode())
                + "\nprice1: " + display(probe.price1())
                + "\nprice2: " + display(probe.price2())
                + "\nprice3: " + display(probe.price3())
                + "\nprice4: " + display(probe.price4())
                + "\nstcode: " + display(probe.stcode())
                + "\nrussian_mark: " + display(probe.russianMark()));
        lastScan.setCaretPosition(0);
    }

    void showScanError(String raw, String error) {
        lastScan.setText("Не удалось разобрать скан: " + error + "\nВходные данные: " + raw);
        lastScan.setCaretPosition(0);
    }

    private static String display(String value) { return value == null || value.isBlank() ? "—" : value; }
}
