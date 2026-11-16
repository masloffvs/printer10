package com.setretailx.printer.ui;

import javax.swing.JTabbedPane;
import java.awt.BorderLayout;

/** One navigation destination with separate archives for changed labels and printed reports. */
@SuppressWarnings("serial")
final class PrintChangesHistoryPanel extends javax.swing.JPanel {
    private final ChangedLabelPrintHistoryPanel labels;
    private final PriceChangePrintHistoryPanel reports;

    PrintChangesHistoryPanel(ChangedLabelPrintHistoryPanel labels, PriceChangePrintHistoryPanel reports) {
        super(new BorderLayout());
        this.labels = labels;
        this.reports = reports;
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Изменённые ценники", labels);
        tabs.addTab("Отчёты об изменениях", reports);
        add(tabs, BorderLayout.CENTER);
    }

    void close() {
        labels.close();
        reports.close();
    }
}
