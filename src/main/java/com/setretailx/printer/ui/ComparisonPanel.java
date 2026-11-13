package com.setretailx.printer.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.setretailx.printer.model.Product;
import com.setretailx.printer.model.ScannedProbe;
import com.setretailx.printer.repository.ProductRepository;
import com.setretailx.printer.service.SetRetailXConnector;
import com.setretailx.printer.service.setretailx.dto.ProductDto;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Receives scanned price labels and compares them with SetRetailX price sets. */
@SuppressWarnings("serial")
final class ComparisonPanel extends JPanel {
    private static final int BATCH_SIZE = 50;
    private final DefaultTableModel model = readOnlyModel(
            "Статус", "Артикул", "Штрихкод", "price1 · обычная", "price2 · до скидки",
            "price3 · неизвестно", "price4 · на ценнике", "Итоговая (price4/2)",
            "Обычная #1", "Обычная #2", "Обычная #3", "Обычная #4",
            "99 #1", "99 #2", "99 #3", "99 #4", "Совпал #1", "Совпал #2", "Совпал #3", "Совпал #4", "Результат");
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel("Ожидаю сканирование ценников.");
    private final ProductRepository repository;
    private final List<ScanRecord> pending = new ArrayList<>();
    private SetRetailXConnector connector;
    private Consumer<Product> mismatchHandler = product -> { };
    private boolean workerRunning;

    ComparisonPanel(ProductRepository repository) {
        super(new BorderLayout(8, 8));
        this.repository = repository;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(24);
        table.getTableHeader().setToolTipText("price1 — обычная цена, price2 — до акции/скидки, "
                + "price4 — цена на ценнике после скидки; итоговая = price4, иначе price2. "
                + "Назначение price3 неизвестно, поэтому оно не участвует в выводе о совпадении. "
                + "«Совпал» сверяет известные priceN со слотами #N в pricesMap (обычные) и prices99Map.");
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.add(status, BorderLayout.CENTER);
        JButton clear = new JButton("Очистить результаты");
        clear.addActionListener(event -> {
            if (workerRunning) {
                setStatus("Дождитесь завершения текущей проверки.");
            } else if (model.getRowCount() > 0 && JOptionPane.showConfirmDialog(this,
                    "Удалить все результаты сверки?", "Подтверждение удаления",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                clear();
            }
        });
        footer.add(clear, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    void setConnector(SetRetailXConnector connector) {
        this.connector = connector;
        if (connector != null) processPending();
    }

    void setMismatchHandler(Consumer<Product> handler) {
        mismatchHandler = handler == null ? product -> { } : handler;
    }

    void receiveScan(ScannedProbe probe) {
        if (!probe.hasPriceData()) return;
        Map<Integer, BigDecimal> prices;
        try {
            prices = probe.pricesAsDecimals();
        } catch (IllegalArgumentException error) {
            appendError(probe, error.getMessage());
            return;
        }
        ScanRecord scan = new ScanRecord(probe, prices, model.getRowCount());
        model.addRow(row(scan, "В очереди", null, null, null, null));
        if (probe.barcode().isEmpty()) {
            setRowError(scan, "В скане ценника не заполнен barcode");
            return;
        }
        pending.add(scan);
        if (connector == null) {
            setStatus("Ценник принят; ожидание подключения к SetRetailX.");
        } else {
            processPending();
        }
    }

    void setStatus(String message) { status.setText(message); }

    void clear() {
        pending.clear();
        model.setRowCount(0);
        setStatus("Ожидаю сканирование ценников.");
    }

    private void processPending() {
        if (workerRunning || connector == null || pending.isEmpty()) return;
        List<ScanRecord> batch = new ArrayList<>(pending.subList(0, Math.min(BATCH_SIZE, pending.size())));
        pending.subList(0, batch.size()).clear();
        batch.forEach(scan -> updateRow(scan, row(scan, "Проверка…", null, null, null, null)));
        workerRunning = true;
        setStatus("Проверяем ценники: " + batch.size() + "…");
        SetRetailXConnector activeConnector = connector;
        new SwingWorker<List<ComparisonResult>, Void>() {
            @Override protected List<ComparisonResult> doInBackground() {
                return compare(batch, activeConnector);
            }

            @Override protected void done() {
                try {
                    List<ComparisonResult> results = get();
                    int queued = 0;
                    for (ComparisonResult result : results) {
                        if (result.error != null) setRowError(result.scan, result.error);
                        else {
                            updateRow(result.scan, row(result.scan, "Готово", result.normal, result.promo,
                                    result.matches, result.product.vendorCode()));
                            if (result.mismatched()) {
                                mismatchHandler.accept(result.product);
                                queued++;
                            }
                        }
                    }
                    long checked = results.stream().filter(result -> result.error == null).count();
                    long mismatches = results.stream().filter(result -> result.error == null && result.mismatched()).count();
                    setStatus("Проверено: " + checked + "; несовпадений: " + mismatches
                            + "; добавлено в очередь: " + queued
                            + (pending.isEmpty() ? "" : "; в очереди: " + pending.size()));
                } catch (Exception error) {
                    String message = message(error);
                    for (ScanRecord scan : batch) setRowError(scan, message);
                    setStatus("Ошибка сверки: " + message);
                } finally {
                    workerRunning = false;
                    if (!pending.isEmpty() && connector != null) processPending();
                }
            }
        }.execute();
    }

    private List<ComparisonResult> compare(List<ScanRecord> scans, SetRetailXConnector activeConnector) {
        Map<ScanRecord, Product> local = new LinkedHashMap<>();
        List<ComparisonResult> results = new ArrayList<>();
        Set<String> markings = new LinkedHashSet<>();
        for (ScanRecord scan : scans) {
            try {
                Product product = repository.findByBarcode(scan.probe.barcode());
                if (product == null) {
                    product = activeConnector.searchOnlineProduct(scan.probe.barcode()).join().orElse(null);
                    if (product == null) {
                        results.add(ComparisonResult.error(scan,
                                "Штрихкод не найден в локальной базе и SetRetailX"));
                        continue;
                    }
                }
                String marking = product.vendorCode().isEmpty()
                        ? product.stCode() : product.vendorCode() + "_" + product.uom();
                if (marking.isBlank()) {
                    results.add(ComparisonResult.error(scan, "Не удалось определить артикул SetRetailX"));
                    continue;
                }
                local.put(scan, product);
                markings.add(marking);
            } catch (Exception error) {
                results.add(ComparisonResult.error(scan, message(error)));
            }
        }
        if (markings.isEmpty()) return results;
        try {
            List<ProductDto> remoteProducts = new ArrayList<>();
            List<String> ids = new ArrayList<>(markings);
            for (int start = 0; start < ids.size(); start += 100) {
                remoteProducts.addAll(activeConnector.getProducts(
                        ids.subList(start, Math.min(start + 100, ids.size())), 12).join());
            }
            Map<String, ProductDto> byMarking = new LinkedHashMap<>();
            Map<String, ProductDto> byBarcode = new LinkedHashMap<>();
            for (ProductDto product : remoteProducts) {
                if (product.marking() != null) byMarking.putIfAbsent(product.marking(), product);
                String barcode = first(product.barCode(), product.barcode());
                if (!barcode.isEmpty()) byBarcode.putIfAbsent(barcode, product);
            }
            for (Map.Entry<ScanRecord, Product> entry : local.entrySet()) {
                Product product = entry.getValue();
                String marking = product.vendorCode().isEmpty()
                        ? product.stCode() : product.vendorCode() + "_" + product.uom();
                ProductDto remote = byBarcode.get(product.barcode());
                if (remote == null) remote = byMarking.get(marking);
                if (remote == null) {
                    results.add(ComparisonResult.error(entry.getKey(), "Товар не вернулся из SetRetailX"));
                    continue;
                }
                Map<Integer, BigDecimal> normal = prices(remote.pricesMap());
                Map<Integer, BigDecimal> promo = prices(remote.prices99Map());
                Map<Integer, Boolean> matches = new LinkedHashMap<>();
                for (int number = 1; number <= 4; number++) {
                    BigDecimal scanned = entry.getKey().prices.get(number);
                    BigDecimal standard = normal.get(number);
                    BigDecimal sale99 = promo.get(number);
                    matches.put(number, number == 3 || scanned == null || (standard == null && sale99 == null) ? null
                            : samePrice(scanned, standard) || samePrice(scanned, sale99));
                }
                results.add(new ComparisonResult(entry.getKey(), product, normal, promo, matches, null));
            }
        } catch (Exception error) {
            String failure = message(error);
            for (ScanRecord scan : local.keySet()) results.add(ComparisonResult.error(scan, failure));
        }
        return results;
    }

    private void appendError(ScannedProbe probe, String error) {
        ScanRecord scan = new ScanRecord(probe, Map.of(), model.getRowCount());
        model.addRow(row(scan, error, null, null, null, null));
        table.getModel().setValueAt(error, scan.row, 20);
        setStatus(error);
    }

    private void setRowError(ScanRecord scan, String error) {
        Object[] values = row(scan, error, null, null, null, null);
        values[20] = error;
        updateRow(scan, values);
        setStatus(error);
    }

    private Object[] row(ScanRecord scan, String state, Map<Integer, BigDecimal> normal,
                         Map<Integer, BigDecimal> promo, Map<Integer, Boolean> matches, String marking) {
        Object[] values = new Object[21];
        values[0] = state;
        values[1] = marking == null || marking.isBlank() ? "—" : marking;
        values[2] = scan.probe.barcode();
        for (int i = 1; i <= 4; i++) values[2 + i] = format(scan.prices.get(i));
        values[7] = format(finalPrice(scan.prices));
        for (int i = 1; i <= 4; i++) {
            values[7 + i] = format(normal == null ? null : normal.get(i));
            values[11 + i] = format(promo == null ? null : promo.get(i));
            Boolean match = matches == null ? null : matches.get(i);
            values[15 + i] = match == null ? "—" : match ? "✓" : "✗";
        }
        if (matches == null) {
            values[20] = "—";
        } else if (matches.values().stream().anyMatch(Boolean.FALSE::equals)) {
            values[20] = "Не совпадает";
        } else if (matches.values().stream().noneMatch(value -> value != null)) {
            values[20] = "Нет данных для сравнения";
        } else {
            values[20] = "Совпадает";
        }
        if (matches != null && matches.values().stream().anyMatch(Boolean.FALSE::equals)) {
            values[20] = "В очередь печати";
        }
        return values;
    }

    private void updateRow(ScanRecord scan, Object[] values) {
        if (scan.row >= model.getRowCount()) return;
        for (int column = 0; column < values.length; column++) model.setValueAt(values[column], scan.row, column);
        table.setToolTipText(values[0] instanceof String && ((String) values[0]).contains("не найден")
                ? (String) values[0] : null);
    }

    private static Map<Integer, BigDecimal> prices(Map<String, JsonNode> source) {
        Map<Integer, BigDecimal> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : source.entrySet()) {
            JsonNode value = entry.getValue();
            if (value == null || !value.isObject()) continue;
            JsonNode numberNode = value.get("number");
            String number = numberNode == null || numberNode.isNull() ? entry.getKey() : numberNode.asText();
            if (!number.matches("[1-4]")) continue;
            JsonNode priceNode = value.get("value");
            if (priceNode == null || priceNode.isNull()) continue;
            try {
                result.put(Integer.parseInt(number), new BigDecimal(priceNode.asText()));
            } catch (NumberFormatException ignored) {
                // Ignore malformed SetRetailX price entries; compare valid price numbers only.
            }
        }
        return result;
    }

    private static String format(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static BigDecimal finalPrice(Map<Integer, BigDecimal> scanned) {
        BigDecimal discounted = scanned.get(4);
        return discounted == null ? scanned.get(2) : discounted;
    }

    private static boolean samePrice(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String message(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }

    private record ScanRecord(ScannedProbe probe, Map<Integer, BigDecimal> prices, int row) { }

    private record ComparisonResult(ScanRecord scan, Product product, Map<Integer, BigDecimal> normal,
                                    Map<Integer, BigDecimal> promo, Map<Integer, Boolean> matches, String error) {
        private static ComparisonResult error(ScanRecord scan, String error) {
            return new ComparisonResult(scan, null, Map.of(), Map.of(), Map.of(), error);
        }

        private boolean mismatched() { return matches.values().stream().anyMatch(Boolean.FALSE::equals); }
    }
}
