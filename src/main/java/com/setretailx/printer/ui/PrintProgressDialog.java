package com.setretailx.printer.ui;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Window;

/** Modal progress feedback while print jobs are being submitted to SetRetailX. */
@SuppressWarnings("serial")
final class PrintProgressDialog extends JDialog {
    private final JLabel message = new JLabel("Подготавливаем задания печати…");
    private final JProgressBar progress = new JProgressBar();

    PrintProgressDialog(Window owner, int totalGroups) {
        super(owner, "Отправка ценников на печать", Dialog.ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        progress.setIndeterminate(true);
        progress.setStringPainted(false);
        JPanel content = new JPanel(new BorderLayout(12, 16));
        content.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        content.add(message, BorderLayout.NORTH);
        content.add(progress, BorderLayout.CENTER);
        JLabel hint = new JLabel("Пожалуйста, подождите и не запускайте печать повторно.");
        content.add(hint, BorderLayout.SOUTH);
        setContentPane(content);
        setSize(460, 140);
        setResizable(false);
        setLocationRelativeTo(owner);
        message.setText("Идёт отправка заданий: 0 из " + totalGroups + "…");
    }

    void showProgress(String text, int completedGroups, int totalGroups) {
        message.setText(text);
        progress.setIndeterminate(false);
        progress.setMaximum(Math.max(1, totalGroups));
        progress.setValue(completedGroups);
        progress.setStringPainted(true);
        progress.setString(completedGroups + " / " + totalGroups);
    }
}
