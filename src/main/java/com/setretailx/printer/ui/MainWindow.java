package com.setretailx.printer.ui;

import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.model.Product;
import com.setretailx.printer.model.BarInput;
import com.setretailx.printer.model.PrintBatch;
import com.setretailx.printer.model.PrintHistoryEntry;
import com.setretailx.printer.repository.PriceChangePrintHistoryRepository;
import com.setretailx.printer.repository.ChangedLabelPrintHistoryRepository;
import com.setretailx.printer.model.PriceType;
import com.setretailx.printer.model.ScannedProbe;
import com.setretailx.printer.repository.ProductRepository;
import com.setretailx.printer.repository.PrintHistoryRepository;
import com.setretailx.printer.repository.AddressStorageRepository;
import com.setretailx.printer.repository.SalesPointRepository;
import com.setretailx.printer.repository.AccountRepository;
import com.setretailx.printer.service.ParentAccessService;
import com.setretailx.printer.service.ParentApiService;
import com.setretailx.printer.service.ParentApiClient;
import com.setretailx.printer.repository.SalesPointRepository.ShelfItem;
import com.setretailx.printer.service.SetRetailXConnector;
import com.setretailx.printer.service.ExchangeEvent;
import com.setretailx.printer.service.PriceMonitoringTaskService;
import com.setretailx.printer.service.PeerMeshService;
import com.setretailx.printer.service.setretailx.dto.PrintGroupItemDto;
import com.setretailx.printer.service.setretailx.dto.PrintGroupResponseDto;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterJob;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;

/** Main application shell; feature screens and MDI window management live in separate classes. */
@SuppressWarnings("serial")
public final class MainWindow extends JFrame {
    private final ProductRepository products = new ProductRepository();
    private final PrintHistoryRepository printHistory = new PrintHistoryRepository();
    private final PriceChangePrintHistoryRepository priceChangePrintHistory = new PriceChangePrintHistoryRepository();
    private final ChangedLabelPrintHistoryRepository changedLabelPrintHistory = new ChangedLabelPrintHistoryRepository();
    private final AddressStorageRepository addressStorage = new AddressStorageRepository();
    private final SalesPointRepository salesPoints = new SalesPointRepository();
    private final MdiWorkspace workspace = new MdiWorkspace();
    private final List<QueueEntry> entries = new ArrayList<>();
    private final ScannerPanel scanner = new ScannerPanel(this::submitScan);
    private final ComparisonPanel comparison;
    private final SearchPanel search = new SearchPanel(this::search, this::updateSearchSelection,
            this::addSelectedSearchResults, this::showProductCard,
            picker -> workspace.addDynamicWindow("Выбор категории", picker, 500, 520));
    private final QueuePanel queue = new QueuePanel(this::assignSelected, this::removeSelected,
            this::clearQueue, this::moveSelected, this::printAssigned, this::printQueueList);
    private final ExchangePanel exchange = new ExchangePanel(this::checkNetworkConnection);
    private final PeerMeshService peerMeshService = new PeerMeshService(
            peer -> SwingUtilities.invokeLater(this::refreshPeerMesh), PeerMeshPanel::confirmTrust);
    private final PeerMeshPanel peerMeshPanel = new PeerMeshPanel(peerMeshService);
    private final AccountRepository accountRepository = AppConfig.nodeRole() == AppConfig.NodeRole.PARENT
            ? new AccountRepository() : null;
    private final ParentAccessService parentAccess = accountRepository == null ? null
            : new ParentAccessService(accountRepository);
    private final PrintHistoryPanel history = new PrintHistoryPanel(this::restoreBatch, this::refreshHistory);
    private final SetRetailXConnector network = AppConfig.nodeRole() == AppConfig.NodeRole.PARENT
            ? SetRetailXConnector.getInstance(event -> SwingUtilities.invokeLater(() -> exchange.append(event)))
            : SetRetailXConnector.unavailableOnChild();
    private final PriceMonitoringTaskService taskService = new PriceMonitoringTaskService(network);
    private final ParentApiService parentApi = parentAccess == null ? null
            : new ParentApiService(parentAccess, taskService);
    private final ParentApiClient parentApiClient = new ParentApiClient(parentApi, peerMeshService);
    private final PriceMonitoringPanel taskPanel = new PriceMonitoringPanel(taskService, workspace);
    private final AddressStoragePanel addressStoragePanel = new AddressStoragePanel(addressStorage);
    private final PalletScanningPanel palletScanningPanel = new PalletScanningPanel(addressStorage);
    private final AddressManagementPanel addressManagementPanel = new AddressManagementPanel(addressStorage,
            () -> workspace.showWindow("addressCreate"),
            () -> {
                addressStoragePanel.refreshAvailableAddresses();
                palletScanningPanel.refreshAvailableAddresses();
            });
    private final AddressCreatePanel addressCreatePanel = new AddressCreatePanel(addressStorage, () -> {
        addressManagementPanel.refreshAddresses();
        addressStoragePanel.refreshAvailableAddresses();
        palletScanningPanel.refreshAvailableAddresses();
    });
    private final AddressSearchPanel addressSearchPanel = new AddressSearchPanel(addressStorage);
    private final SalesPointEditorPanel salesPointEditorPanel = new SalesPointEditorPanel(salesPoints);
    private final SalesPointMapPanel salesPointMapPanel = new SalesPointMapPanel(salesPoints,
            point -> {
                salesPointEditorPanel.refreshPoints();
                salesPointEditorPanel.selectPoint(point);
                workspace.showWindow("salesPointEditor");
            }, salesPointEditorPanel::refreshPoints);
    private final PriceChangesPanel priceChanges = new PriceChangesPanel(taskService, priceChangePrintHistory,
            (marking, name, barcode) ->
            workspace.addDynamicWindow("История цен — " + marking,
                    new PriceHistoryPanel(taskService, marking, name, barcode), 760, 480),
            this::enqueueChangedLabels);
    private final PrintChangesHistoryPanel changedPrintHistoryPanel = new PrintChangesHistoryPanel(
            new ChangedLabelPrintHistoryPanel(changedLabelPrintHistory),
            new PriceChangePrintHistoryPanel(priceChangePrintHistory));
    private final javax.swing.JLabel status = new javax.swing.JLabel("Готово");
    private List<Product> searchResults = new ArrayList<>();
    private boolean printInProgress;

    public MainWindow() {
        super("SetRetailX — Печать ценников (Java)");
        comparison = new ComparisonPanel(products, parentApiClient);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1120, 720);
        setMinimumSize(new java.awt.Dimension(850, 560));
        setLocationRelativeTo(null);
        setJMenuBar(workspace.createMenuBar(this::dispose, this::showAbout));
        if (parentApi != null) peerMeshService.configureParentApi(parentApi);
        comparison.setMismatchHandler(this::enqueueMismatchedProduct);
        salesPointEditorPanel.setHistoryCheckHandler(this::checkSalesPointPriceHistory);
        salesPointEditorPanel.setForcedCheckHandler(this::forceSalesPointPriceCheck);
        add(workspace, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        add(status, BorderLayout.SOUTH);

        workspace.addWindow("scanner", scanner, 650, 470);
        workspace.addWindow("comparison", comparison, 900, 470);
        workspace.addWindow("search", search, 760, 480);
        workspace.addWindow("queue", queue, 760, 440);
        workspace.addWindow("exchange", exchange, 650, 400);
        workspace.addWindow("apiExchangeLog", new ApiExchangeLogPanel(), 1000, 620);
        workspace.addWindow("peerMesh", peerMeshPanel, 850, 500);
        workspace.addWindow("history", history, 760, 420);
        workspace.addWindow("salesHistory", new SalesAnalyticsPanel(parentApiClient,
                this::openSalesArticleCard, this::enqueueSalesArticle), 900, 600);
        workspace.addWindow("priceChangePrintHistory", changedPrintHistoryPanel, 950, 540);
        workspace.addWindow("tasks", taskPanel, 900, 520);
        workspace.addWindow("storage", addressStoragePanel, 900, 560);
        workspace.addWindow("storageAddresses", addressManagementPanel, 900, 520);
        workspace.addWindow("addressCreate", addressCreatePanel, 560, 300);
        workspace.addWindow("addressSearch", addressSearchPanel, 860, 520);
        workspace.addWindow("salesPointMap", salesPointMapPanel, 1000, 680);
        workspace.addWindow("salesPointEditor", salesPointEditorPanel, 760, 520);
        workspace.addWindow("palletScanning", palletScanningPanel, 900, 600);
        workspace.addWindow("priceChanges", priceChanges, 900, 520);
        if (AppConfig.nodeRole() == AppConfig.NodeRole.PARENT) {
            workspace.addWindow("environment", new EnvironmentPanel(() -> {
                network.reloadSettings();
                log("Параметры обслуживания и администрирования сохранены и применены. При необходимости нажмите «Проверить подключение».");
                status.setText("Параметры администрирования обновлены.");
            }), 760, 520);
        }
        if (AppConfig.nodeRole() == AppConfig.NodeRole.PARENT) {
            workspace.addWindow("accounts", new AccountManagementPanel(accountRepository, parentAccess), 780, 500);
        }
        scanner.registerRecipient("comparison", "Сверка ценников");
        scanner.registerRecipient("search", "Поиск товаров");
        scanner.registerRecipient("queue", "Печать ценников");
        scanner.registerRecipient("storage", "Сканирование единичных коробок");
        scanner.registerRecipient("palletScanning", "Сканирование паллет");
        scanner.registerRecipient("salesPointEditor", "Редактор торговой точки · ценники");
        runBackground(products::searchOptions, search::setSearchOptions,
                error -> search.setStatus("Не удалось загрузить фильтры каталога: " + error));
        workspace.setWindowVisibilityListener((key, open) -> {
            scanner.setRecipientOpen(key, open);
            if ("palletScanning".equals(key) && open) {
                palletScanningPanel.prepareForScanning();
                scanner.focusInput();
            }
        });
        workspace.showWindow("scanner");
        refreshHistory();
        log("Java UI запущен. Локальная база: " + AppConfig.DB_PATH);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                taskPanel.close();
                priceChanges.close();
                changedPrintHistoryPanel.close();
                network.close();
                peerMeshService.close();
            }
        });
    }

    private void checkNetworkConnection() {
        if (AppConfig.nodeRole() != AppConfig.NodeRole.PARENT) {
            exchange.setStatus("Прямое подключение SetRetailX отключено на дочернем узле. Используйте родительский ПК.");
            return;
        }
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

    private void refreshPeerMesh() {
        if (peerMeshPanel != null) peerMeshPanel.refreshPeers();
    }

    private void submitScan() {
        String raw = scanner.inputText().trim();
        if (raw.isEmpty()) return;
        scanner.clearInput();
        if (raw.startsWith("username=") || raw.contains("&password=")) {
            try (com.setretailx.printer.service.ScannedCredentials.Credentials ignored =
                         com.setretailx.printer.service.ScannedCredentials.parse(raw)) {
                status.setText("Формат сканируемых данных проверен, но вход в аккаунт ещё не подключён.");
            } catch (IllegalArgumentException invalid) {
                status.setText(invalid.getMessage());
            }
            return;
        }
        if (scanner.sendsTo("palletScanning")) {
            submitPalletScan(raw);
            scanner.focusInput();
            return;
        }
        ScannedProbe probe = null;
        try {
            probe = ScannedProbe.fromText(raw);
            scanner.showParsedScan(probe);
            if (probe.hasPriceData()) probe.pricesAsDecimals();
        } catch (IllegalArgumentException error) {
            if (probe == null) scanner.showScanError(raw, error.getMessage());
            else scanner.showParsedScan(probe);
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
        if (scanner.sendsTo("salesPointEditor") && probe.hasPriceData()) {
            receivers++;
            receiveSalesPointPriceScan(probe);
        }
        if (scanner.sendsTo("salesPointEditor") && !probe.hasPriceData() && !probe.barcode().isBlank()) {
            receivers++;
            addSalesPointItemByBarcode(probe.barcode());
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
            Product product = local != null ? local : parentApiClient.searchOnlineProduct(probe.barcode()).orElse(null);
            if (product == null) return null;
            return new ScannedPrintProduct(product, printHistory.latestSuccessfulEntry(product).orElse(null));
        }, scanned -> {
            if (scanned == null) {
                status.setText("Штрихкод " + probe.barcode() + " не найден.");
                return;
            }
            QueueEntry queued = findQueuedByBarcode(probe.barcode());
            if (queued != null) {
                int currentAmount = queued.amount == null ? 1 : queued.amount;
                if (currentAmount >= 999) {
                    status.setText("Товар уже в очереди; максимальный тираж ценника — 999: "
                            + scanned.product().name());
                    return;
                }
                queued.amount = currentAmount + 1;
                refreshQueue();
                status.setText("Товар уже в очереди: тираж увеличен до " + queued.amount + " — "
                        + scanned.product().name());
                return;
            }
            QueueEntry entry = new QueueEntry(scanned.product());
            entry.amount = scanned.previousPrint() == null || scanned.previousPrint().amount() == null
                    ? 1 : Math.max(1, Math.min(999, scanned.previousPrint().amount()));
            if (scanned.previousPrint() != null) {
                PrintHistoryEntry previous = scanned.previousPrint();
                entry.format = AppConfig.formatByKey(previous.formatKey());
                entry.quantity = previous.quantity();
                entry.priceType = previous.priceType();
            }
            entries.add(entry);
            refreshQueue();
            status.setText("Добавлен товар (" + scanned.product().source() + "): " + scanned.product().name()
                    + (scanned.previousPrint() == null ? "" : " · настройки взяты из истории печати"));
        }, error -> status.setText("Ошибка поиска товара: " + error));
    }

    private QueueEntry findQueuedByBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) return null;
        String normalized = barcode.trim();
        return entries.stream()
                .filter(entry -> !entry.product.barcode().isBlank())
                .filter(entry -> normalized.equals(entry.product.barcode().trim()))
                .findFirst().orElse(null);
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
            return local != null ? local : parentApiClient.searchOnlineProduct(probe.barcode()).orElse(null);
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

    private void receivePalletScan(ScannedProbe probe) {
        if (probe.barcode().isEmpty()) {
            palletScanningPanel.scanFailed("Для сканирования паллет нужен штрихкод товара.");
            status.setText("Для сканирования паллет нужен штрихкод товара.");
            return;
        }
        status.setText("Ищем товар " + probe.barcode() + " для паллеты…");
        runBackground(() -> {
            Product local = products.findByBarcodeForPallet(probe.barcode());
            return local != null ? local : parentApiClient.searchOnlineProduct(probe.barcode()).orElse(null);
        }, product -> {
            if (product == null) {
                palletScanningPanel.scanFailed("Штрихкод " + probe.barcode() + " не найден; товар не добавлен.");
                status.setText("Штрихкод " + probe.barcode() + " не найден; товар не добавлен.");
                return;
            }
            palletScanningPanel.addScannedProduct(product, message -> {
                status.setText(message);
                if (message.startsWith("Добавлен")) log(message);
            });
        }, error -> {
            palletScanningPanel.scanFailed("Ошибка поиска товара для паллеты: " + error);
            status.setText("Ошибка поиска товара для паллеты: " + error);
        });
    }

    private void submitPalletScan(String raw) {
        if (isPalletCloseBarcode(raw)) {
            palletScanningPanel.closeCurrentBox(message -> {
                status.setText(message);
                if (message.startsWith("Коробка закрыта")) log(message);
            });
            return;
        }
        if (!palletScanningPanel.beginProductLookup()) {
            status.setText(palletScanningPanel.selectedAddress() == null
                    ? "Выберите паллет перед сканированием товара."
                    : "Дождитесь завершения текущей операции перед сканированием.");
            return;
        }
        ScannedProbe probe;
        try {
            probe = ScannedProbe.fromText(raw);
            scanner.showParsedScan(probe);
            if (probe.hasPriceData()) probe.pricesAsDecimals();
        } catch (IllegalArgumentException error) {
            scanner.showScanError(raw, error.getMessage());
            palletScanningPanel.scanFailed(error.getMessage());
            status.setText(error.getMessage());
            log("Ошибка разбора скана паллеты: " + error.getMessage());
            return;
        }
        if (probe.hasPriceData()) {
            palletScanningPanel.scanFailed("В окне паллетного режима ожидается штрихкод товара или сервисный код закрытия.");
            status.setText("В окне паллетного режима ожидается штрихкод товара или сервисный код закрытия.");
            return;
        }
        receivePalletScan(probe);
    }

    private static boolean isPalletCloseBarcode(String raw) {
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            decoded = raw;
        }
        return decoded.equals("retailx-proto://close-box")
                || decoded.matches("(?i)(?:.*[?&])?barcode=retailx-proto://close-box(?:&.*)?");
    }

    private void receivePriceScan(ScannedProbe probe) {
        comparison.receiveScan(probe);
        log("Скан ценника принят для сверки: barcode=" + probe.barcode());
    }

    private void receiveSalesPointPriceScan(ScannedProbe probe) {
        long targetPointId = salesPointEditorPanel.selectedPointId();
        if (targetPointId < 0) {
            status.setText("Откройте редактор и выберите торговую точку перед сканированием ценника.");
            return;
        }
        if (probe.barcode().isBlank()) {
            status.setText("Для ценника точки продаж нужен barcode.");
            return;
        }
        runBackground(() -> {
            Product product = products.findByBarcode(probe.barcode());
            return product != null ? product : parentApiClient.searchOnlineProduct(probe.barcode()).orElse(null);
        }, product -> {
            if (product == null) {
                status.setText("Товар " + probe.barcode() + " не найден; ценник точки не обновлён.");
                return;
            }
            salesPointEditorPanel.acceptPriceScan(targetPointId, product, probe);
            scanner.focusInput();
        }, error -> {
            status.setText("Не удалось найти товар для точки продаж: " + error);
            scanner.focusInput();
        });
    }

    private void addSalesPointItemByBarcode(String barcode) {
        long targetPointId = salesPointEditorPanel.selectedPointId();
        if (targetPointId < 0) {
            status.setText("Сначала выберите торговую точку.");
            return;
        }
        status.setText("Ищем товар и последний ценник по штрихкоду " + barcode + "…");
        runBackground(() -> {
            Product product = products.findByBarcode(barcode);
            if (product == null) product = parentApiClient.searchOnlineProduct(barcode).orElse(null);
            if (product == null) return null;
            PriceMonitoringTaskService.ShelfPrice price = taskService.latestShelfPrice(product.marking());
            return new SalesPointBarcodeItem(product, price);
        }, item -> {
            if (item == null) {
                status.setText("Товар " + barcode + " не найден; ценник точки не добавлен.");
                return;
            }
            salesPointEditorPanel.acceptBarcodeItem(targetPointId, item.product(), item.price());
            scanner.focusInput();
        }, error -> status.setText("Не удалось добавить ценник по штрихкоду: " + error));
    }

    private void enqueueMismatchedProduct(Product product) {
        entries.add(new QueueEntry(product));
        refreshQueue();
        log("Несовпавший ценник добавлен в очередь без назначения формата и тиража: " + product.name());
    }

    private void enqueueChangedLabels(List<PriceChangesPanel.ChangedLabelCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return;
        status.setText("Подбираем товары для очереди изменённых ценников…");
        runBackground(() -> {
            List<Product> found = new ArrayList<>();
            for (PriceChangesPanel.ChangedLabelCandidate candidate : candidates) {
                Product product = candidate.marking().isBlank() ? null : products.findByMarking(candidate.marking());
                if (product == null && !candidate.barcode().isBlank()) product = products.findByBarcode(candidate.barcode());
                if (product == null && !candidate.barcode().isBlank()) {
                    product = parentApiClient.searchOnlineProduct(candidate.barcode()).orElse(null);
                }
                if (product != null) found.add(product);
            }
            return found;
        }, found -> {
            for (Product product : found) entries.add(new QueueEntry(product, true));
            refreshQueue();
            workspace.showWindow("queue");
            int missing = candidates.size() - found.size();
            status.setText("В очередь добавлено ценников по изменениям: " + found.size()
                    + (missing == 0 ? "" : " · товары не найдены: " + missing));
        }, error -> status.setText("Не удалось добавить изменённые ценники в очередь: " + error));
    }

    private void checkSalesPointPriceHistory(List<ShelfItem> shelfItems) {
        if (shelfItems == null || shelfItems.isEmpty()) return;
        status.setText("Проверяем историю изменения ценников…");
        runBackground(() -> {
            List<ChangedShelfProduct> changed = new ArrayList<>();
            for (ShelfItem item : shelfItems) {
                List<PriceMonitoringTaskService.PriceChange> changes = taskService.priceChangesSince(
                        item.article(), item.updatedAt() / 1000.0);
                if (changes.isEmpty()) continue;
                Product product = products.findByMarking(item.article());
                if (product == null && !changes.get(0).barcode().isBlank()) {
                    product = parentApiClient.searchOnlineProduct(changes.get(0).barcode()).orElse(null);
                }
                changed.add(new ChangedShelfProduct(item, changes.size(), product));
            }
            return changed;
        }, changed -> {
            if (changed.isEmpty()) {
                status.setText("Проверено ценников: " + shelfItems.size() + ". Изменений в истории нет — всё хорошо.");
                JOptionPane.showMessageDialog(this,
                        "Проверено ценников: " + shelfItems.size() + ".\nИзменений цен после последнего обновления не найдено — всё хорошо.",
                        "История цен торговой точки", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            long queueable = changed.stream().filter(item -> item.product() != null).count();
            StringBuilder message = new StringBuilder("Найдены изменения цен после обновления ценника:\n\n");
            for (ChangedShelfProduct entry : changed) {
                message.append("• ").append(entry.shelfItem().article()).append(" — ")
                        .append(entry.shelfItem().name().isBlank() ? "без названия" : entry.shelfItem().name())
                        .append(" (событий: ").append(entry.changeCount()).append(")")
                        .append(entry.product() == null ? " · товар не найден для очереди" : "")
                        .append('\n');
            }
            message.append("\nДобавить найденные товары в очередь печати? (доступно: ")
                    .append(queueable).append(')');
            int answer = JOptionPane.showConfirmDialog(this, message.toString(), "Изменения цен",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.YES_OPTION) {
                int queued = 0;
                for (ChangedShelfProduct entry : changed) {
                    if (entry.product() == null) continue;
                    entries.add(new QueueEntry(entry.product()));
                    queued++;
                }
                refreshQueue();
                status.setText("Добавлено в очередь: " + queued + " ценников с изменившимися ценами.");
                log("По истории изменений цен в очередь добавлено ценников: " + queued);
            } else {
                status.setText("Найдено изменившихся ценников: " + changed.size() + ". В очередь не добавляли.");
            }
        }, error -> status.setText("Не удалось проверить историю цен: " + error));
    }

    private void forceSalesPointPriceCheck(List<ShelfItem> shelfItems) {
        if (shelfItems == null || shelfItems.isEmpty()) return;
        status.setText("Готовим принудительную сверку цен…");
        runBackground(() -> {
            List<ForcedShelfScan> scans = new ArrayList<>();
            for (ShelfItem item : shelfItems) {
                Product product = products.findByMarking(item.article());
                if (product == null) throw new IllegalStateException(
                        "Товар не найден в локальной базе по артикулу " + item.article());
                scans.add(new ForcedShelfScan(item, product));
            }
            return scans;
        }, scans -> {
            int submitted = 0;
            for (ForcedShelfScan scan : scans) {
                ShelfItem item = scan.shelfItem();
                if ((item.price() == null || item.price().isBlank())
                        && (item.discountPrice() == null || item.discountPrice().isBlank())) continue;
                comparison.receiveScan(new ScannedProbe(scan.product().barcode(), item.price(), "", "",
                        item.discountPrice(), "", ""));
                submitted++;
            }
            if (submitted == 0) {
                status.setText("У выбранных ценников не заполнены обычная и скидочная цены.");
                return;
            }
            workspace.showWindow("comparison");
            status.setText("Отправлено на принудительную сверку: " + submitted + " ценников.");
        }, error -> status.setText("Не удалось выполнить принудительную сверку: " + error));
    }

    private record ChangedShelfProduct(ShelfItem shelfItem, int changeCount, Product product) { }
    private record ForcedShelfScan(ShelfItem shelfItem, Product product) { }
    private record SalesPointBarcodeItem(Product product, PriceMonitoringTaskService.ShelfPrice price) { }
    private record ScannedPrintProduct(Product product, PrintHistoryEntry previousPrint) { }

    private void search() {
        String query = search.query();
        String lookup = query.isEmpty() ? "" : BarInput.barcode(query).orElse(query);
        ProductRepository.SearchCriteria criteria = new ProductRepository.SearchCriteria(lookup,
                search.categoryFilter(), search.attributeNameFilter(), search.attributeValueFilter(),
                search.minimumPriceFilter(), search.maximumPriceFilter(), search.advancedSearch());
        if (query.isEmpty() && !criteria.hasFilters()) {
            search.setStatus("Введите запрос или выберите категорию, цену либо характеристику.");
            return;
        }
        search.setStatus("Ищем в локальной базе…");
        search.setSearchEnabled(false);
        runBackground(() -> {
            List<Product> local = products.search(criteria);
            if (!local.isEmpty() || criteria.hasFilters() || lookup.isEmpty()) return local;
            SwingUtilities.invokeLater(() -> search.setStatus("Локально не найдено; ищем в SetRetailX…"));
            return parentApiClient.searchOnlineProduct(lookup).map(List::of).orElseGet(List::of);
        }, results -> {
            search.setSearchEnabled(true);
            searchResults = new ArrayList<>(results);
            search.showResults(results);
            search.setStatus("Найдено товаров: " + results.size());
        }, error -> {
            search.setSearchEnabled(true);
            search.setStatus("Ошибка поиска: " + error);
        });
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
                        PrintGroupResponseDto result = parentApiClient.printGroup(key.format(), key.amount(), items);
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
                List<PrintHistoryEntry> successfullyPrintedChangedLabels = new ArrayList<>();
                for (int candidateIndex : successfulCandidateIndexes) {
                    if (candidateEntries.get(candidateIndex).changedLabel) {
                        successfullyPrintedChangedLabels.add(candidates.get(candidateIndex));
                    }
                }
                if (!successfullyPrintedChangedLabels.isEmpty()) {
                    try {
                        changedLabelPrintHistory.addSuccessful(successfullyPrintedChangedLabels);
                    } catch (Exception error) {
                        failures.add("Ценники напечатаны, но история изменённых ценников не сохранена: "
                                + (error.getMessage() == null ? error.toString() : error.getMessage()));
                    }
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

    private void printQueueList() {
        if (entries.isEmpty()) {
            status.setText("В очереди нет товаров для списка.");
            return;
        }
        Object[] choices = {"Без группировки", "По категориям"};
        Object choice = JOptionPane.showInputDialog(this,
                "Выберите группировку списка товаров:",
                "Формат списка товаров", JOptionPane.QUESTION_MESSAGE,
                null, choices, choices[0]);
        if (choice == null) return;
        boolean groupByCategory = "По категориям".equals(choice);
        List<QueueListRow> rows = new ArrayList<>();
        for (QueueEntry entry : entries) {
            Product product = entry.product;
            String category = product.details().entrySet().stream()
                    .filter(detail -> detail.getKey().replaceAll("[^a-zA-Zа-яА-Я0-9]", "")
                            .equalsIgnoreCase("categorypath"))
                    .map(Map.Entry::getValue).filter(value -> value != null && !value.isBlank())
                    .findFirst().orElse("Без категории");
            rows.add(new QueueListRow(product.name(), category, product.marking(), product.vendorCode(),
                    product.barcode(), product.price() == null ? "—" : product.price().format(),
                    product.totalQuantity()));
        }
        if (groupByCategory) {
            Map<String, List<QueueListRow>> grouped = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (QueueListRow row : rows) grouped.computeIfAbsent(row.category(), ignored -> new ArrayList<>()).add(row);
            rows.clear();
            for (Map.Entry<String, List<QueueListRow>> group : grouped.entrySet()) {
                rows.add(QueueListRow.categoryHeader(group.getKey()));
                group.getValue().sort(Comparator.comparing(QueueListRow::name, String.CASE_INSENSITIVE_ORDER));
                rows.addAll(group.getValue());
            }
        }
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("Список товаров · " + entries.size() + " позиций");
        PageFormat page = job.defaultPage();
        Paper paper = new Paper();
        double width = 210 / 25.4 * 72, height = 297 / 25.4 * 72;
        double margin = 14 / 25.4 * 72;
        paper.setSize(width, height);
        paper.setImageableArea(margin, margin, width - margin * 2, height - margin * 2);
        page.setPaper(paper);
        page.setOrientation(PageFormat.PORTRAIT);
        job.setPrintable(new QueueListPrintable(rows), page);
        if (!job.printDialog()) return;
        try {
            job.print();
            status.setText("Список товаров отправлен на печать: " + entries.size() + " позиций.");
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.toString() : error.getMessage();
            status.setText("Ошибка печати списка: " + message);
            JOptionPane.showMessageDialog(this, message, "Печать списка", JOptionPane.ERROR_MESSAGE);
        }
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
        workspace.addProductCard("Карточка — " + product.name(),
                new ProductCardDialog(product, addressStorage, taskService, parentApiClient));
    }

    private void openSalesArticleCard(PriceMonitoringTaskService.SalesArticle article) {
        resolveSalesArticle(article, product -> {
            if (product == null) {
                status.setText("Товар не найден в каталоге: " + article.marking());
                return;
            }
            showProductCard(product);
        });
    }

    private void enqueueSalesArticle(PriceMonitoringTaskService.SalesArticle article) {
        resolveSalesArticle(article, product -> {
            if (product == null) {
                status.setText("Товар не найден в каталоге для очереди: " + article.marking());
                return;
            }
            entries.add(new QueueEntry(product));
            refreshQueue();
            workspace.showWindow("queue");
            status.setText("Добавлен в очередь печати: " + product.name());
        });
    }

    private void resolveSalesArticle(PriceMonitoringTaskService.SalesArticle article,
                                     java.util.function.Consumer<Product> done) {
        status.setText("Ищем товар в каталоге: " + article.marking());
        runBackground(() -> {
            Product product = article.marking().isBlank() ? null : products.findByMarking(article.marking());
            if (product == null && !article.barcode().isBlank()) product = products.findByBarcode(article.barcode());
            if (product == null && !article.barcode().isBlank()) {
                product = parentApiClient.searchOnlineProduct(article.barcode()).orElse(null);
            }
            if (product == null && !article.name().isBlank()) {
                List<Product> matches = products.search(article.name());
                product = matches.stream().filter(match -> match.name().equalsIgnoreCase(article.name()))
                        .findFirst().orElse(null);
            }
            return product;
        }, product -> done.accept(product), error -> status.setText("Не удалось найти товар: " + error));
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
        if (entries.isEmpty() || JOptionPane.showConfirmDialog(this, "Очистить очередь печати?", "Печать ценников",
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
            items.add(new QueuePanel.QueueItem(entry.product.name(), entry.product.marking(),
                    entry.product.barcode(), entry.product.stCode(),
                    entry.product.price() == null ? "—" : entry.product.price().format(),
                    entry.format, entry.priceType, entry.amount, entry.quantity));
        }
        queue.showEntries(items);
        queue.setSummary("Всего: " + entries.size() + " · назначено: " + assigned
                + " · не настроено: " + (entries.size() - assigned));
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
        private final boolean changedLabel;
        private AppConfig.PrintFormat format;
        private Integer amount;
        private int quantity = 1;
        private PriceType priceType = PriceType.PROMO;

        private QueueEntry(Product product) { this(product, false); }

        private QueueEntry(Product product, boolean changedLabel) {
            this.product = product;
            this.changedLabel = changedLabel;
        }

        private PrintHistoryEntry snapshot() {
            return PrintHistoryEntry.fromQueue(product, format, amount, quantity, priceType);
        }
    }

    private record PrintGroupKey(AppConfig.PrintFormat format, int amount) { }
    private record QueueListRow(String name, String category, String sapCode, String article,
                                String barcode, String price, String quantity, boolean categoryHeader) {
        private QueueListRow(String name, String category, String sapCode, String article,
                             String barcode, String price, String quantity) {
            this(name, category, sapCode, article, barcode, price, quantity, false);
        }
        private static QueueListRow categoryHeader(String category) {
            return new QueueListRow(category, category, "", "", "", "", "", true);
        }
    }
    private record PrintProgressUpdate(String message, int completedGroups) { }
    private record PrintOutcome(long batchId, List<Integer> successfulCandidateIndexes, List<String> failures) { }

    private static final class QueueListPrintable implements Printable {
        private static final double HEADER_HEIGHT = 50;
        private static final double TABLE_HEADER_HEIGHT = 24;
        private static final double FOOTER_HEIGHT = 104;
        private static final double ROW_HEIGHT = 19;
        private static final int SAP_CODES_PER_QR = 20;
        private static final int QR_SIZE = 68;
        private final List<QueueListRow> rows;

        private QueueListPrintable(List<QueueListRow> rows) { this.rows = List.copyOf(rows); }

        @Override public int print(java.awt.Graphics graphics, PageFormat page, int pageIndex) {
            double width = page.getImageableWidth();
            double height = page.getImageableHeight();
            int rowsPerPage = Math.max(1, (int) ((height - HEADER_HEIGHT - TABLE_HEADER_HEIGHT - FOOTER_HEIGHT - 10)
                    / ROW_HEIGHT));
            List<List<QueueListRow>> pages = paginate(rows, rowsPerPage);
            if (pageIndex < 0 || pageIndex >= pages.size()) return NO_SUCH_PAGE;
            List<QueueListRow> pageRows = pages.get(pageIndex);
            List<String> sapCodes = pageRows.stream().filter(row -> !row.categoryHeader())
                    .map(QueueListRow::sapCode).filter(code -> code != null && !code.isBlank()).toList();
            List<BufferedImage> qrImages = createQrGroups(sapCodes);

            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(page.getImageableX(), page.getImageableY());
                g.setColor(Color.BLACK);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
                g.drawString("СПИСОК ТОВАРОВ", 0, 17);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
                drawQueueListRight(g, "ПОЗИЦИЙ: " + rows.stream().filter(row -> !row.categoryHeader()).count()
                        + " · ЛИСТ " + (pageIndex + 1) + "/" + pages.size(), width, 17);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 8));
                g.drawString("Товарный список · " + java.time.LocalDate.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")), 0, 34);
                g.setStroke(new java.awt.BasicStroke(1.2f));
                g.drawLine(0, 42, (int) width, 42);

                double tableTop = HEADER_HEIGHT;
                double[] x = {0, width * .37, width * .53, width * .63, width * .78, width * .91};
                double[] w = {width * .37, width * .16, width * .10, width * .15, width * .13, width * .09};
                g.setColor(new Color(224, 224, 224));
                g.fillRect(0, (int) tableTop, (int) width, (int) TABLE_HEADER_HEIGHT);
                g.setColor(Color.BLACK);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 7));
                String[] headers = {"ТОВАР", "SAP-КОД", "АРТИКУЛ", "ШТРИХКОД", "ЦЕНА", "ОСТАТОК"};
                for (int col = 0; col < headers.length; col++) {
                    int baseline = (int) (tableTop + 15);
                    if (col >= 4) drawQueueListRight(g, headers[col], x[col] + w[col] - 3, baseline);
                    else g.drawString(headers[col], (int) x[col] + 3, baseline);
                }
                for (int i = 0; i < pageRows.size(); i++) {
                    QueueListRow row = pageRows.get(i);
                    double top = tableTop + TABLE_HEADER_HEIGHT + i * ROW_HEIGHT;
                    int baseline = (int) (top + ROW_HEIGHT * .69);
                    if (row.categoryHeader()) {
                        g.setColor(new Color(232, 236, 241));
                        g.fillRect(0, (int) top, (int) width, (int) ROW_HEIGHT);
                        g.setColor(Color.BLACK);
                        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
                        g.drawString("КАТЕГОРИЯ: " + clipQueueList(g, row.category(), width - 8), 4, baseline);
                        continue;
                    }
                    g.setColor(Color.BLACK);
                    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 7));
                    g.drawString(clipQueueList(g, row.name(), w[0] - 7), (int) x[0] + 3, baseline);
                    g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 7));
                    g.drawString(clipQueueList(g, row.sapCode(), w[1] - 7), (int) x[1] + 3, baseline);
                    g.drawString(clipQueueList(g, row.article(), w[2] - 7), (int) x[2] + 3, baseline);
                    g.drawString(clipQueueList(g, row.barcode(), w[3] - 7), (int) x[3] + 3, baseline);
                    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 7));
                    drawQueueListRight(g, row.price(), x[4] + w[4] - 3, baseline);
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 7));
                    drawQueueListRight(g, row.quantity(), x[5] + w[5] - 3, baseline);
                    g.setColor(new Color(200, 200, 200));
                    g.drawLine(0, (int) (top + ROW_HEIGHT), (int) width, (int) (top + ROW_HEIGHT));
                }
                g.setColor(Color.DARK_GRAY);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 7));
                int footerY = (int) (height - FOOTER_HEIGHT + 5);
                g.drawString("SAP-КОДЫ ЭТОГО ЛИСТА · до 20 кодов на QR · всего: " + sapCodes.size(), 0, footerY);
                int qrX = 0;
                int qrY = footerY + 7;
                for (int i = 0; i < qrImages.size(); i++) {
                    if (qrX + QR_SIZE > width) { qrX = 0; qrY += QR_SIZE + 14; }
                    if (qrY + QR_SIZE > height) break;
                    g.drawImage(qrImages.get(i), qrX, qrY, QR_SIZE, QR_SIZE, null);
                    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 6));
                    g.drawString("Коды " + (i * SAP_CODES_PER_QR + 1) + "–"
                            + Math.min((i + 1) * SAP_CODES_PER_QR, sapCodes.size()), qrX, qrY + QR_SIZE + 8);
                    qrX += QR_SIZE + 8;
                }
                return PAGE_EXISTS;
            } finally { g.dispose(); }
        }

        private static List<List<QueueListRow>> paginate(List<QueueListRow> rows, int rowsPerPage) {
            List<List<QueueListRow>> pages = new ArrayList<>();
            List<QueueListRow> current = new ArrayList<>();
            for (QueueListRow row : rows) {
                if (current.size() >= rowsPerPage) {
                    pages.add(List.copyOf(current));
                    String category = current.stream().filter(QueueListRow::categoryHeader)
                            .map(QueueListRow::category).reduce((first, second) -> second).orElse(null);
                    current = new ArrayList<>();
                    if (category != null && !row.categoryHeader()) current.add(QueueListRow.categoryHeader(category));
                }
                current.add(row);
            }
            if (!current.isEmpty()) pages.add(List.copyOf(current));
            return pages;
        }

        private static List<BufferedImage> createQrGroups(List<String> codes) {
            List<BufferedImage> result = new ArrayList<>();
            for (int start = 0; start < codes.size(); start += SAP_CODES_PER_QR) {
                String payload = String.join("\n", codes.subList(start,
                        Math.min(start + SAP_CODES_PER_QR, codes.size())));
                try {
                    Map<EncodeHintType, Object> hints = new LinkedHashMap<>();
                    hints.put(EncodeHintType.MARGIN, 1);
                    result.add(MatrixToImageWriter.toBufferedImage(new MultiFormatWriter()
                            .encode(payload, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints)));
                } catch (Exception ignored) { }
            }
            return result;
        }

        private static String clipQueueList(Graphics2D g, String value, double maxWidth) {
            String text = value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ");
            if (g.getFontMetrics().stringWidth(text) <= maxWidth) return text;
            while (!text.isEmpty() && g.getFontMetrics().stringWidth(text + "…") > maxWidth)
                text = text.substring(0, text.length() - 1);
            return text.isEmpty() ? "" : text + "…";
        }

        private static void drawQueueListRight(Graphics2D g, String text, double right, int baseline) {
            g.drawString(text, (int) (right - g.getFontMetrics().stringWidth(text)), baseline);
        }
    }
}
