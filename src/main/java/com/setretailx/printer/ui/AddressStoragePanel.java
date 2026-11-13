package com.setretailx.printer.ui;

import com.setretailx.printer.model.Product;
import com.setretailx.printer.repository.AddressStorageRepository;
import com.setretailx.printer.repository.AddressStorageRepository.LocationResult;
import com.setretailx.printer.repository.AddressStorageRepository.StorageAddress;
import com.setretailx.printer.repository.AddressStorageRepository.StorageBox;
import com.setretailx.printer.repository.AddressStorageRepository.StoredProduct;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
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
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.print.Printable;
import java.awt.print.PrinterJob;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.List;
import java.util.function.Consumer;

/** MDI screen for virtual storage addresses, boxes, and item lookup. */
@SuppressWarnings("serial")
final class AddressStoragePanel extends JPanel {
    private final AddressStorageRepository repository;
    private final JComboBox<StorageAddress> addresses = new JComboBox<>();
    private final JComboBox<StorageBox> boxes = new JComboBox<>();
    private final DefaultTableModel productsModel = model("Наименование", "Артикул", "Штрихкод", "Кол-во");
    private final JTable productsTable = new JTable(productsModel);
    private final DefaultTableModel locationsModel = model("Адрес", "Коробка", "Товар", "Артикул", "Штрихкод", "Кол-во");
    private final JTable locationsTable = new JTable(locationsModel);
    private final JTextField locationQuery = new JTextField(24);
    private final JLabel status = new JLabel("Создайте или выберите адрес, затем откройте коробку.");
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
        locationQuery.addActionListener(event -> findLocations());
        refreshAddresses(null);
    }

    private JComponent buildStorageControls() {
        JPanel panel = new JPanel(new BorderLayout(8, 6));
        JPanel addressRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        addressRow.add(new JLabel("Виртуальный адрес:"));
        addresses.setPrototypeDisplayValue(new StorageAddress("Склад-01", "Основной склад"));
        addressRow.add(addresses);
        JButton addAddress = new JButton("Создать адрес");
        addAddress.addActionListener(event -> createAddress());
        addressRow.add(addAddress);
        panel.add(addressRow, BorderLayout.NORTH);

        JPanel boxRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        boxRow.add(new JLabel("Коробка:"));
        boxes.setPrototypeDisplayValue(new StorageBox(0, "", "абв123", 0, 0));
        boxRow.add(boxes);
        JButton addBox = new JButton("Создать и открыть коробку");
        addBox.addActionListener(event -> createBox());
        boxRow.add(addBox);
        JButton printLabel = new JButton("Печать наклейки");
        printLabel.addActionListener(event -> printLabel());
        boxRow.add(printLabel);
        panel.add(boxRow, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildContent() {
        JPanel content = new JPanel(new GridLayout(1, 2, 10, 0));
        JPanel boxContents = new JPanel(new BorderLayout(4, 4));
        boxContents.setBorder(BorderFactory.createTitledBorder("Товары в выбранной коробке"));
        boxContents.add(new JScrollPane(productsTable), BorderLayout.CENTER);
        content.add(boxContents);

        JPanel lookup = new JPanel(new BorderLayout(4, 4));
        lookup.setBorder(BorderFactory.createTitledBorder("Поиск места по артикулу или штрихкоду"));
        JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        searchRow.add(locationQuery);
        JButton searchButton = new JButton("Найти");
        searchButton.addActionListener(event -> findLocations());
        searchRow.add(searchButton);
        lookup.add(searchRow, BorderLayout.NORTH);
        lookup.add(new JScrollPane(locationsTable), BorderLayout.CENTER);
        content.add(lookup);
        return content;
    }

    private void createAddress() {
        JTextField id = new JTextField(18);
        JTextField name = new JTextField(18);
        JPanel fields = new JPanel(new GridLayout(0, 1, 4, 4));
        fields.add(new JLabel("ID хранилища (уникальный):"));
        fields.add(id);
        fields.add(new JLabel("Название (необязательно):"));
        fields.add(name);
        if (JOptionPane.showConfirmDialog(this, fields, "Создать виртуальный адрес",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        run("Создаём адрес…", () -> {
            repository.createAddress(id.getText(), name.getText());
            return id.getText().trim();
        }, createdId -> refreshAddresses(createdId), this::showError);
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

    private void findLocations() {
        String query = locationQuery.getText().trim();
        if (query.isEmpty()) {
            setStatus("Введите артикул или штрихкод для поиска.");
            return;
        }
        run("Ищем места хранения…", () -> repository.findLocations(query), rows -> {
            locationsModel.setRowCount(0);
            for (LocationResult location : rows) locationsModel.addRow(new Object[]{
                    location.addressId() + " — " + location.addressName(), location.boxCode(), location.name(),
                    location.vendorCode(), location.barcode(), location.quantity()});
            setStatus(rows.isEmpty() ? "Товар не найден в адресном хранении." : "Найдено размещений: " + rows.size());
        }, this::showError);
    }

    boolean hasOpenBox() { return boxes.getSelectedItem() != null; }

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
            StringBuilder label = new StringBuilder();
            label.append("АДРЕСНОЕ ХРАНЕНИЕ\n\n")
                    .append("Адрес: ").append(address.addressId()).append(" — ").append(address.name()).append('\n')
                    .append("Коробка: ").append(box.boxCode()).append("\n\nСОДЕРЖИМОЕ:\n");
            if (rows.isEmpty()) label.append("(коробка пока пустая)\n");
            for (LocationResult item : rows) {
                label.append("• ").append(item.name()).append(" × ").append(item.quantity());
                if (!item.vendorCode().isBlank()) label.append(" · арт. ").append(item.vendorCode());
                if (!item.barcode().isBlank()) label.append(" · штрихкод ").append(item.barcode());
                label.append('\n');
            }
            try {
                String qrPayload = "SETRETAILX-STORAGE\naddress=" + address.addressId() + "\nbox=" + box.boxCode();
                BufferedImage qr = MatrixToImageWriter.toBufferedImage(new QRCodeWriter().encode(qrPayload,
                        BarcodeFormat.QR_CODE, 180, 180, Map.of(EncodeHintType.CHARACTER_SET, "UTF-8")));
                submitPrint(label.toString(), qr);
            } catch (Exception error) {
                showError("Не удалось сформировать QR-код: " + error.getMessage());
            }
        }, this::showError);
    }

    private void submitPrint(String text, BufferedImage qr) {
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("Наклейка адресного хранения");
        job.setPrintable((graphics, pageFormat, pageIndex) -> {
            String[] lines = text.split("\\n", -1);
            int lineHeight = 16;
            int firstPageLines = Math.max(1, (int) ((pageFormat.getImageableHeight() - 190) / lineHeight));
            int otherPageLines = Math.max(1, (int) (pageFormat.getImageableHeight() / lineHeight));
            int start = 0;
            int page = 0;
            while (page < pageIndex) {
                start += page == 0 ? firstPageLines : otherPageLines;
                page++;
            }
            int linesPerPage = pageIndex == 0 ? firstPageLines : otherPageLines;
            if (start >= lines.length) return Printable.NO_SUCH_PAGE;
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) graphics;
            g2.translate(pageFormat.getImageableX(), pageFormat.getImageableY());
            graphics.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 10));
            int y = lineHeight;
            for (int i = start; i < Math.min(lines.length, start + linesPerPage); i++, y += lineHeight) {
                graphics.drawString(lines[i], 0, y);
            }
            if (pageIndex == 0) g2.drawImage(qr, Math.max(0, (int) pageFormat.getImageableWidth() - 190),
                    (int) pageFormat.getImageableHeight() - 190, 180, 180, null);
            return Printable.PAGE_EXISTS;
        });
        if (!job.printDialog()) return;
        try {
            job.print();
            setStatus("Наклейка отправлена на печать.");
        } catch (Exception error) {
            showError(error.getMessage() == null ? error.toString() : error.getMessage());
        }
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
