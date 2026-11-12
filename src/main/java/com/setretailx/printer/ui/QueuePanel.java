package com.setretailx.printer.ui;

import com.setretailx.printer.config.AppConfig;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Queue table; every queue action is available from its right-click menu. */
@SuppressWarnings("serial")
final class QueuePanel extends JPanel {
    private final DefaultTableModel model = readOnlyModel("№", "Товар", "Штрихкод", "ST-код", "Формат", "Тираж", "Количество");
    private final JTable table = new JTable(model);
    private final JLabel summary = new JLabel("В очереди: 0");
    private final JComboBox<AppConfig.PrintFormat> format = formatCombo();
    private final JSpinner amount = new JSpinner(new SpinnerNumberModel(1, 1, 999, 1));

    QueuePanel(Runnable assignSelected, Runnable removeSelected, Runnable clearQueue) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JPopupMenu contextMenu = new JPopupMenu();
        JMenuItem assign = new JMenuItem("Назначить формат и тираж выбранным…");
        assign.addActionListener(event -> {
            if (table.getSelectedRowCount() > 0 && showAssignmentDialog()) assignSelected.run();
        });
        contextMenu.add(assign);
        JMenuItem remove = new JMenuItem("Удалить выбранное");
        remove.addActionListener(event -> removeSelected.run());
        contextMenu.add(remove);
        JMenuItem clear = new JMenuItem("Очистить очередь…");
        clear.addActionListener(event -> clearQueue.run());
        contextMenu.add(clear);
        JMenuItem print = new JMenuItem("Печать назначенных");
        print.setEnabled(false);
        print.setToolTipText("Интеграция с JSON-RPC SetRetailX будет подключена следующим этапом миграции.");
        contextMenu.add(print);
        table.addMouseListener(new MouseAdapter() {
            private void showMenu(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                int row = table.rowAtPoint(event.getPoint());
                if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
                contextMenu.show(table, event.getX(), event.getY());
            }
            @Override public void mousePressed(MouseEvent event) { showMenu(event); }
            @Override public void mouseReleased(MouseEvent event) { showMenu(event); }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(summary, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    DefaultTableModel model() { return model; }
    int[] selectedRows() { return table.getSelectedRows(); }
    AppConfig.PrintFormat selectedFormat() { return (AppConfig.PrintFormat) format.getSelectedItem(); }
    int selectedAmount() { return (Integer) amount.getValue(); }
    void setSummary(String text) { summary.setText(text); }

    private boolean showAssignmentDialog() {
        JPanel options = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        options.add(new javax.swing.JLabel("Формат:"));
        options.add(format);
        options.add(new javax.swing.JLabel("Тираж:"));
        options.add(amount);
        return JOptionPane.showConfirmDialog(this, options, "Назначить выбранным",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private static JComboBox<AppConfig.PrintFormat> formatCombo() {
        return new JComboBox<>(AppConfig.PRINT_FORMATS.toArray(AppConfig.PrintFormat[]::new));
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }
}
