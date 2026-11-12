package com.setretailx.printer;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;

/** Main application shell; feature screens and MDI window management live in separate classes. */
@SuppressWarnings("serial")
final class MainWindow extends JFrame {
    private final CatalogService catalog = new CatalogService();
    private final MdiWorkspace workspace = new MdiWorkspace();
    private final List<QueueEntry> entries = new ArrayList<>();
    private final ScannerPanel scanner = new ScannerPanel(this::submitScan, this::assignUnassigned);
    private final ComparisonPanel comparison = new ComparisonPanel();
    private final SearchPanel search = new SearchPanel(this::search, this::updateSearchSelection,
            this::addSelectedSearchResults);
    private final QueuePanel queue = new QueuePanel(this::assignSelected, this::removeSelected,
            this::clearQueue);
    private final ExchangePanel exchange = new ExchangePanel();
    private final javax.swing.JLabel status = new javax.swing.JLabel("Готово");
    private List<Product> searchResults = new ArrayList<>();

    MainWindow() {
        super("SetRetailX — Печать ценников (Java)");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1120, 720);
        setMinimumSize(new java.awt.Dimension(850, 560));
        setLocationRelativeTo(null);
        setJMenuBar(workspace.createMenuBar(this::dispose, this::showAbout));
        add(workspace, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        add(status, BorderLayout.SOUTH);

        workspace.addWindow("scanner", scanner, 560, 270);
        workspace.addWindow("comparison", comparison, 900, 470);
        workspace.addWindow("search", search, 760, 480);
        workspace.addWindow("queue", queue, 760, 440);
        workspace.addWindow("exchange", exchange, 650, 400);
        workspace.addWindow("history", new MigrationNoticePanel(
                "Локальная история партий будет перенесена вместе с печатью."), 620, 360);
        workspace.addWindow("tasks", new MigrationNoticePanel(
                "Планировщик фоновых задач будет перенесён после сетевого клиента."), 680, 400);
        workspace.showWindow("scanner");
        log("Java UI запущен. Локальная база: " + AppConfig.DB_PATH);
    }

    private void submitScan() {
        String raw = scanner.inputText().trim();
        if (raw.isEmpty()) return;
        scanner.clearInput();
        ScannedProbe probe;
        try {
            probe = ScannedProbe.fromText(raw);
            if (probe.hasPriceData()) probe.pricesAsDecimals();
        } catch (IllegalArgumentException error) {
            scanner.setStatus(error.getMessage());
            log("Ошибка разбора скана: " + error.getMessage());
            return;
        }

        int receivers = 0;
        if (scanner.isSubscribed() && !probe.hasPriceData()) {
            receivers++;
            receiveProductScan(probe);
        }
        if (comparison.isSubscribed() && probe.hasPriceData()) {
            receivers++;
            receivePriceScan(probe);
        }
        if (search.isSubscribed() && !probe.hasPriceData() && !probe.barcode().isEmpty()) {
            receivers++;
            search.setQuery(probe.barcode());
            search();
        }
        if (receivers == 0) scanner.setStatus("Для этого типа скана нет подписанных окон-получателей.");
        else status.setText("Скан передан в окна: " + receivers);
        scanner.focusInput();
    }

    private void receiveProductScan(ScannedProbe probe) {
        if (probe.barcode().isEmpty()) {
            scanner.setStatus("В скане товара не заполнен barcode.");
            return;
        }
        scanner.setStatus("Ищем " + probe.barcode() + " в локальной базе…");
        runBackground(() -> catalog.findByBarcode(probe.barcode()), product -> {
            if (product == null) {
                scanner.setStatus("Штрихкод " + probe.barcode() + " не найден в локальной базе.");
                return;
            }
            entries.add(new QueueEntry(product));
            refreshQueue();
            scanner.setStatus("Добавлен товар из локальной базы: " + product.name());
        }, error -> scanner.setStatus("Ошибка локального поиска: " + error));
    }

    private void receivePriceScan(ScannedProbe probe) {
        String[] prices = {probe.price1(), probe.price2(), probe.price3(), probe.price4()};
        comparison.model().addRow(new Object[]{"Ожидает сверки", "—", probe.barcode(),
                prices[0], prices[1], prices[2], prices[3], "Нужен SetRetailX"});
        comparison.setStatus("Ценник принят. Сверка с SetRetailX появится после переноса JSON-RPC клиента.");
        log("Скан ценника: barcode=" + probe.barcode() + ", prices=" + String.join(" / ", prices));
    }

    private void search() {
        String query = search.query();
        if (query.isEmpty()) {
            search.setStatus("Введите название, артикул или штрихкод.");
            return;
        }
        search.setStatus("Ищем в локальной базе…");
        runBackground(() -> catalog.search(query), products -> {
            searchResults = new ArrayList<>(products);
            search.showResults(products);
            search.setStatus("Найдено товаров: " + products.size());
        }, error -> search.setStatus("Ошибка поиска: " + error));
    }

    private void updateSearchSelection() {
        search.setStatus(search.selectedCount() == 0
                ? "Выберите товары для добавления в очередь."
                : "Выбрано товаров: " + search.selectedCount());
    }

    private void addSelectedSearchResults() {
        int selectedCount = search.selectedCount();
        for (int row : search.selectedRows()) {
            if (row >= 0 && row < searchResults.size()) entries.add(new QueueEntry(searchResults.get(row)));
        }
        refreshQueue();
        status.setText("Добавлено товаров в очередь: " + selectedCount);
    }

    private void assignUnassigned() {
        AppConfig.PrintFormat format = scanner.selectedFormat();
        int amount = scanner.amount();
        int count = 0;
        for (QueueEntry entry : entries) {
            if (entry.format == null) {
                entry.format = format;
                entry.amount = amount;
                count++;
            }
        }
        refreshQueue();
        scanner.setStatus("Назначено неназначенных товаров: " + count);
    }

    private void assignSelected() {
        AppConfig.PrintFormat format = queue.selectedFormat();
        int amount = queue.selectedAmount();
        for (int row : queue.selectedRows()) {
            if (row >= 0 && row < entries.size()) {
                entries.get(row).format = format;
                entries.get(row).amount = amount;
            }
        }
        refreshQueue();
    }

    private void removeSelected() {
        int[] rows = queue.selectedRows();
        for (int i = rows.length - 1; i >= 0; i--) {
            if (rows[i] >= 0 && rows[i] < entries.size()) entries.remove(rows[i]);
        }
        refreshQueue();
    }

    private void clearQueue() {
        if (entries.isEmpty() || JOptionPane.showConfirmDialog(this, "Очистить очередь печати?", "Очередь",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        entries.clear();
        refreshQueue();
    }

    private void refreshQueue() {
        DefaultTableModel model = queue.model();
        model.setRowCount(0);
        int assigned = 0;
        for (int i = 0; i < entries.size(); i++) {
            QueueEntry entry = entries.get(i);
            if (entry.format != null) assigned++;
            model.addRow(new Object[]{i + 1, entry.product.name(), entry.product.barcode(), entry.product.stCode(),
                    entry.format == null ? "Не назначен" : entry.format.name(),
                    entry.amount == null ? "—" : entry.amount, entry.quantity});
        }
        queue.setSummary("В очереди: " + entries.size() + " · назначено: " + assigned
                + " · без формата: " + (entries.size() - assigned));
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
                "SetRetailX — сканирование товаров и печать ценников.\nJava + Swing + FlatLaf",
                "О программе", JOptionPane.INFORMATION_MESSAGE);
    }

    private void log(String message) { exchange.append(message); }

    private <T> void runBackground(SqlWork<T> work, Success<T> success, Failure failure) {
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return work.run(); }

            @Override protected void done() {
                try {
                    success.accept(get());
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    log("Ошибка: " + message);
                    failure.accept(message);
                }
            }
        }.execute();
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
