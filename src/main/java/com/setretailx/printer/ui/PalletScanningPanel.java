package com.setretailx.printer.ui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.setretailx.printer.model.Product;
import com.setretailx.printer.repository.AddressStorageRepository;
import com.setretailx.printer.repository.AddressStorageRepository.StorageAddress;
import com.setretailx.printer.repository.AddressStorageRepository.StorageBox;
import com.setretailx.printer.repository.AddressStorageRepository.StoredProduct;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterJob;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Dedicated scan session that creates one-product virtual boxes on a selected pallet. */
@SuppressWarnings("serial")
final class PalletScanningPanel extends JPanel {
    static final String CLOSE_BOX_BARCODE = "retailx-proto://close-box";
    private static final long DIFFERENT_PRODUCT_WINDOW_MS = 15_000;
    private static final int DIFFERENT_PRODUCT_CLOSE_SCANS = 3;

    private final AddressStorageRepository repository;
    private final JComboBox<StorageAddress> addresses = new JComboBox<>();
    private final DefaultTableModel boxesModel = new DefaultTableModel(
            new String[]{"Коробка", "Артикул", "Товар", "Кол-во"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable boxesTable = new JTable(boxesModel);
    private final JLabel activeBox = new JLabel("Активная коробка: нет");
    private final JLabel status = new JLabel("Выберите паллет — адрес хранения, затем сканируйте товары.");
    private final Map<Long, SessionBox> sessionBoxes = new LinkedHashMap<>();
    private Long currentBoxId;
    private String currentProductKey;
    private boolean scanInProgress;
    private boolean updatingAddresses;
    private boolean addressPromptPending;
    private long currentScanStartedAt;
    private long firstDifferentProductScanAt;
    private int differentProductScanCount;

    PalletScanningPanel(AddressStorageRepository repository) {
        super(new BorderLayout(8, 8));
        this.repository = Objects.requireNonNull(repository, "repository");
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(buildControls(), BorderLayout.NORTH);
        boxesTable.setFillsViewportHeight(true);
        boxesTable.setRowHeight(26);
        add(new JScrollPane(boxesTable), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 6));
        JPanel info = new JPanel(new BorderLayout(4, 4));
        info.add(activeBox, BorderLayout.NORTH);
        info.add(status, BorderLayout.SOUTH);
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.TRAILING, 6, 0));
        JButton closeBox = new JButton("Закрыть коробку");
        closeBox.addActionListener(event -> closeCurrentBox(message -> setStatus(message)));
        JButton print = new JButton("Печать наклеек текущей сессии · 2×4 A4");
        print.addActionListener(event -> printSessionLabels());
        actions.add(closeBox);
        actions.add(print);
        footer.add(info, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);

        addresses.setPrototypeDisplayValue(new StorageAddress("ПАЛЛЕТ-01", "Паллет у зоны отгрузки"));
        addresses.addActionListener(event -> {
            if (!updatingAddresses) resetSessionForAddress();
        });
        refreshAddresses(null);
    }

    private JPanel buildControls() {
        JPanel controls = new JPanel(new BorderLayout(6, 8));
        JPanel top = new JPanel(new BorderLayout(6, 8));
        JPanel palletRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 6, 0));
        palletRow.add(new JLabel("Паллет (адрес хранения):"));
        palletRow.add(addresses);
        JButton refresh = new JButton("Обновить адреса");
        refresh.addActionListener(event -> refreshAddresses(selectedAddressIdFromSession));
        palletRow.add(refresh);
        top.add(palletRow, BorderLayout.NORTH);
        JLabel hint = new JLabel("Сканируйте в окне «Единый сканер» после выбора адреса. Для закрытия коробки используйте кнопку ниже или сервисный штрихкод.");
        hint.setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));
        top.add(hint, BorderLayout.SOUTH);
        controls.add(top, BorderLayout.NORTH);
        return controls;
    }

    void focusScanner() {
        // The actual scan field lives in the shared ScannerPanel.
    }

    void prepareForScanning() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (selectedAddressIdFromSession == null) {
                addressPromptPending = true;
                promptForAddressIfReady();
            }
        });
    }

    private void promptForAddressIfReady() {
        if (!addressPromptPending || addresses.getItemCount() == 0) return;
        addressPromptPending = false;
        JComboBox<StorageAddress> selector = new JComboBox<>();
        for (int i = 0; i < addresses.getItemCount(); i++) selector.addItem(addresses.getItemAt(i));
        selector.setSelectedIndex(-1);
        JPanel prompt = new JPanel(new BorderLayout(6, 6));
        prompt.add(new JLabel("Выберите адрес хранения, который будет использоваться как паллет:"), BorderLayout.NORTH);
        prompt.add(selector, BorderLayout.CENTER);
        int answer = JOptionPane.showConfirmDialog(this, prompt, "Выбор паллета",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer == JOptionPane.OK_OPTION && selector.getSelectedItem() instanceof StorageAddress selected) {
            selectAddress(selected.addressId());
        }
    }

    private void selectAddress(String addressId) {
        updatingAddresses = true;
        for (int i = 0; i < addresses.getItemCount(); i++) {
            if (addresses.getItemAt(i).addressId().equals(addressId)) {
                addresses.setSelectedIndex(i);
                break;
            }
        }
        updatingAddresses = false;
        selectedAddressIdFromSession = selectedAddressId();
        setStatus("Паллет выбран. Сканируйте первый товар для автоматического создания коробки.");
    }

    /** Claims a scan before asynchronous product lookup, preventing overlapping scans. */
    boolean beginProductLookup() {
        if (addresses.getSelectedItem() == null) {
            setStatus("Сначала выберите паллет в списке адресов хранения.");
            return false;
        }
        if (scanInProgress) {
            setStatus("Предыдущий скан ещё обрабатывается. Повторите скан после завершения операции.");
            return false;
        }
        scanInProgress = true;
        currentScanStartedAt = System.currentTimeMillis();
        addresses.setEnabled(false);
        setStatus("Ищем товар по штрихкоду…");
        return true;
    }

    void scanFailed(String message) {
        scanInProgress = false;
        currentScanStartedAt = 0;
        if (currentBoxId == null) addresses.setEnabled(true);
        setStatus(message);
    }

    StorageAddress selectedAddress() { return (StorageAddress) addresses.getSelectedItem(); }

    void refreshAvailableAddresses() { refreshAddresses(selectedAddressIdFromSession); }

    void addScannedProduct(Product product, Consumer<String> completion) {
        Objects.requireNonNull(completion, "completion");
        StorageAddress address = (StorageAddress) addresses.getSelectedItem();
        if (address == null) {
            scanFailed("Сначала выберите паллет в окне сканирования паллет.");
            completion.accept("Сначала выберите паллет.");
            return;
        }
        // The caller claims the scan before lookup and keeps it claimed through persistence.
        scanInProgress = true;
        if (currentScanStartedAt == 0) currentScanStartedAt = System.currentTimeMillis();
        String productKey = productKey(product);
        if (currentProductKey != null && !currentProductKey.equals(productKey)) {
            if (differentProductScanCount == 0 || currentScanStartedAt - firstDifferentProductScanAt > DIFFERENT_PRODUCT_WINDOW_MS) {
                firstDifferentProductScanAt = currentScanStartedAt;
                differentProductScanCount = 0;
            }
            differentProductScanCount++;
            scanInProgress = false;
            currentScanStartedAt = 0;
            addresses.setEnabled(false);
            if (differentProductScanCount >= DIFFERENT_PRODUCT_CLOSE_SCANS) {
                resetDifferentProductScans();
                String message = "Коробка закрыта после трёх сканов другого товара за 15 секунд. "
                        + "Этот товар не добавлен; отсканируйте его ещё раз, чтобы открыть новую коробку.";
                closeBoxState();
                setStatus(message);
                completion.accept(message);
                return;
            }
            String message = "В коробке другой товар. Скан " + differentProductScanCount + "/"
                    + DIFFERENT_PRODUCT_CLOSE_SCANS + " в течение 15 секунд закроет коробку; товар не добавлен.";
            setStatus(message);
            completion.accept(message);
            return;
        }
        resetDifferentProductScans();
        boolean createBox = currentBoxId == null;
        Long boxId = currentBoxId;
        SessionBox activeSessionBox = createBox ? null : sessionBoxes.get(boxId);
        if (!createBox && activeSessionBox == null) {
            scanFailed("Активная коробка не найдена в текущей сессии. Закройте её и начните новую.");
            completion.accept("Не удалось определить активную коробку.");
            return;
        }
        run("Сохраняем скан товара…", () -> {
            StorageBox newBox = createBox ? repository.createBox(address.addressId()) : null;
            long targetBoxId = createBox ? newBox.boxId() : boxId;
            try {
                if (!createBox && repository.box(targetBoxId) == null) {
                    throw new IllegalStateException("Активная коробка больше не существует в базе хранения.");
                }
                repository.addProduct(targetBoxId, product);
            } catch (Exception error) {
                if (newBox != null) {
                    try { repository.deleteBox(newBox.boxId()); }
                    catch (Exception cleanupError) { error.addSuppressed(cleanupError); }
                }
                throw error;
            }
            String boxCode = createBox ? newBox.boxCode() : activeSessionBox.boxCode();
            return new ScanResult(targetBoxId, boxCode, createBox);
        }, result -> {
            currentScanStartedAt = 0;
            if (result.created()) {
                currentBoxId = result.boxId();
                currentProductKey = productKey;
                sessionBoxes.put(result.boxId(), new SessionBox(result.boxId(), result.boxCode(),
                        new StoredProduct(product.stCode(), product.name(), product.marking(), product.barcode(), 1)));
            } else {
                SessionBox previous = sessionBoxes.get(result.boxId());
                if (previous != null) {
                    StoredProduct old = previous.product();
                    sessionBoxes.put(result.boxId(), new SessionBox(previous.boxId(), previous.boxCode(),
                            new StoredProduct(old.productId(), old.name(), old.vendorCode(), old.barcode(), old.quantity() + 1)));
                }
            }
            scanInProgress = false;
            addresses.setEnabled(currentBoxId == null);
            refreshSessionRows();
            activeBox.setText("Активная коробка: " + result.boxCode() + " · " + product.name());
            String message = "Добавлен в коробку " + result.boxCode() + ": " + product.name();
            setStatus(message);
            completion.accept(message);
        }, message -> {
            scanInProgress = false;
            currentScanStartedAt = 0;
            addresses.setEnabled(currentBoxId == null);
            setStatus("Не удалось сохранить скан: " + message);
            completion.accept("Не удалось добавить товар в паллет: " + message);
        });
    }

    void closeCurrentBox(Consumer<String> completion) {
        if (scanInProgress) {
            completion.accept("Дождитесь завершения обработки текущего скана.");
            return;
        }
        if (currentBoxId == null) {
            String message = "Активной коробки нет — отсканируйте товар, чтобы начать новую.";
            setStatus(message);
            completion.accept(message);
            return;
        }
        String message = "Коробка закрыта. Сканируйте первый товар следующей коробки.";
        closeBoxState();
        setStatus(message);
        completion.accept(message);
    }

    private void closeBoxState() {
        currentBoxId = null;
        currentProductKey = null;
        resetDifferentProductScans();
        addresses.setEnabled(true);
        activeBox.setText("Активная коробка: нет");
    }

    private void resetDifferentProductScans() {
        firstDifferentProductScanAt = 0;
        differentProductScanCount = 0;
    }

    private void refreshAddresses(String selectId) {
        run("Загружаем адреса паллет…", repository::addresses, rows -> {
            updatingAddresses = true;
            addresses.removeAllItems();
            rows.forEach(addresses::addItem);
            if (selectId != null) {
                for (int i = 0; i < addresses.getItemCount(); i++) {
                    if (addresses.getItemAt(i).addressId().equals(selectId)) {
                        addresses.setSelectedIndex(i);
                        break;
                    }
                }
            } else if (selectedAddressIdFromSession == null) {
                addresses.setSelectedIndex(-1);
            }
            updatingAddresses = false;
            if (rows.isEmpty()) setStatus("Адресов нет. Создайте адрес хранения и используйте его как паллет.");
            promptForAddressIfReady();
        }, this::showError);
    }

    private void resetSessionForAddress() {
        if (currentBoxId != null || scanInProgress) {
            JOptionPane.showMessageDialog(this,
                    scanInProgress
                            ? "Дождитесь завершения текущей операции перед сменой паллета."
                            : "Сначала закройте активную коробку сервисным штрихкодом " + CLOSE_BOX_BARCODE + ".",
                    "Нельзя сменить паллет", JOptionPane.WARNING_MESSAGE);
            restoreSessionAddress();
            return;
        }
        if (!sessionBoxes.isEmpty()) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "Сменить паллет? Текущая сессия останется в базе и её наклейки не войдут в текущий список.",
                    "Сменить паллет", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                restoreSessionAddress();
                return;
            }
        }
        selectedAddressIdFromSession = selectedAddressId();
        currentBoxId = null;
        currentProductKey = null;
        resetDifferentProductScans();
        sessionBoxes.clear();
        boxesModel.setRowCount(0);
        activeBox.setText("Активная коробка: нет");
        setStatus(selectedAddressIdFromSession == null
                ? "Выберите паллет — адрес хранения, затем сканируйте товары."
                : "Паллет выбран. Сканируйте первый товар для автоматического создания коробки.");
    }

    private void restoreSessionAddress() {
        updatingAddresses = true;
        for (int i = 0; i < addresses.getItemCount(); i++) {
            if (Objects.equals(addresses.getItemAt(i).addressId(), selectedAddressIdFromSession)) {
                addresses.setSelectedIndex(i);
                break;
            }
        }
        updatingAddresses = false;
    }

    private String selectedAddressIdFromSession;

    private void refreshSessionRows() {
        boxesModel.setRowCount(0);
        for (SessionBox row : sessionBoxes.values()) boxesModel.addRow(new Object[]{row.boxCode(),
                displayArticle(row.product()), row.product().name(), row.product().quantity()});
    }

    private void printSessionLabels() {
        if (scanInProgress) {
            setStatus("Дождитесь завершения обработки скана перед печатью.");
            return;
        }
        if (sessionBoxes.isEmpty()) {
            setStatus("В текущей сессии пока нет коробок для печати.");
            return;
        }
        List<LabelItem> labels = new ArrayList<>(sessionBoxes.size());
        try {
            for (SessionBox row : sessionBoxes.values()) {
                labels.add(new LabelItem(articleForLabel(row.product()), row.product().name(), row.product().quantity()));
            }
        } catch (IllegalArgumentException error) {
            setStatus(error.getMessage());
            JOptionPane.showMessageDialog(this, error.getMessage(), "Некорректный артикул",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        submitLabels(labels);
    }

    private void submitLabels(List<LabelItem> labels) {
        if (labels.isEmpty()) {
            setStatus("Нет заполненных коробок для печати.");
            return;
        }
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("Наклейки паллеты · " + (selectedAddressId() == null ? "сессия" : selectedAddressId()));
        PageFormat format = job.defaultPage();
        Paper paper = new Paper();
        double width = 210 / 25.4 * 72;
        double height = 297 / 25.4 * 72;
        double margin = 5 / 25.4 * 72;
        paper.setSize(width, height);
        paper.setImageableArea(margin, margin, width - margin * 2, height - margin * 2);
        format.setPaper(paper);
        format.setOrientation(PageFormat.PORTRAIT);
        job.setPrintable(new PalletLabelPrintable(labels), format);
        if (!job.printDialog()) return;
        try {
            job.print();
            setStatus("Наклейки отправлены на печать: " + labels.size() + " коробок, сетка 2×4 на A4.");
        } catch (Exception error) {
            showError(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private String selectedAddressId() {
        StorageAddress address = (StorageAddress) addresses.getSelectedItem();
        return address == null ? null : address.addressId();
    }

    private static String productKey(Product product) {
        if (!product.marking().isBlank()) return "article:" + product.marking().trim().toLowerCase(java.util.Locale.ROOT);
        if (!product.barcode().isBlank()) return "barcode:" + product.barcode().trim();
        if (!product.stCode().isBlank()) return "id:" + product.stCode().trim();
        return "";
    }

    private static String displayArticle(StoredProduct product) {
        return product.vendorCode().isBlank() ? product.productId() : product.vendorCode();
    }

    private static String articleForLabel(StoredProduct product) {
        String article = displayArticle(product).trim();
        int suffix = article.indexOf('_');
        if (suffix > 0) article = article.substring(0, suffix);
        if (!article.matches("\\d{1,6}")) {
            throw new IllegalArgumentException("Артикул товара «" + product.name()
                    + "» должен состоять из 1–6 цифр для печати шестизначной этикетки: «" + article + "».");
        }
        return "0".repeat(6 - article.length()) + article;
    }

    private <T> void run(String message, Work<T> work, Consumer<T> success, Consumer<String> failure) {
        setStatus(message);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return work.run(); }
            @Override protected void done() {
                try { success.accept(get()); }
                catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    failure.accept(cause.getMessage() == null ? cause.toString() : cause.getMessage());
                }
            }
        }.execute();
    }

    private void showError(String message) {
        setStatus("Ошибка: " + message);
        JOptionPane.showMessageDialog(this, message, "Сканирование паллет", JOptionPane.ERROR_MESSAGE);
    }

    private void setStatus(String message) { status.setText(message); }

    private record ScanResult(long boxId, String boxCode, boolean created) { }
    private record SessionBox(long boxId, String boxCode, StoredProduct product) { }
    private record LabelItem(String article, String name, int quantity) { }
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }

    private static final class PalletLabelPrintable implements Printable {
        private static final double GAP = 7 / 25.4 * 72;
        private static final Font ARTICLE_FONT = new Font(Font.MONOSPACED, Font.BOLD, 42);
        private static final Font NAME_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        private static final Font QUANTITY_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 15);
        private final List<LabelItem> labels;
        private final List<BufferedImage> qrCodes;

        private PalletLabelPrintable(List<LabelItem> labels) {
            this.labels = List.copyOf(labels);
            this.qrCodes = labels.stream().map(item -> createQrCode(item.article())).toList();
        }

        @Override public int print(Graphics graphics, PageFormat format, int pageIndex) {
            int perPage = 8;
            int pages = (labels.size() + perPage - 1) / perPage;
            if (pageIndex >= pages) return NO_SUCH_PAGE;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(format.getImageableX(), format.getImageableY());
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                double cellWidth = (format.getImageableWidth() - GAP) / 2;
                double cellHeight = (format.getImageableHeight() - GAP * 3) / 4;
                int from = pageIndex * perPage;
                int to = Math.min(labels.size(), from + perPage);
                for (int index = from; index < to; index++) {
                    int local = index - from;
                    int column = local % 2;
                    int row = local / 2;
                    double x = column * (cellWidth + GAP);
                    double y = row * (cellHeight + GAP);
                    drawLabel(g, labels.get(index), qrCodes.get(index), x, y, cellWidth, cellHeight);
                }
                return PAGE_EXISTS;
            } finally {
                g.dispose();
            }
        }

        private static void drawLabel(Graphics2D g, LabelItem label, BufferedImage qr, double x, double y,
                                      double width, double height) {
            g.setColor(Color.BLACK);
            g.drawRect((int) x, (int) y, (int) width, (int) height);
            int padding = 10;
            int qrSize = (int) Math.min(60, height * 0.42);
            int qrX = (int) (x + width - qrSize - padding);
            int qrY = (int) (y + padding);
            if (qr != null) g.drawImage(qr, qrX, qrY, qrSize, qrSize, null);

            int articleWidth = qrX - (int) x - padding * 2;
            String article = safe(label.article());
            Font articleFont = ARTICLE_FONT;
            while (articleFont.getSize() > 22 && g.getFontMetrics(articleFont).stringWidth(article) > articleWidth) {
                articleFont = articleFont.deriveFont((float) (articleFont.getSize() - 1));
            }
            g.setFont(articleFont);
            FontMetrics articleMetrics = g.getFontMetrics();
            int articleBaseline = (int) (y + padding + Math.max(articleMetrics.getAscent(), qrSize * 0.62));
            g.drawString(article, (int) x + padding, articleBaseline);

            g.setFont(NAME_FONT);
            drawWrappedName(g, safe(label.name()), (int) x + padding,
                    (int) (y + height * 0.56), width - padding * 2, height * 0.32);
            if (label.quantity() > 1) {
                g.setFont(QUANTITY_FONT);
                g.drawString("Кол-во: " + label.quantity(), (int) x + padding,
                        (int) (y + height - padding - 2));
            }
        }

        private static void drawWrappedName(Graphics2D g, String text, int x, int y, double width, double height) {
            java.awt.FontMetrics metrics = g.getFontMetrics();
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && metrics.stringWidth(candidate) > width) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else line = new StringBuilder(candidate);
            }
            if (!line.isEmpty()) lines.add(line.toString());
            int maxLines = Math.max(1, (int) (height / metrics.getHeight()));
            for (int i = 0; i < Math.min(lines.size(), maxLines); i++) {
                g.drawString(lines.get(i), x, y + metrics.getAscent() + i * metrics.getHeight());
            }
        }

        private static BufferedImage createQrCode(String value) {
            if (value == null || value.isBlank()) return null;
            try {
                return MatrixToImageWriter.toBufferedImage(new QRCodeWriter().encode(value.trim(),
                        BarcodeFormat.QR_CODE, 180, 180, Map.of(EncodeHintType.MARGIN, 1)));
            } catch (WriterException | IllegalArgumentException ignored) {
                return null;
            }
        }

        private static String safe(String value) { return value == null ? "" : value; }
    }
}
