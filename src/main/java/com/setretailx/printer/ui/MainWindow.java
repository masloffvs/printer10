package com.setretailx.printer.ui;

import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.model.Product;
import com.setretailx.printer.model.BarInput;
import com.setretailx.printer.model.PrintBatch;
import com.setretailx.printer.model.PrintHistoryEntry;
import com.setretailx.printer.model.PriceType;
import com.setretailx.printer.model.ScannedProbe;
import com.setretailx.printer.repository.ProductRepository;
import com.setretailx.printer.repository.PrintHistoryRepository;
import com.setretailx.printer.repository.AddressStorageRepository;
import com.setretailx.printer.service.SetRetailXConnector;
import com.setretailx.printer.service.ExchangeEvent;
import com.setretailx.printer.service.PriceMonitoringTaskService;
import com.setretailx.printer.service.setretailx.dto.PrintGroupItemDto;
import com.setretailx.printer.service.setretailx.dto.PrintGroupResponseDto;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import javax.swing.SwingUtilities;

/** Main application shell; feature screens and MDI window management live in separate classes. */
@SuppressWarnings("serial")
public final class MainWindow extends JFrame {
    private final ProductRepository products = new ProductRepository();
    private final PrintHistoryRepository printHistory = new PrintHistoryRepository();
    private final AddressStorageRepository addressStorage = new AddressStorageRepository();
    private final MdiWorkspace workspace = new MdiWorkspace();
    private final List<QueueEntry> entries = new ArrayList<>();
    private final ScannerPanel scanner = new ScannerPanel(this::submitScan);
    private final ComparisonPanel comparison = new ComparisonPanel(products);
    private final SearchPanel search = new SearchPanel(this::search, this::updateSearchSelection,
            this::addSelectedSearchResults, this::showProductCard);
    private final QueuePanel queue = new QueuePanel(this::assignSelected, this::removeSelected,
            this::clearQueue, this::moveSelected, this::printAssigned);
    private final ExchangePanel exchange = new ExchangePanel(this::checkNetworkConnection);
    private final PrintHistoryPanel history = new PrintHistoryPanel(this::restoreBatch, this::refreshHistory);
    private final SetRetailXConnector network = new SetRetailXConnector(event -> SwingUtilities.invokeLater(() -> exchange.append(event)));
    private final PriceMonitoringTaskService taskService = new PriceMonitoringTaskService(network);
    private final PriceMonitoringPanel taskPanel = new PriceMonitoringPanel(taskService, workspace);
    private final AddressStoragePanel addressStoragePanel = new AddressStoragePanel(addressStorage);
    private final PriceChangesPanel priceChanges = new PriceChangesPanel(taskService, (marking, name, barcode) ->
            workspace.addDynamicWindow("История цен — " + marking,
                    new PriceHistoryPanel(taskService, marking, name, barcode), 760, 480));
    private final javax.swing.JLabel status = new javax.swing.JLabel("Готово");
    private List<Product> searchResults = new ArrayList<>();
    private boolean printInProgress;

    public MainWindow() {
        super("SetRetailX — Печать ценников (Java)");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1120, 720);
        setMinimumSize(new java.awt.Dimension(850, 560));
        setLocationRelativeTo(null);
        setJMenuBar(workspace.createMenuBar(this::dispose, this::showAbout));
        comparison.setConnector(network);
        comparison.setMismatchHandler(this::enqueueMismatchedProduct);
        add(workspace, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        add(status, BorderLayout.SOUTH);

        workspace.addWindow("scanner", scanner, 560, 270);
        workspace.addWindow("comparison", comparison, 900, 470);
        workspace.addWindow("search", search, 760, 480);
        workspace.addWindow("queue", queue, 760, 440);
        workspace.addWindow("exchange", exchange, 650, 400);
        workspace.addWindow("history", history, 760, 420);
        workspace.addWindow("tasks", taskPanel, 900, 520);
        workspace.addWindow("storage", addressStoragePanel, 1000, 600);
        workspace.addWindow("priceChanges", priceChanges, 900, 520);
        workspace.addWindow("environment", new EnvironmentPanel(() -> {
            network.reloadSettings();
            log("Параметры обслуживания и администрирования сохранены и применены. При необходимости нажмите «Проверить подключение».");
            status.setText("Параметры администрирования обновлены.");
        }), 760, 520);
        scanner.registerRecipient("comparison", "Сверка ценников");
        scanner.registerRecipient("search", "Поиск товаров");
        scanner.registerRecipient("queue", "Очередь печати");
        scanner.registerRecipient("storage", "Адресное хранение (открытая коробка)");
        workspace.setWindowVisibilityListener(scanner::setRecipientOpen);
        workspace.showWindow("scanner");
        refreshHistory();
        log("Java UI запущен. Локальная база: " + AppConfig.DB_PATH);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                taskPanel.close();
                priceChanges.close();
                network.close();
            }
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
        if (scanner.sendsTo("storage") && !probe.hasPriceData()) {
            receivers++;
            receiveStorageScan(probe);
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
        runBackground(() -> {
            Product local = products.findByBarcode(probe.barcode());
            return local != null ? local : network.searchOnlineProduct(probe.barcode()).join().orElse(null);
        }, product -> {
            if (product == null) {
                status.setText("Штрихкод " + probe.barcode() + " не найден.");
                return;
            }
            entries.add(new QueueEntry(product));
            refreshQueue();
            status.setText("Добавлен товар (" + product.source() + "): " + product.name());
        }, error -> status.setText("Ошибка поиска товара: " + error));
    }

    private void receiveStorageScan(ScannedProbe probe) {
        if (probe.barcode().isEmpty()) {
            status.setText("Для адресного хранения нужен скан товара со штрихкодом.");
            return;
        }
        if (!addressStoragePanel.hasOpenBox()) {
            status.setText("Откройте коробку в окне адресного хранения перед сканированием.");
            return;
        }
        status.setText("Ищем товар " + probe.barcode() + " для адресного хранения…");
        runBackground(() -> {
            Product local = products.findByBarcode(probe.barcode());
            return local != null ? local : network.searchOnlineProduct(probe.barcode()).join().orElse(null);
        }, product -> {
            if (product == null) {
                status.setText("Штрихкод " + probe.barcode() + " не найден; товар не добавлен.");
                return;
            }
            addressStoragePanel.addScannedProduct(product, message -> {
                status.setText(message);
                if (message.startsWith("Добавлен")) log(message);
            });
        }, error -> status.setText("Ошибка поиска товара для хранения: " + error));
    }

    private void receivePriceScan(ScannedProbe probe) {
        comparison.receiveScan(probe);
        log("Скан ценника принят для сверки: barcode=" + probe.barcode());
    }

    private void enqueueMismatchedProduct(Product product) {
        entries.add(new QueueEntry(product));
        refreshQueue();
        log("Несовпавший ценник добавлен в очередь без назначения формата и тиража: " + product.name());
    }

    private void search() {
        String query = search.query();
        if (query.isEmpty()) {
            search.setStatus("Введите название, артикул или штрихкод.");
            return;
        }
        String lookup = BarInput.barcode(query).orElse(query);
        search.setStatus("Ищем в локальной базе…");
        runBackground(() -> {
            List<Product> local = products.search(lookup);
            if (!local.isEmpty()) return local;
            SwingUtilities.invokeLater(() -> search.setStatus("Локально не найдено; ищем в SetRetailX…"));
            return network.searchOnlineProduct(lookup).join().map(List::of).orElseGet(List::of);
        }, results -> {
            searchResults = new ArrayList<>(results);
            search.showResults(results);
            search.setStatus("Найдено товаров: " + results.size());
        }, error -> search.setStatus("Ошибка поиска: " + error));
    }

    private void printAssigned() {
        if (printInProgress) {
            status.setText("Задания печати уже отправляются.");
            return;
        }
        Map<PrintGroupKey, List<Integer>> groups = new LinkedHashMap<>();
        List<PrintHistoryEntry> candidates = new ArrayList<>();
        List<QueueEntry> candidateEntries = new ArrayList<>();
        List<Integer> candidateQueueIndexes = new ArrayList<>();
        Map<Integer, PrintHistoryEntry> snapshotsByQueueIndex = new LinkedHashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            QueueEntry entry = entries.get(index);
            if (entry.format == null) continue;
            if (entry.amount == null || entry.amount < 1 || entry.amount > 999) {
                status.setText("У товара «" + entry.product.name() + "» некорректный тираж.");
                return;
            }
            groups.computeIfAbsent(new PrintGroupKey(entry.format, entry.amount), ignored -> new ArrayList<>())
                    .add(index);
            PrintHistoryEntry snapshot = entry.snapshot();
            candidates.add(snapshot);
            candidateEntries.add(entry);
            snapshotsByQueueIndex.put(index, snapshot);
            candidateQueueIndexes.add(index);
        }
        if (candidates.isEmpty()) {
            status.setText("Нет назначенных товаров для печати.");
            return;
        }

        printInProgress = true;
        PrintProgressDialog progressDialog = new PrintProgressDialog(this, groups.size());
        new SwingWorker<PrintOutcome, PrintProgressUpdate>() {
            @Override protected PrintOutcome doInBackground() throws Exception {
                long batchId = printHistory.addBatch(candidates);
                List<Integer> successfulCandidateIndexes = new ArrayList<>();
                List<String> failures = new ArrayList<>();
                int groupIndex = 0;
                for (Map.Entry<PrintGroupKey, List<Integer>> group : groups.entrySet()) {
                    groupIndex++;
                    publish(new PrintProgressUpdate("Отправляем задание " + groupIndex + " из " + groups.size()
                            + " · " + group.getKey().format().name() + "…", groupIndex - 1));
                    PrintGroupKey key = group.getKey();
                    List<PrintGroupItemDto> items = group.getValue().stream()
                            .map(index -> PrintGroupItemDto.from(snapshotsByQueueIndex.get(index).product(),
                                    snapshotsByQueueIndex.get(index).quantity()))
                            .toList();
                    try {
                        PrintGroupResponseDto result = network.printGroup(key.format(), key.amount(), items).join();
                        if (!result.started()) {
                            failures.add("Задание для " + key.format().name() + " не было запущено.");
                            continue;
                        }
                        for (int queueIndex : group.getValue()) {
                            successfulCandidateIndexes.add(candidateQueueIndexes.indexOf(queueIndex));
                        }
                    } catch (Exception error) {
                        Throwable cause = error;
                        while ((cause instanceof java.util.concurrent.CompletionException
                                || cause instanceof java.util.concurrent.ExecutionException)
                                && cause.getCause() != null) cause = cause.getCause();
                        failures.add(cause.getMessage() == null ? cause.toString() : cause.getMessage());
                    }
                    publish(new PrintProgressUpdate("Обработано заданий: " + groupIndex + " из " + groups.size(), groupIndex));
                }
                String batchStatus = successfulCandidateIndexes.isEmpty()
                        ? (failures.isEmpty() ? "Не отправлено" : "Ошибка отправки")
                        : (successfulCandidateIndexes.size() == candidates.size() && failures.isEmpty()
                        ? "Отправлено" : "Частично отправлено");
                try {
                    printHistory.finishBatch(batchId, successfulCandidateIndexes, batchStatus);
                } catch (Exception error) {
                    failures.add("Не удалось обновить историю партии: "
                            + (error.getMessage() == null ? error.toString() : error.getMessage()));
                    log("Ошибка обновления истории партии " + batchId + ": " + error);
                }
                return new PrintOutcome(batchId, successfulCandidateIndexes, failures);
            }

            @Override protected void process(List<PrintProgressUpdate> updates) {
                PrintProgressUpdate update = updates.get(updates.size() - 1);
                progressDialog.showProgress(update.message(), update.completedGroups(), groups.size());
            }

            @Override protected void done() {
                progressDialog.dispose();
                try {
                    PrintOutcome outcome = get();
                    java.util.Set<Integer> successfulCandidateIndexes =
                            new java.util.HashSet<>(outcome.successfulCandidateIndexes());
                    for (int candidateIndex : outcome.successfulCandidateIndexes()) {
                        entries.remove(candidateEntries.get(candidateIndex));
                    }
                    refreshQueue();
                    refreshHistory();
                    status.setText("Печать завершена: отправлено товаров " + successfulCandidateIndexes.size()
                            + (outcome.failures().isEmpty() ? "" : "; ошибок: " + outcome.failures().size()));
                    outcome.failures().forEach(MainWindow.this::log);
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    status.setText("Ошибка печати: " + cause.getMessage());
                    log("Ошибка печати: " + cause.getMessage());
                    refreshHistory();
                } finally {
                    printInProgress = false;
                }
            }
        }.execute();
        status.setText("Идёт отправка ценников на печать. Пожалуйста, подождите…");
        progressDialog.setVisible(true);
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
        workspace.addProductCard("Карточка — " + product.name(), new ProductCardDialog(product));
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

    private record PrintGroupKey(AppConfig.PrintFormat format, int amount) { }
    private record PrintProgressUpdate(String message, int completedGroups) { }
    private record PrintOutcome(long batchId, List<Integer> successfulCandidateIndexes, List<String> failures) { }
}
