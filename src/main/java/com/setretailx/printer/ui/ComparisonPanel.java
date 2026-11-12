package com.setretailx.printer.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;

/** Receives scanned price labels for comparison. */
@SuppressWarnings("serial")
final class ComparisonPanel extends JPanel {
    private final DefaultTableModel model = readOnlyModel(
            "Статус", "Артикул", "Штрихкод", "Ярлык 1", "Ярлык 2", "Ярлык 3", "Ярлык 4", "Результат");
    private final JLabel status = new JLabel("Ожидаю сканирование ценников.");

    ComparisonPanel() {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.add(status, BorderLayout.CENTER);
        JButton clear = new JButton("Очистить результаты");
        clear.addActionListener(event -> {
            if (model.getRowCount() > 0 && JOptionPane.showConfirmDialog(this,
                    "Удалить все результаты сверки?", "Подтверждение удаления",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) clear();
        });
        footer.add(clear, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    DefaultTableModel model() { return model; }
    void setStatus(String message) { status.setText(message); }
    void clear() {
        model.setRowCount(0);
        setStatus("Ожидаю сканирование ценников.");
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }
}
