package com.setretailx.printer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/** Queue table and print-format assignment controls. */
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
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        top.add(new JLabel("Формат:"));
        top.add(format);
        top.add(new JLabel("Тираж:"));
        top.add(amount);
        JButton assign = new JButton("Назначить выбранным");
        assign.addActionListener(event -> assignSelected.run());
        top.add(assign);
        add(top, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.add(summary);
        JButton remove = new JButton("Удалить выбранное");
        remove.addActionListener(event -> removeSelected.run());
        footer.add(remove);
        JButton clear = new JButton("Очистить очередь");
        clear.addActionListener(event -> clearQueue.run());
        footer.add(clear);
        JButton print = new JButton("Печать назначенных");
        print.setEnabled(false);
        print.setToolTipText("Интеграция с JSON-RPC SetRetailX будет подключена следующим этапом миграции.");
        footer.add(print);
        add(footer, BorderLayout.SOUTH);
    }

    DefaultTableModel model() { return model; }
    int[] selectedRows() { return table.getSelectedRows(); }
    AppConfig.PrintFormat selectedFormat() { return (AppConfig.PrintFormat) format.getSelectedItem(); }
    int selectedAmount() { return (Integer) amount.getValue(); }
    void setSummary(String text) { summary.setText(text); }

    private static JComboBox<AppConfig.PrintFormat> formatCombo() {
        return new JComboBox<>(AppConfig.PRINT_FORMATS.toArray(AppConfig.PrintFormat[]::new));
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }
}
