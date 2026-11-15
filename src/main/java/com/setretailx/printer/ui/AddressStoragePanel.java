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
import java.awt.print.Paper;
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
        JPanel footer = new JPanel(new BorderLayout(8, 4));
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.TRAILING, 6, 0));
        JButton addBox = new JButton("Создать коробку");
        addBox.addActionListener(event -> createBox());
        JButton deleteBox = new JButton("Удалить коробку");
        deleteBox.addActionListener(event -> deleteBox());
        JButton printLabel = new JButton("Печать наклейки");
        printLabel.addActionListener(event -> printLabel());
        actions.add(addBox);
        actions.add(deleteBox);
        actions.add(printLabel);
        footer.add(status, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

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
            for (StoredProduct product : rows) productsModel.addRow(new Object[]{product.name(), product.article(),
                    product.barcode(), product.quantity()});
            int itemCount = rows.stream().mapToInt(StoredProduct::quantity).sum();
            updateSelectedBoxCount(box, itemCount);
            setStatus("Коробка " + box.boxCode() + " открыта · товаров: " + itemCount
                    + " · сканируйте товары в едином сканере.");
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
        Paper paper = new Paper();
        double paperWidth = 210 / 25.4 * 72, paperHeight = 297 / 25.4 * 72, margin = 14 / 25.4 * 72;
        paper.setSize(paperWidth, paperHeight);
        paper.setImageableArea(margin, margin, paperWidth - margin * 2, paperHeight - margin * 2);
        pageFormat.setPaper(paper);
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
        private static final double OUTER_MARGIN = 0;
        private static final double HEADER_HEIGHT = 52;
        private static final double TABLE_HEADER_HEIGHT = 19;
        private static final double FOOTER_HEIGHT = 15;
        private static final double MIN_ROW_HEIGHT = 31;
        private static final Font BODY = new Font("DejaVu Sans", Font.PLAIN, 8);
        private static final Font HEADER = new Font("DejaVu Sans", Font.BOLD, 8);
        private static final Font TITLE = new Font("DejaVu Sans", Font.BOLD, 15);
        private static final Font BOX_CODE = new Font("DejaVu Sans Mono", Font.BOLD, 18);
        private static final Font ARTICLE = new Font("DejaVu Sans Mono", Font.BOLD, 11);
        private static final Font FOOTER = new Font("DejaVu Sans", Font.PLAIN, 6);
        private static final String[] COLUMNS = {"№", "НАИМЕНОВАНИЕ ТОВАРА", "АРТИКУЛ", "QR-КОД", "КОЛ-ВО"};
        private static final double[] WIDTHS = {0.05, 0.35, 0.27, 0.18, 0.15};

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
            double tableBodyHeight = height - HEADER_HEIGHT - TABLE_HEADER_HEIGHT - FOOTER_HEIGHT;
            int rowsPerPage = Math.max(1, (int) Math.floor(tableBodyHeight / MIN_ROW_HEIGHT));
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
                double rowHeight = rows.isEmpty() ? MIN_ROW_HEIGHT
                        : Math.min(tableBodyHeight / visibleRows, MIN_ROW_HEIGHT * 1.4);
                if (rows.isEmpty()) {
                    drawEmptyRow(g, width, tableTop + TABLE_HEADER_HEIGHT, rowHeight);
                } else {
                    for (int index = start; index < end; index++) {
                        drawProductRow(g, width, tableTop + TABLE_HEADER_HEIGHT + (index - start) * rowHeight,
                                rowHeight, index, rows.get(index), qrCodes.get(index));
                    }
                }
                drawTableGrid(g, width, tableTop, visibleRows, rowHeight);
                drawFooter(g, width, height, pageIndex, pageCount);
                return PAGE_EXISTS;
            } finally {
                g.dispose();
            }
        }

        private void drawHeader(Graphics2D g, double width, int pageIndex, int pageCount) {
            g.setColor(Color.BLACK);
            g.setFont(TITLE);
            g.drawString("СПИСОК ТОВАРОВ В КОРОБКЕ", 0, 15);
            g.setFont(BODY);
            g.setColor(new Color(51, 51, 51));
            g.drawString("АДРЕС: " + safe(address.addressId()) + " — " + safe(address.name()), 0, 29);
            g.setFont(BOX_CODE);
            g.setColor(Color.BLACK);
            g.drawString(box.boxCode(), 0, 48);
            g.setFont(HEADER);
            String info = "ПОЗИЦИЙ: " + rows.size() + (pageCount > 1 ? " · ЛИСТ " + (pageIndex + 1) + "/" + pageCount : "");
            int infoX = (int) Math.max(width * 0.58, g.getFontMetrics().stringWidth(box.boxCode()) + 24);
            g.drawString(info, infoX, 44);
            g.setStroke(new BasicStroke(1.5f));
            g.drawLine(0, (int) (HEADER_HEIGHT - 1), (int) width, (int) (HEADER_HEIGHT - 1));
        }

        private static void drawTableHeader(Graphics2D g, double width, double y) {
            g.setColor(new Color(217, 217, 217));
            g.fillRect(0, (int) y, (int) width, (int) TABLE_HEADER_HEIGHT);
            double x = 0;
            g.setFont(HEADER);
            g.setColor(Color.BLACK);
            for (int col = 0; col < COLUMNS.length; col++) {
                double cellWidth = width * WIDTHS[col];
                if (col == 4) drawRight(g, COLUMNS[col], x + cellWidth - 3, (int) (y + 13));
                else g.drawString(COLUMNS[col], (int) x + 3, (int) (y + 13));
                x += cellWidth;
            }
        }

        private static void drawProductRow(Graphics2D g, double width, double y, double rowHeight, int index,
                                           LocationResult item, BufferedImage qrCode) {
            double x = 0;
            String[] values = {Integer.toString(index + 1), item.name(), item.vendorCode(), "", Integer.toString(item.quantity())};
            float fontSize = (float) Math.max(7, Math.min(8, rowHeight * 0.24));
            g.setFont(BODY.deriveFont(fontSize));
            g.setColor(Color.BLACK);
            for (int col = 0; col < WIDTHS.length; col++) {
                double cellWidth = width * WIDTHS[col];
                if (col == 3) {
                    drawQrCode(g, item.barcode(), qrCode, x, y, cellWidth, rowHeight);
                } else if (col == 1) {
                    drawWrapped(g, values[col], x + 4, y + 3, cellWidth - 8, rowHeight - 6);
                } else if (col == 2) {
                    g.setFont(ARTICLE.deriveFont((float) Math.max(8, Math.min(12, rowHeight * 0.34))));
                    drawCentered(g, values[col], x + 2, y, cellWidth - 4, rowHeight);
                    g.setFont(BODY.deriveFont(fontSize));
                } else if (col == 4) {
                    g.setFont(BODY.deriveFont(Font.BOLD, 8f));
                    drawRight(g, values[col], x + cellWidth - 4, (int) (y + rowHeight / 2 + g.getFontMetrics().getAscent() / 2.5));
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
            int imageSize = (int) Math.min(Math.min(width - 8, rowHeight - 4), 34);
            int imageX = (int) (x + (width - imageSize) / 2);
            int imageY = (int) (y + (rowHeight - imageSize) / 2);
            g.drawImage(qrCode, imageX, imageY, imageSize, imageSize, null);
        }

        private static void drawTableGrid(Graphics2D g, double width, double tableTop, int rows, double rowHeight) {
            g.setColor(new Color(187, 187, 187));
            g.setStroke(new BasicStroke(.5f));
            double tableBottom = tableTop + TABLE_HEADER_HEIGHT + rows * rowHeight;
            double[] horizontal = new double[rows + 2];
            horizontal[0] = tableTop;
            horizontal[1] = tableTop + TABLE_HEADER_HEIGHT;
            for (int row = 0; row < rows; row++) {
                horizontal[row + 2] = tableTop + TABLE_HEADER_HEIGHT + (row + 1) * rowHeight;
            }
            for (double lineY : horizontal) g.draw(new java.awt.geom.Line2D.Double(0, lineY, width, lineY));
            double x = 0;
            for (double columnWidth : WIDTHS) {
                x += width * columnWidth;
                g.setColor(new Color(217, 217, 217));
                g.draw(new java.awt.geom.Line2D.Double(x, tableTop, x, tableBottom));
            }
        }

        private void drawFooter(Graphics2D g, double width, double height, int pageIndex, int pageCount) {
            double y = height - FOOTER_HEIGHT;
            g.setColor(new Color(187, 187, 187));
            g.setStroke(new BasicStroke(.5f));
            g.drawLine(0, (int) y, (int) width, (int) y);
            g.setFont(FOOTER);
            g.setColor(new Color(51, 51, 51));
            g.drawString("Адресное хранение · QR: штрихкод товара · Кол-во: единиц в коробке", 0, (int) (y + 10));
            drawRight(g, "Лист " + (pageIndex + 1) + " / " + pageCount, width, (int) (y + 10));
        }

        private static void drawRight(Graphics2D g, String text, double right, int baseline) {
            g.drawString(text, (int) (right - g.getFontMetrics().stringWidth(text)), baseline);
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
