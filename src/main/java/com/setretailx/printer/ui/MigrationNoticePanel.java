package com.setretailx.printer.ui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/** Placeholder used for functionality that is not migrated to Java yet. */
@SuppressWarnings("serial")
final class MigrationNoticePanel extends JPanel {
    MigrationNoticePanel(String text) {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        add(new JLabel("<html><div style='width:520px'><h2>Перенос в процессе</h2>" + text
                + "<br><br>Эти функции остаются в Python-версии до завершения соответствующего этапа.</div></html>"),
                BorderLayout.NORTH);
    }
}
