package com.setretailx.printer;

import javax.swing.BorderFactory;
import javax.swing.DefaultListSelectionModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Main Swing shell. Scanner events are dispatched once to subscribed screens. */
@SuppressWarnings("serial")
final class MainWindow extends JFrame {
    private final CatalogService catalog = new CatalogService();
    private final List<QueueEntry> entries = new ArrayList<>();
    private final DefaultTableModel queueModel = readOnlyModel(
            "№", "Товар", "Штрихкод", "ST-код", "Формат", "Тираж", "Количество");
    private final JTable queueTable = new JTable(queueModel);
    private final DefaultTableModel searchModel = readOnlyModel(
            "Товар", "Артикул", "Штрихкод", "Цена", "Остаток");
    private final JTable searchTable = new JTable(searchModel);
    private final List<Product> searchResults = new ArrayList<>();
    private final DefaultTableModel comparisonModel = readOnlyModel(
            "Статус", "Артикул", "Штрихкод", "Ярлык 1", "Ярлык 2", "Ярлык 3", "Ярлык 4", "Результат");
    private final JTextArea exchangeLog = new JTextArea();
    private final JLabel status = new JLabel("Готово");

    private final JCheckBox scannerSubscription = new JCheckBox("Добавлять товары из общего потока в очередь печати", true);
    private final JCheckBox compareSubscription = new JCheckBox("Принимать ценники из общего потока сканера", true);
    private final JCheckBox searchSubscription = new JCheckBox("Искать товары по штрихкоду из общего потока", false);
    private final JTextField scannerInput = new JTextField(32);
    private final JComboBox<AppConfig.PrintFormat> scannerFormat = new JComboBox<>(AppConfig.PRINT_FORMATS.toArray(AppConfig.PrintFormat[]::new));
    private final JSpinner scannerAmount = new JSpinner(new SpinnerNumberModel(1, 1, 999, 1));
    private final JLabel scannerStatus = new JLabel("Сканируйте штрихкод или ценник в общее поле.");
    private final JLabel queueSummary = new JLabel("В очереди: 0");
    private final JTextField searchInput = new JTextField(28);
    private final JLabel searchStatus = new JLabel("Введите название, артикул или штрихкод для локального поиска.");
    private final JLabel comparisonStatus = new JLabel("Ожидаю сканирование ценников.");

    MainWindow() {
        super("SetRetailX — Печать ценников (Java)");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1120, 720);
        setMinimumSize(new java.awt.Dimension(850, 560));
        setLocationRelativeTo(null);
        setJMenuBar(createMenuBar());

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Единый сканер", buildScannerPanel());
        tabs.addTab("Сверка ценников", buildComparisonPanel());
        tabs.addTab("Поиск товаров", buildSearchPanel());
        tabs.addTab("Очередь печати", buildQueuePanel());
        tabs.addTab("Обмен с SetRetailX", buildExchangePanel());
        tabs.addTab("История печати", migrationNotice("Локальная история партий будет перенесена вместе с печатью."));
        tabs.addTab("Долгие задачи", migrationNotice("Планировщик фоновых задач будет перенесён после сетевого клиента."));

        add(tabs, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        add(status, BorderLayout.SOUTH);
        log("Java UI запущен. Локальная база: " + AppConfig.DB_PATH);
    }

    private JPanel buildScannerPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        GridBagConstraints c = constraints();
        c.gridx = 0; c.gridy = 0; c.gridwidth = 3; c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(scannerSubscription, c);
        c.gridy++;
        panel.add(new JLabel("Общий поток сканера — Enter отправит событие всем подходящим подписанным окнам:"), c);
        c.gridy++;
        c.gridwidth = 2;
        panel.add(scannerInput, c);
        c.gridx = 2; c.gridwidth = 1; c.weightx = 0;
        JButton send = new JButton("Передать скан");
        send.addActionListener(event -> submitScan());
        panel.add(send, c);
        scannerInput.addActionListener(event -> submitScan());

        c.gridx = 0; c.gridy++; c.gridwidth = 1; c.weightx = 0;
        panel.add(new JLabel("Формат по умолчанию:"), c);
        c.gridx = 1; c.weightx = 0.5;
        panel.add(scannerFormat, c);
        c.gridx = 2; c.weightx = 0;
        JPanel amount = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        amount.add(new JLabel("Тираж:")); amount.add(scannerAmount);
        panel.add(amount, c);
        c.gridx = 0; c.gridy++; c.gridwidth = 3;
        JButton assign = new JButton("Назначить всем неназначенным");
        assign.addActionListener(event -> assignUnassigned());
        panel.add(assign, c);
        c.gridy++;
        scannerStatus.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        panel.add(scannerStatus, c);
        c.gridy++; c.weighty = 1; panel.add(new JPanel(), c);
        return panel;
    }

    private JPanel buildComparisonPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(compareSubscription, BorderLayout.NORTH);
        JTable table = new JTable(comparisonModel);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.add(comparisonStatus, BorderLayout.CENTER);
        JButton clear = new JButton("Очистить результаты");
        clear.addActionListener(event -> { comparisonModel.setRowCount(0); comparisonStatus.setText("Ожидаю сканирование ценников."); });
        footer.add(clear, BorderLayout.EAST);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildSearchPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(searchSubscription, BorderLayout.NORTH);
        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        form.add(searchInput);
        JButton find = new JButton("Найти");
        find.addActionListener(event -> search());
        searchInput.addActionListener(event -> search());
        form.add(find);
        top.add(form, BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);
        searchTable.setSelectionMode(DefaultListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        searchTable.getSelectionModel().addListSelectionListener(event -> updateSearchSelection());
        panel.add(new JScrollPane(searchTable), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.add(searchStatus, BorderLayout.CENTER);
        JButton add = new JButton("Добавить выбранные в очередь");
        add.addActionListener(event -> addSelectedSearchResults());
        footer.add(add, BorderLayout.EAST);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildQueuePanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JComboBox<AppConfig.PrintFormat> format = formatCombo();
        JSpinner amount = new JSpinner(new SpinnerNumberModel(1, 1, 999, 1));
        top.add(new JLabel("Формат:")); top.add(format);
        top.add(new JLabel("Тираж:")); top.add(amount);
        JButton assign = new JButton("Назначить выбранным");
        assign.addActionListener(event -> assignSelected(format, amount));
        top.add(assign);
        panel.add(top, BorderLayout.NORTH);
        queueTable.setSelectionMode(DefaultListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        panel.add(new JScrollPane(queueTable), BorderLayout.CENTER);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.add(queueSummary);
        JButton remove = new JButton("Удалить выбранное");
        remove.addActionListener(event -> removeSelected());
        footer.add(remove);
        JButton clear = new JButton("Очистить очередь");
        clear.addActionListener(event -> clearQueue());
        footer.add(clear);
        JButton print = new JButton("Печать назначенных");
        print.setEnabled(false);
        print.setToolTipText("Интеграция с JSON-RPC SetRetailX будет подключена следующим этапом миграции.");
        footer.add(print);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildExchangePanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JLabel notice = new JLabel("Журнал запросов и ответов появится после переноса авторизации и JSON-RPC клиента.");
        panel.add(notice, BorderLayout.NORTH);
        exchangeLog.setEditable(false);
        panel.add(new JScrollPane(exchangeLog), BorderLayout.CENTER);
        return panel;
    }

    private JPanel migrationNotice(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        panel.add(new JLabel("<html><div style='width:520px'><h2>Перенос в процессе</h2>" + text
                + "<br><br>Эти функции остаются в Python-версии до завершения соответствующего этапа.</div></html>"), BorderLayout.NORTH);
        return panel;
    }

    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("Файл");
        JMenuItem exit = new JMenuItem("Выход");
        exit.addActionListener(event -> dispose());
        file.add(exit);
        JMenu view = new JMenu("Вид");
        for (String tab : new String[]{"Единый сканер", "Сверка ценников", "Поиск товаров", "Очередь печати", "Обмен с SetRetailX", "История печати", "Долгие задачи"}) {
            JMenuItem item = new JMenuItem(tab);
            item.addActionListener(event -> {
                JTabbedPane tabs = (JTabbedPane) getContentPane().getComponent(0);
                tabs.setSelectedIndex(indexOfTab(tabs, tab));
            });
            view.add(item);
        }
        JMenu help = new JMenu("Справка");
        JMenuItem about = new JMenuItem("О программе");
        about.addActionListener(event -> JOptionPane.showMessageDialog(this,
                "SetRetailX — сканирование товаров и печать ценников.\nJava + Swing + FlatLaf",
                "О программе", JOptionPane.INFORMATION_MESSAGE));
        help.add(about);
        bar.add(file); bar.add(view); bar.add(help);
        return bar;
    }

    private int indexOfTab(JTabbedPane tabs, String title) {
        for (int i = 0; i < tabs.getTabCount(); i++) if (tabs.getTitleAt(i).equals(title)) return i;
        return 0;
    }

    private void submitScan() {
        String raw = scannerInput.getText().trim();
        if (raw.isEmpty()) return;
        scannerInput.setText("");
        ScannedProbe probe;
        try {
            probe = ScannedProbe.fromText(raw);
            if (probe.hasPriceData()) probe.pricesAsDecimals();
        } catch (IllegalArgumentException error) {
            scannerStatus.setText(error.getMessage());
            log("Ошибка разбора скана: " + error.getMessage());
            return;
        }

        int receivers = 0;
        if (scannerSubscription.isSelected() && !probe.hasPriceData()) {
            receivers++;
            receiveProductScan(probe);
        }
        if (compareSubscription.isSelected() && probe.hasPriceData()) {
            receivers++;
            receivePriceScan(probe);
        }
        if (searchSubscription.isSelected() && !probe.hasPriceData() && !probe.barcode().isEmpty()) {
            receivers++;
            searchInput.setText(probe.barcode());
            search();
        }
        if (receivers == 0) scannerStatus.setText("Для этого типа скана нет подписанных окон-получателей.");
        else status.setText("Скан передан в окна: " + receivers);
        scannerInput.requestFocusInWindow();
    }

    private void receiveProductScan(ScannedProbe probe) {
        if (probe.barcode().isEmpty()) {
            scannerStatus.setText("В скане товара не заполнен barcode.");
            return;
        }
        scannerStatus.setText("Ищем " + probe.barcode() + " в локальной базе…");
        runBackground(() -> catalog.findByBarcode(probe.barcode()), product -> {
            if (product == null) {
                scannerStatus.setText("Штрихкод " + probe.barcode() + " не найден в локальной базе.");
                return;
            }
            entries.add(new QueueEntry(product));
            refreshQueue();
            scannerStatus.setText("Добавлен товар из локальной базы: " + product.name());
        }, error -> scannerStatus.setText("Ошибка локального поиска: " + error));
    }

    private void receivePriceScan(ScannedProbe probe) {
        String[] prices = {probe.price1(), probe.price2(), probe.price3(), probe.price4()};
        comparisonModel.addRow(new Object[]{"Ожидает сверки", "—", probe.barcode(), prices[0], prices[1], prices[2], prices[3], "Нужен SetRetailX"});
        comparisonStatus.setText("Ценник принят. Сверка с SetRetailX появится после переноса JSON-RPC клиента.");
        log("Скан ценника: barcode=" + probe.barcode() + ", prices=" + String.join(" / ", prices));
    }

    private void search() {
        String query = searchInput.getText().trim();
        if (query.isEmpty()) {
            searchStatus.setText("Введите название, артикул или штрихкод.");
            return;
        }
        searchStatus.setText("Ищем в локальной базе…");
        runBackground(() -> catalog.search(query), products -> {
            searchResults.clear(); searchResults.addAll(products);
            searchModel.setRowCount(0);
            for (Product product : products) searchModel.addRow(new Object[]{product.name(), product.vendorCode(), product.barcode(), product.price().isEmpty() ? "—" : product.price(), product.totalQuantity().isEmpty() ? "—" : product.totalQuantity()});
            searchStatus.setText("Найдено товаров: " + products.size());
        }, error -> searchStatus.setText("Ошибка поиска: " + error));
    }

    private void updateSearchSelection() {
        if (!searchTable.getSelectionModel().getValueIsAdjusting()) {
            searchStatus.setText(searchTable.getSelectedRowCount() == 0
                    ? "Выберите товары для добавления в очередь."
                    : "Выбрано товаров: " + searchTable.getSelectedRowCount());
        }
    }

    private void addSelectedSearchResults() {
        for (int row : searchTable.getSelectedRows()) if (row >= 0 && row < searchResults.size()) entries.add(new QueueEntry(searchResults.get(row)));
        refreshQueue();
        status.setText("Добавлено товаров в очередь: " + searchTable.getSelectedRowCount());
    }

    private void assignUnassigned() {
        AppConfig.PrintFormat format = (AppConfig.PrintFormat) scannerFormat.getSelectedItem();
        int amount = (Integer) scannerAmount.getValue();
        int count = 0;
        for (QueueEntry entry : entries) if (entry.format == null) { entry.format = format; entry.amount = amount; count++; }
        refreshQueue();
        scannerStatus.setText("Назначено неназначенных товаров: " + count);
    }

    private void assignSelected(JComboBox<AppConfig.PrintFormat> format, JSpinner amount) {
        AppConfig.PrintFormat selectedFormat = (AppConfig.PrintFormat) format.getSelectedItem();
        int quantity = (Integer) amount.getValue();
        for (int row : queueTable.getSelectedRows()) if (row >= 0 && row < entries.size()) {
            entries.get(row).format = selectedFormat; entries.get(row).amount = quantity;
        }
        refreshQueue();
    }

    private void removeSelected() {
        int[] rows = queueTable.getSelectedRows();
        for (int i = rows.length - 1; i >= 0; i--) if (rows[i] >= 0 && rows[i] < entries.size()) entries.remove(rows[i]);
        refreshQueue();
    }

    private void clearQueue() {
        if (entries.isEmpty() || JOptionPane.showConfirmDialog(this, "Очистить очередь печати?", "Очередь", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        entries.clear(); refreshQueue();
    }

    private void refreshQueue() {
        queueModel.setRowCount(0);
        int assigned = 0;
        for (int i = 0; i < entries.size(); i++) {
            QueueEntry entry = entries.get(i);
            if (entry.format != null) assigned++;
            queueModel.addRow(new Object[]{i + 1, entry.product.name(), entry.product.barcode(), entry.product.stCode(),
                    entry.format == null ? "Не назначен" : entry.format.name(), entry.amount == null ? "—" : entry.amount, entry.quantity});
        }
        queueSummary.setText("В очереди: " + entries.size() + " · назначено: " + assigned + " · без формата: " + (entries.size() - assigned));
    }

    private JComboBox<AppConfig.PrintFormat> formatCombo() {
        return new JComboBox<>(AppConfig.PRINT_FORMATS.toArray(AppConfig.PrintFormat[]::new));
    }

    private void log(String message) {
        exchangeLog.append("[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message + "\n");
        exchangeLog.setCaretPosition(exchangeLog.getDocument().getLength());
    }

    private <T> void runBackground(SqlWork<T> work, Success<T> success, Failure failure) {
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return work.run(); }
            @Override protected void done() {
                try { success.accept(get()); }
                catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    log("Ошибка: " + message);
                    failure.accept(message);
                }
            }
        }.execute();
    }

    private static GridBagConstraints constraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(7, 6, 7, 6);
        c.anchor = GridBagConstraints.WEST;
        return c;
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }

    @FunctionalInterface private interface SqlWork<T> { T run() throws Exception; }
    @FunctionalInterface private interface Success<T> { void accept(T value); }
    @FunctionalInterface private interface Failure { void accept(String message); }

    private static final class QueueEntry {
        private final Product product;
        private AppConfig.PrintFormat format;
        private Integer amount;
        private int quantity = 1;
        private QueueEntry(Product product) { this.product = product; }
    }
}
