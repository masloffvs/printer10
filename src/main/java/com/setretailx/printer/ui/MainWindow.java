package com.setretailx.printer.ui;

import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.model.Product;
import com.setretailx.printer.model.PrintBatch;
import com.setretailx.printer.model.PrintHistoryEntry;
import com.setretailx.printer.model.PriceType;
import com.setretailx.printer.model.ScannedProbe;
import com.setretailx.printer.repository.ProductRepository;
import com.setretailx.printer.repository.PrintHistoryRepository;
import com.setretailx.printer.service.SetRetailXConnector;
import com.setretailx.printer.service.ExchangeEvent;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import javax.swing.SwingUtilities;

/** Main application shell; feature screens and MDI window management live in separate classes. */
@SuppressWarnings("serial")
public final class MainWindow extends JFrame {
    private final ProductRepository products = new ProductRepository();
    private final PrintHistoryRepository printHistory = new PrintHistoryRepository();
    private final MdiWorkspace workspace = new MdiWorkspace();
    private final List<QueueEntry> entries = new ArrayList<>();
    private final ScannerPanel scanner = new ScannerPanel(this::submitScan);
    private final ComparisonPanel comparison = new ComparisonPanel();
    private final SearchPanel search = new SearchPanel(this::search, this::updateSearchSelection,
            this::addSelectedSearchResults, this::showProductCard);
    private final QueuePanel queue = new QueuePanel(this::assignSelected, this::removeSelected,
            this::clearQueue, this::moveSelected);
    private final ExchangePanel exchange = new ExchangePanel(this::checkNetworkConnection);
    private final PrintHistoryPanel history = new PrintHistoryPanel(this::restoreBatch, this::refreshHistory);
    private final SetRetailXConnector network = new SetRetailXConnector(event -> SwingUtilities.invokeLater(() -> exchange.append(event)));
    private final javax.swing.JLabel status = new javax.swing.JLabel("Готово");
    private List<Product> searchResults = new ArrayList<>();

    public MainWindow() {
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
        workspace.addWindow("history", history, 760, 420);
        workspace.addWindow("tasks", new MigrationNoticePanel(
                "Планировщик фоновых задач будет перенесён отдельным этапом."), 680, 400);
        scanner.registerRecipient("comparison", "Сверка ценников");
        scanner.registerRecipient("search", "Поиск товаров");
        scanner.registerRecipient("queue", "Очередь печати");
        workspace.setWindowVisibilityListener(scanner::setRecipientOpen);
        workspace.showWindow("scanner");
        refreshHistory();
        log("Java UI запущен. Локальная база: " + AppConfig.DB_PATH);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) { network.close(); }
        });
    }

    private void checkNetworkConnection() {
        exchange.setBusy(true);
        exchange.setStatus("Проверка соединения…");
        network.checkConnection().whenComplete((response, error) -> SwingUtilities.invokeLater(() -> {
            exchange.setBusy(false);
            if (error != null) {
                Throwable cause = error;
                while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                        && cause.getCause() != null) cause = cause.getCause();
                String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                exchange.setStatus("Ошибка подключения");
                log("Ошибка подключения SetRetailX: " + message);
            } else {
                exchange.setStatus("Подключено");
                status.setText("Соединение с SetRetailX проверено");
            }
        }));
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
            status.setText(error.getMessage());
            log("Ошибка разбора скана: " + error.getMessage());
            return;
        }

        int receivers = 0;
        if (scanner.sendsTo("queue") && !probe.hasPriceData()) {
            receivers++;
            receiveProductScan(probe);
        }
        if (scanner.sendsTo("comparison") && probe.hasPriceData()) {
            receivers++;
            receivePriceScan(probe);
        }
        if (scanner.sendsTo("search") && !probe.hasPriceData() && !probe.barcode().isEmpty()) {
            receivers++;
            search.setQuery(probe.barcode());
            search();
        }
        if (receivers == 0) status.setText("Для этого типа скана нет отмеченных открытых окон-получателей.");
        else status.setText("Скан передан в окна: " + receivers);
        scanner.focusInput();
    }

    private void receiveProductScan(ScannedProbe probe) {
        if (probe.barcode().isEmpty()) {
            status.setText("В скане товара не заполнен barcode.");
            return;
        }
        status.setText("Ищем " + probe.barcode() + " в локальной базе…");
        runBackground(() -> products.findByBarcode(probe.barcode()), product -> {
            if (product == null) {
                status.setText("Штрихкод " + probe.barcode() + " не найден в локальной базе.");
                return;
            }
            entries.add(new QueueEntry(product));
            refreshQueue();
            status.setText("Добавлен товар из локальной базы: " + product.name());
        }, error -> status.setText("Ошибка локального поиска: " + error));
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
        runBackground(() -> products.search(query), results -> {
            searchResults = new ArrayList<>(results);
            search.showResults(results);
            search.setStatus("Найдено товаров: " + results.size());
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

    private void showProductCard(Product product) {
        if (product == null) return;
        ProductCardDialog dialog = new ProductCardDialog(this, product);
        dialog.setVisible(true);
    }

    private void assignSelected(List<Integer> rows, AppConfig.PrintFormat format,
                                PriceType priceType, Integer amount) {
        for (int row : rows) {
            if (row >= 0 && row < entries.size()) {
                entries.get(row).format = format;
                entries.get(row).priceType = priceType;
                entries.get(row).amount = amount;
            }
        }
        refreshQueue();
    }

    private void moveSelected(List<Integer> rows, AppConfig.PrintFormat format) {
        for (int row : rows) {
            if (row >= 0 && row < entries.size()) entries.get(row).format = format;
        }
        refreshQueue();
    }

    private void removeSelected(List<Integer> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            int row = rows.get(i);
            if (row >= 0 && row < entries.size()) entries.remove(row);
        }
        refreshQueue();
    }

    private void clearQueue() {
        if (entries.isEmpty() || JOptionPane.showConfirmDialog(this, "Очистить очередь печати?", "Очередь",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        entries.clear();
        refreshQueue();
    }

    private void refreshHistory() {
        history.setMessage("Загружаем историю…");
        runBackground(printHistory::batches, history::showBatches,
                error -> history.setMessage("Не удалось загрузить историю: " + error));
    }

    private void restoreBatch(PrintBatch batch) {
        List<PrintHistoryEntry> toRestore = batch.entries();
        if ("Частично отправлено".equals(batch.status())) {
            java.util.Set<Integer> successful = new java.util.HashSet<>(batch.successfulIndexes());
            List<PrintHistoryEntry> pending = new ArrayList<>();
            for (int i = 0; i < batch.entries().size(); i++) {
                if (!successful.contains(i)) pending.add(batch.entries().get(i));
            }
            toRestore = pending;
        }
        for (PrintHistoryEntry archived : toRestore) {
            QueueEntry entry = new QueueEntry(archived.product());
            String key = archived.formatKey();
            entry.format = AppConfig.formatByKey(key);
            entry.amount = archived.amount();
            entry.quantity = archived.quantity();
            entry.priceType = archived.priceType();
            entries.add(entry);
        }
        refreshQueue();
        workspace.showWindow("queue");
        status.setText("Восстановлено товаров из истории: " + toRestore.size());
    }

    private void refreshQueue() {
        List<QueuePanel.QueueItem> items = new ArrayList<>();
        int assigned = 0;
        for (QueueEntry entry : entries) {
            if (entry.format != null) assigned++;
            items.add(new QueuePanel.QueueItem(entry.product.name(), entry.product.barcode(),
                    entry.product.stCode(), entry.format, entry.priceType, entry.amount, entry.quantity));
        }
        queue.showEntries(items);
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
        private PriceType priceType = PriceType.PROMO;

        private QueueEntry(Product product) { this.product = product; }

        private PrintHistoryEntry snapshot() {
            return PrintHistoryEntry.fromQueue(product, format, amount, quantity, priceType);
        }
    }
}
