package com.setretailx.printer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.FlowLayout;
import java.awt.Insets;

/** Shared barcode/price scan entry screen. */
@SuppressWarnings("serial")
final class ScannerPanel extends JPanel {
    private final JCheckBox subscription = new JCheckBox("Добавлять товары из общего потока в очередь печати", true);
    private final JTextField input = new JTextField(32);
    private final JComboBox<AppConfig.PrintFormat> format = new JComboBox<>(
            AppConfig.PRINT_FORMATS.toArray(AppConfig.PrintFormat[]::new));
    private final JSpinner amount = new JSpinner(new SpinnerNumberModel(1, 1, 999, 1));
    private final JLabel status = new JLabel("Сканируйте штрихкод или ценник в общее поле.");

    ScannerPanel(Runnable submitScan, Runnable assignUnassigned) {
        super(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        GridBagConstraints c = constraints();
        c.gridx = 0; c.gridy = 0; c.gridwidth = 3; c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        add(subscription, c);
        c.gridy++;
        add(new JLabel("Общий поток сканера — Enter отправит событие всем подходящим подписанным окнам:"), c);
        c.gridy++;
        c.gridwidth = 2;
        add(input, c);
        c.gridx = 2; c.gridwidth = 1; c.weightx = 0;
        JButton send = new JButton("Передать скан");
        send.addActionListener(event -> submitScan.run());
        add(send, c);
        input.addActionListener(event -> submitScan.run());

        c.gridx = 0; c.gridy++; c.gridwidth = 1; c.weightx = 0;
        add(new JLabel("Формат по умолчанию:"), c);
        c.gridx = 1; c.weightx = 0.5;
        add(format, c);
        c.gridx = 2; c.weightx = 0;
        JPanel amountPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        amountPanel.add(new JLabel("Тираж:"));
        amountPanel.add(amount);
        add(amountPanel, c);
        c.gridx = 0; c.gridy++; c.gridwidth = 3;
        JButton assign = new JButton("Назначить всем неназначенным");
        assign.addActionListener(event -> assignUnassigned.run());
        add(assign, c);
        c.gridy++;
        status.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        add(status, c);
        c.gridy++; c.weighty = 1;
        add(new JPanel(), c);
    }

    boolean isSubscribed() { return subscription.isSelected(); }
    String inputText() { return input.getText(); }
    void clearInput() { input.setText(""); }
    void focusInput() { input.requestFocusInWindow(); }
    AppConfig.PrintFormat selectedFormat() { return (AppConfig.PrintFormat) format.getSelectedItem(); }
    int amount() { return (Integer) amount.getValue(); }
    void setStatus(String message) { status.setText(message); }

    private static GridBagConstraints constraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(7, 6, 7, 6);
        c.anchor = GridBagConstraints.WEST;
        return c;
    }
}
