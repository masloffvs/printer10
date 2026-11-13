package com.setretailx.printer.ui;

import com.setretailx.printer.model.Product;
import com.setretailx.printer.repository.AddressStorageRepository;
import com.setretailx.printer.repository.AddressStorageRepository.LocationResult;
import com.setretailx.printer.repository.AddressStorageRepository.StorageAddress;
import com.setretailx.printer.repository.AddressStorageRepository.StorageBox;
import com.setretailx.printer.repository.AddressStorageRepository.StoredProduct;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
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
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.BasicStroke;
import java.awt.print.Printable;
import java.awt.print.PageFormat;
import java.awt.print.PrinterJob;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.function.Consumer;

/** MDI screen for selecting boxes, scanning their contents, and printing box labels. */
@SuppressWarnings("serial")
final class AddressStoragePanel extends JPanel {
    private final AddressStorageRepository repository;
    private final JComboBox<StorageAddress> addresses = new JComboBox<>();
    private final JComboBox<StorageBox> boxes = new JComboBox<>();
    private final DefaultTableModel productsModel = model("Наименование", "Артикул", "Штрихкод", "Кол-во");
    private final JTable productsTable = new JTable(productsModel);
    private final JLabel status = new JLabel("Выберите адрес и создайте коробку, чтобы начать сканирование.");
    private boolean updatingSelectors;

    AddressStoragePanel(AddressStorageRepository repository) {
        super(new BorderLayout(8, 8));
        this.repository = repository;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(buildStorageControls(), BorderLayout.NORTH);
        add(buildContent(), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        addresses.addActionListener(event -> {
            if (!updatingSelectors) refreshBoxes(null);
        });
        boxes.addActionListener(event -> {
            if (!updatingSelectors) refreshProducts();
        });
        refreshAddresses(null);
    }

    private JComponent buildStorageControls() {
        JPanel panel = new JPanel(new BorderLayout(8, 6));
        JPanel addressRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 6, 0));
        addressRow.add(new JLabel("Адрес хранения:"));
        addresses.setPrototypeDisplayValue(new StorageAddress("Склад-01", "Основной склад"));
        addressRow.add(addresses);
        panel.add(addressRow, BorderLayout.NORTH);

        JPanel boxRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 6, 0));
        boxRow.add(new JLabel("Коробка:"));
        boxes.setPrototypeDisplayValue(new StorageBox(0, "", "АЕБ123", 0, 0));
        boxRow.add(boxes);
        JButton addBox = new JButton("Создать и открыть коробку");
        addBox.addActionListener(event -> createBox());
        boxRow.add(addBox);
        JButton deleteBox = new JButton("Удалить коробку");
        deleteBox.addActionListener(event -> deleteBox());
        boxRow.add(deleteBox);
        JButton printLabel = new JButton("Печать наклейки");
        printLabel.addActionListener(event -> printLabel());
        boxRow.add(printLabel);
        panel.add(boxRow, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildContent() {
        JPanel content = new JPanel(new BorderLayout(10, 0));
        JPanel boxContents = new JPanel(new BorderLayout(4, 4));
        boxContents.setBorder(BorderFactory.createTitledBorder("Товары в выбранной коробке"));
        boxContents.add(new JScrollPane(productsTable), BorderLayout.CENTER);
        content.add(boxContents, BorderLayout.CENTER);
        return content;
    }

    private void createBox() {
        StorageAddress address = (StorageAddress) addresses.getSelectedItem();
        if (address == null) {
            setStatus("Сначала создайте или выберите адрес.");
            return;
        }
        run("Создаём коробку…", () -> repository.createBox(address.addressId()),
                box -> refreshBoxes(box.boxId()), this::showError);
    }

    private void deleteBox() {
        StorageBox box = (StorageBox) boxes.getSelectedItem();
        if (box == null) {
            setStatus("Выберите коробку для удаления.");
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Удалить коробку «" + box.boxCode() + "» и все записи о товарах в ней? Это действие нельзя отменить.",
                "Удалить коробку", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        run("Удаляем коробку…", () -> {
            repository.deleteBox(box.boxId());
            return null;
        }, ignored -> refreshBoxes(null), this::showError);
    }

    private void refreshAddresses(String selectId) {
        run("Загружаем адреса…", repository::addresses, result -> {
            updatingSelectors = true;
            addresses.removeAllItems();
            result.forEach(addresses::addItem);
            if (selectId != null) {
                for (int i = 0; i < addresses.getItemCount(); i++) {
                    if (addresses.getItemAt(i).addressId().equals(selectId)) {
                        addresses.setSelectedIndex(i);
                        break;
                    }
                }
            }
            updatingSelectors = false;
            refreshBoxes(null);
            if (result.isEmpty()) setStatus("Адресов пока нет — создайте виртуальный адрес.");
        }, this::showError);
    }

    private void refreshBoxes(Long selectBoxId) {
        StorageAddress address = (StorageAddress) addresses.getSelectedItem();
        if (address == null) {
            updatingSelectors = true;
            boxes.removeAllItems();
            updatingSelectors = false;
            boxes.setEnabled(false);
            refreshProducts();
            return;
        }
        updatingSelectors = true;
        boxes.removeAllItems();
        updatingSelectors = false;
        boxes.setEnabled(false);
        refreshProducts();
        run("Загружаем коробки…", () -> repository.boxes(address.addressId()), result -> {
            updatingSelectors = true;
            boxes.removeAllItems();
            result.forEach(boxes::addItem);
            if (selectBoxId != null) {
                for (int i = 0; i < boxes.getItemCount(); i++) {
                    if (boxes.getItemAt(i).boxId() == selectBoxId) {
                        boxes.setSelectedIndex(i);
                        break;
                    }
                }
            }
            updatingSelectors = false;
            boxes.setEnabled(true);
            refreshProducts();
            if (result.isEmpty()) setStatus("В выбранном адресе нет коробок. Создайте коробку, чтобы начать сканирование.");
        }, message -> {
            boxes.setEnabled(true);
            showError(message);
        });
    }

    private void refreshProducts() {
        StorageBox box = (StorageBox) boxes.getSelectedItem();
        if (box == null) {
            productsModel.setRowCount(0);
            return;
        }
        run("Загружаем содержимое коробки…", () -> repository.productsInBox(box.boxId()), rows -> {
            productsModel.setRowCount(0);
            for (StoredProduct product : rows) productsModel.addRow(new Object[]{product.name(), product.vendorCode(),
                    product.barcode(), product.quantity()});
            updateSelectedBoxCount(box, rows.size());
            setStatus("Коробка " + box.boxCode() + " открыта · товаров: " + rows.size()
                    + " · сканируйте товары в универсальном сканере.");
        }, this::showError);
    }

    boolean hasOpenBox() { return boxes.getSelectedItem() != null; }

    void refreshAvailableAddresses() {
        StorageAddress selected = (StorageAddress) addresses.getSelectedItem();
        refreshAddresses(selected == null ? null : selected.addressId());
    }

    void addScannedProduct(Product product, Consumer<String> completion) {
        StorageBox box = (StorageBox) boxes.getSelectedItem();
        if (box == null) {
            completion.accept("Откройте коробку в окне адресного хранения перед сканированием.");
            return;
        }
        run("Добавляем товар в коробку " + box.boxCode() + "…", () -> {
            repository.addProduct(box.boxId(), product);
            return null;
        }, ignored -> {
            refreshProducts();
            setStatus("Добавлен в " + box.boxCode() + ": " + product.name());
            completion.accept("Добавлен в коробку " + box.boxCode() + ": " + product.name());
        }, message -> completion.accept("Не удалось добавить товар: " + message));
    }

    private void printLabel() {
        StorageBox box = (StorageBox) boxes.getSelectedItem();
        StorageAddress address = (StorageAddress) addresses.getSelectedItem();
        if (box == null || address == null) {
            setStatus("Выберите адрес и коробку для печати наклейки.");
            return;
        }
        run("Готовим наклейку…", () -> repository.locationsInBox(box.boxId()), rows -> {
            submitPrint(address, box, rows);
        }, this::showError);
    }

    private void submitPrint(StorageAddress address, StorageBox box, List<LocationResult> rows) {
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("Состав коробки " + box.boxCode());
        PageFormat pageFormat = job.defaultPage();
        pageFormat.setOrientation(PageFormat.LANDSCAPE);
        job.setPrintable(new BoxLabelPrintable(address, box, rows), pageFormat);
        if (!job.printDialog()) return;
        try {
            job.print();
            setStatus("Табличная наклейка коробки " + box.boxCode() + " отправлена на печать.");
        } catch (Exception error) {
            showError(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    /** A compact landscape box label with a scannable QR code in every product row. */
    private static final class BoxLabelPrintable implements Printable {
        private static final double OUTER_MARGIN = 3;
        private static final double HEADER_HEIGHT = 68;
        private static final double TABLE_HEADER_HEIGHT = 25;
        private static final double ROW_HEIGHT = 54;
        private static final Font BODY = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        private static final Font HEADER = new Font(Font.SANS_SERIF, Font.BOLD, 15);
        private static final Font BOX_CODE = new Font(Font.SANS_SERIF, Font.BOLD, 38);
        private static final Font ARTICLE = new Font(Font.SANS_SERIF, Font.BOLD, 19);
        private static final String[] COLUMNS = {"№", "НАИМЕНОВАНИЕ ТОВАРА", "АРТИКУЛ", "QR-КОД", "КОЛ-ВО"};
        private static final double[] WIDTHS = {0.05, 0.35, 0.27, 0.18, 0.15};
        private static final BasicStroke DASHED_STROKE = new BasicStroke(0.7f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 10f, new float[]{2.2f, 2.2f}, 0f);

        private final StorageAddress address;
        private final StorageBox box;
        private final List<LocationResult> rows;
        private final List<BufferedImage> qrCodes;

        private BoxLabelPrintable(StorageAddress address, StorageBox box, List<LocationResult> rows) {
            this.address = address;
            this.box = box;
            this.rows = List.copyOf(rows);
            this.qrCodes = new ArrayList<>(rows.size());
            for (LocationResult row : rows) qrCodes.add(createQrCode(row.barcode()));
        }

        @Override public int print(java.awt.Graphics graphics, PageFormat pageFormat, int pageIndex) {
            double width = pageFormat.getImageableWidth() - OUTER_MARGIN * 2;
            double height = pageFormat.getImageableHeight() - OUTER_MARGIN * 2;
            double tableBodyHeight = height - HEADER_HEIGHT - TABLE_HEADER_HEIGHT;
            int rowsPerPage = Math.max(1, (int) Math.floor(tableBodyHeight / ROW_HEIGHT));
            int pageCount = Math.max(1, (int) Math.ceil((double) Math.max(1, rows.size()) / rowsPerPage));
            if (pageIndex >= pageCount) return NO_SUCH_PAGE;

            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(pageFormat.getImageableX() + OUTER_MARGIN, pageFormat.getImageableY() + OUTER_MARGIN);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                drawHeader(g, width, pageIndex, pageCount);
                double tableTop = HEADER_HEIGHT;
                drawTableHeader(g, width, tableTop);
                int start = pageIndex * rowsPerPage;
                int end = Math.min(rows.size(), start + rowsPerPage);
                int visibleRows = Math.max(1, end - start);
                double rowHeight = tableBodyHeight / visibleRows;
                if (rows.isEmpty()) {
                    drawEmptyRow(g, width, tableTop + TABLE_HEADER_HEIGHT, rowHeight);
                } else {
                    for (int index = start; index < end; index++) {
                        drawProductRow(g, width, tableTop + TABLE_HEADER_HEIGHT + (index - start) * rowHeight,
                                rowHeight, index, rows.get(index), qrCodes.get(index));
                    }
                }
                drawTableGrid(g, width, tableTop, visibleRows, rowHeight);
                return PAGE_EXISTS;
            } finally {
                g.dispose();
            }
        }

        private void drawHeader(Graphics2D g, double width, int pageIndex, int pageCount) {
            g.setColor(Color.BLACK);
            g.setFont(HEADER);
            g.drawString("СКЛАД · " + safe(address.addressId()) + " · " + safe(address.name()), 0, 17);
            g.setFont(BOX_CODE);
            g.drawString(box.boxCode(), 0, 61);
            g.setFont(HEADER);
            String page = pageCount > 1 ? "ЛИСТ " + (pageIndex + 1) + " / " + pageCount : "";
            String count = "ПОЗИЦИЙ: " + rows.size();
            int infoX = (int) Math.max(width * 0.58, g.getFontMetrics().stringWidth(box.boxCode()) + 24);
            g.drawString(count, infoX, 37);
            if (!page.isEmpty()) g.drawString(page, infoX, 59);
            g.setStroke(DASHED_STROKE);
            g.drawLine(0, (int) (HEADER_HEIGHT - 3), (int) width, (int) (HEADER_HEIGHT - 3));
        }

        private static void drawTableHeader(Graphics2D g, double width, double y) {
            double x = 0;
            g.setFont(HEADER.deriveFont(12f));
            g.setColor(Color.BLACK);
            for (int col = 0; col < COLUMNS.length; col++) {
                double cellWidth = width * WIDTHS[col];
                drawCentered(g, COLUMNS[col], x, y, cellWidth, TABLE_HEADER_HEIGHT);
                x += cellWidth;
            }
        }

        private static void drawProductRow(Graphics2D g, double width, double y, double rowHeight, int index,
                                           LocationResult item, BufferedImage qrCode) {
            double x = 0;
            String[] values = {Integer.toString(index + 1), item.name(), item.vendorCode(), "", Integer.toString(item.quantity())};
            float fontSize = (float) Math.max(13, Math.min(27, rowHeight * 0.18));
            g.setFont(BODY.deriveFont(fontSize));
            g.setColor(Color.BLACK);
            for (int col = 0; col < WIDTHS.length; col++) {
                double cellWidth = width * WIDTHS[col];
                if (col == 3) {
                    drawQrCode(g, item.barcode(), qrCode, x, y, cellWidth, rowHeight);
                } else if (col == 1) {
                    drawWrapped(g, values[col], x + 4, y + 3, cellWidth - 8, rowHeight - 6);
                } else if (col == 2) {
                    g.setFont(ARTICLE.deriveFont((float) Math.max(17, Math.min(29, rowHeight * 0.38))));
                    drawCentered(g, values[col], x + 2, y, cellWidth - 4, rowHeight);
                    g.setFont(BODY.deriveFont(fontSize));
                } else {
                    drawCentered(g, values[col], x, y, cellWidth, rowHeight);
                }
                x += cellWidth;
            }
        }

        private static void drawQrCode(Graphics2D g, String value, BufferedImage qrCode,
                                       double x, double y, double width, double rowHeight) {
            if (value == null || value.isBlank() || qrCode == null) {
                drawCentered(g, value == null || value.isBlank() ? "—" : value,
                        x + 3, y, width - 6, rowHeight);
                return;
            }
            int imageSize = (int) Math.min(Math.min(width - 8, rowHeight - 6), 50);
            int imageX = (int) (x + (width - imageSize) / 2);
            int imageY = (int) (y + (rowHeight - imageSize) / 2);
            g.drawImage(qrCode, imageX, imageY, imageSize, imageSize, null);
        }

        private static void drawTableGrid(Graphics2D g, double width, double tableTop, int rows, double rowHeight) {
            g.setColor(Color.BLACK);
            g.setStroke(DASHED_STROKE);
            double tableBottom = tableTop + TABLE_HEADER_HEIGHT + rows * rowHeight;
            double[] horizontal = new double[rows + 2];
            horizontal[0] = tableTop;
            horizontal[1] = tableTop + TABLE_HEADER_HEIGHT;
            for (int row = 0; row < rows; row++) {
                horizontal[row + 2] = tableTop + TABLE_HEADER_HEIGHT + (row + 1) * rowHeight;
            }
            for (double lineY : horizontal) g.draw(new java.awt.geom.Line2D.Double(0, lineY, width, lineY));
            double x = 0;
            g.draw(new java.awt.geom.Line2D.Double(0, tableTop, 0, tableBottom));
            for (double columnWidth : WIDTHS) {
                x += width * columnWidth;
                g.draw(new java.awt.geom.Line2D.Double(x, tableTop, x, tableBottom));
            }
        }

        private static void drawEmptyRow(Graphics2D g, double width, double y, double rowHeight) {
            g.setFont(HEADER);
            g.setColor(Color.BLACK);
            drawCentered(g, "В ЭТОЙ КОРОБКЕ ПОКА НЕТ ТОВАРОВ", 0, y, width, rowHeight);
        }

        private static void drawWrapped(Graphics2D g, String text, double x, double y, double width, double height) {
            String[] words = safe(text).split("\\s+");
            var metrics = g.getFontMetrics();
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && metrics.stringWidth(candidate) > width) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else line = new StringBuilder(candidate);
            }
            if (!line.isEmpty()) lines.add(line.toString());
            int maxLines = Math.max(1, (int) (height / metrics.getHeight()));
            if (lines.size() > maxLines) {
                lines = new ArrayList<>(lines.subList(0, maxLines));
                String last = lines.get(maxLines - 1);
                while (!last.isEmpty() && metrics.stringWidth(last + "…") > width) last = last.substring(0, last.length() - 1);
                lines.set(maxLines - 1, last + "…");
            }
            int totalHeight = lines.size() * metrics.getHeight();
            int baseline = (int) (y + (height - totalHeight) / 2 + metrics.getAscent());
            for (String entry : lines) {
                g.drawString(entry, (int) x, baseline);
                baseline += metrics.getHeight();
            }
        }

        private static void drawCentered(Graphics2D g, String text, double x, double y, double width, double height) {
            String value = safe(text);
            var metrics = g.getFontMetrics();
            int drawX = (int) (x + Math.max(3, (width - metrics.stringWidth(value)) / 2));
            int baseline = (int) (y + (height - metrics.getHeight()) / 2 + metrics.getAscent());
            g.drawString(value, drawX, baseline);
        }

        private static BufferedImage createQrCode(String value) {
            if (value == null || value.isBlank()) return null;
            try {
                return MatrixToImageWriter.toBufferedImage(new QRCodeWriter().encode(value.trim(),
                        BarcodeFormat.QR_CODE, 160, 160, Map.of(EncodeHintType.MARGIN, 2)));
            } catch (WriterException | IllegalArgumentException ignored) {
                return null;
            }
        }

        private static String safe(String value) { return value == null ? "" : value; }
    }

    private void updateSelectedBoxCount(StorageBox original, int count) {
        updatingSelectors = true;
        for (int i = 0; i < boxes.getItemCount(); i++) {
            StorageBox current = boxes.getItemAt(i);
            if (current.boxId() == original.boxId()) {
                StorageBox updated = new StorageBox(current.boxId(), current.addressId(), current.boxCode(),
                        current.createdAt(), count);
                boxes.removeItemAt(i);
                boxes.insertItemAt(updated, i);
                boxes.setSelectedIndex(i);
                break;
            }
        }
        updatingSelectors = false;
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
        JOptionPane.showMessageDialog(this, message, "Адресное хранение", JOptionPane.ERROR_MESSAGE);
    }

    private void setStatus(String message) { status.setText(message); }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }

    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
}
