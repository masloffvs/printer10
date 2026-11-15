package com.setretailx.printer.ui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.setretailx.printer.repository.AddressStorageRepository;
import com.setretailx.printer.repository.AddressStorageRepository.StorageAddress;
import com.setretailx.printer.repository.AddressStorageRepository.StorageBox;
import com.setretailx.printer.repository.AddressStorageRepository.StoredProduct;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterJob;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Address and box hierarchy with context actions for storage management and printing. */
@SuppressWarnings("serial")
final class AddressManagementPanel extends JPanel {
    private final AddressStorageRepository repository;
    private final Runnable openCreateAddress;
    private final Runnable addressesChanged;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Адреса хранения");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    private final JLabel status = new JLabel("Адреса и коробки хранения.");

    AddressManagementPanel(AddressStorageRepository repository, Runnable openCreateAddress, Runnable addressesChanged) {
        super(new BorderLayout(8, 8));
        this.repository = repository;
        this.openCreateAddress = openCreateAddress == null ? () -> { } : openCreateAddress;
        this.addressesChanged = addressesChanged == null ? () -> { } : addressesChanged;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.TRAILING, 6, 0));
        JButton create = new JButton("Создать адрес");
        create.addActionListener(event -> this.openCreateAddress.run());
        controls.add(create);
        JButton refresh = new JButton("Обновить дерево");
        refresh.addActionListener(event -> refresh(null));
        controls.add(refresh);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { maybeShowMenu(event); }
            @Override public void mouseReleased(MouseEvent event) { maybeShowMenu(event); }
        });
        add(new JScrollPane(tree), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 4));
        footer.add(status, BorderLayout.CENTER);
        footer.add(controls, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        refresh(null);
    }

    private void maybeShowMenu(MouseEvent event) {
        if (!event.isPopupTrigger()) return;
        TreePath path = tree.getPathForLocation(event.getX(), event.getY());
        if (path == null) return;
        tree.setSelectionPath(path);
        Object nodeValue = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        JPopupMenu menu = new JPopupMenu();
        if (nodeValue instanceof AddressNode addressNode) {
            StorageAddress address = addressNode.address();
            JMenuItem rename = new JMenuItem("Переименовать адрес");
            rename.addActionListener(ignored -> renameAddress(address));
            menu.add(rename);
            JMenuItem delete = new JMenuItem("Удалить адрес и все коробки");
            delete.addActionListener(ignored -> deleteAddress(address));
            menu.add(delete);
            JMenuItem createBox = new JMenuItem("Создать коробку в этом адресе");
            createBox.addActionListener(ignored -> run("Создаём коробку…",
                    () -> repository.createBox(address.addressId()), box -> refresh(address.addressId()), this::showError));
            menu.add(createBox);
            menu.addSeparator();
            JMenuItem printList = new JMenuItem("Печать списка товаров адреса");
            printList.addActionListener(ignored -> printAddressProducts(address));
            menu.add(printList);
        } else if (nodeValue instanceof BoxNode boxNode) {
            StorageAddress address = boxNode.address();
            StorageBox box = boxNode.box();
            JMenuItem delete = new JMenuItem("Удалить коробку и её содержимое");
            delete.addActionListener(ignored -> deleteBox(address, box));
            menu.add(delete);
            JMenuItem move = new JMenuItem("Переместить в другой адрес…");
            move.addActionListener(ignored -> moveBox(address, box));
            menu.add(move);
            menu.addSeparator();
            JMenuItem label24 = new JMenuItem("Печать наклейки · 2×4");
            label24.addActionListener(ignored -> printBoxLabel(address, box, LabelLayout.TWO_BY_FOUR));
            menu.add(label24);
            JMenuItem label26 = new JMenuItem("Печать пустых наклеек · 2×6");
            label26.addActionListener(ignored -> printBoxLabel(address, box, LabelLayout.TWO_BY_SIX));
            menu.add(label26);
            JMenuItem productList = new JMenuItem("Печать списка товаров коробки");
            productList.addActionListener(ignored -> printBoxProducts(address, box));
            menu.add(productList);
        }
        if (menu.getComponentCount() > 0) menu.show(tree, event.getX(), event.getY());
    }

    private void renameAddress(StorageAddress address) {
        String name = JOptionPane.showInputDialog(this, "Новое название адреса:", address.name());
        if (name == null) return;
        run("Переименовываем адрес…", () -> { repository.renameAddress(address.addressId(), name); return address.addressId(); },
                this::refresh, this::showError);
    }

    private void deleteAddress(StorageAddress address) {
        int answer = JOptionPane.showConfirmDialog(this,
                "Удалить «" + address + "» вместе со всеми коробками и товарами? Это действие нельзя отменить.",
                "Удалить адрес", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        run("Удаляем адрес…", () -> { repository.deleteAddress(address.addressId()); return null; },
                ignored -> refresh(null), this::showError);
    }

    private void deleteBox(StorageAddress address, StorageBox box) {
        int answer = JOptionPane.showConfirmDialog(this,
                "Удалить коробку «" + box.boxCode() + "» и все её товары?",
                "Удалить коробку", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        run("Удаляем коробку…", () -> { repository.deleteBox(box.boxId()); return address.addressId(); },
                this::refresh, this::showError);
    }

    private void moveBox(StorageAddress source, StorageBox box) {
        run("Загружаем адреса…", repository::addresses, addresses -> {
            List<StorageAddress> targets = addresses.stream()
                    .filter(address -> !address.addressId().equals(source.addressId())).toList();
            if (targets.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Нет другого адреса для перемещения. Сначала создайте адрес.",
                        "Перемещение коробки", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JComboBox<StorageAddress> selector = new JComboBox<>(targets.toArray(StorageAddress[]::new));
            if (JOptionPane.showConfirmDialog(this, selector, "Переместить коробку " + box.boxCode() + " в адрес",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            StorageAddress target = (StorageAddress) selector.getSelectedItem();
            if (target == null) return;
            run("Перемещаем коробку…", () -> {
                repository.moveBox(box.boxId(), target.addressId()); return target.addressId();
            }, this::refresh, this::showError);
        }, this::showError);
    }

    void refreshAddresses() { refresh(null); }

    private void refresh(String selectAddressId) {
        run("Загружаем дерево адресов…", () -> {
            List<AddressTree> result = new ArrayList<>();
            for (StorageAddress address : repository.addresses())
                result.add(new AddressTree(address, repository.boxes(address.addressId())));
            return result;
        }, rows -> {
            root.removeAllChildren();
            for (AddressTree row : rows) {
                DefaultMutableTreeNode addressNode = new DefaultMutableTreeNode(new AddressNode(row.address(), row.boxes().size()));
                root.add(addressNode);
                for (StorageBox box : row.boxes()) addressNode.add(new DefaultMutableTreeNode(new BoxNode(row.address(), box)));
            }
            treeModel.reload();
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(i);
                tree.expandPath(new TreePath(new Object[]{root, node}));
                if (selectAddressId != null && ((AddressNode) node.getUserObject()).address().addressId().equals(selectAddressId))
                    tree.setSelectionPath(new TreePath(new Object[]{root, node}));
            }
            addressesChanged.run();
            status.setText(rows.isEmpty() ? "Адресов пока нет — создайте адрес хранения." : "Адресов: " + rows.size());
        }, this::showError);
    }

    private void printBoxLabel(StorageAddress address, StorageBox box, LabelLayout layout) {
        print(new PrintJobData("Наклейки · " + box.boxCode(), new BoxLabelsPrintable(address, box, layout), layout));
    }

    private void printBoxProducts(StorageAddress address, StorageBox box) {
        run("Готовим список товаров…", () -> repository.productsInBox(box.boxId()), rows ->
                print(new PrintJobData("Список товаров · " + box.boxCode(), new ProductListPrintable(address,
                        box.boxCode(), rows.stream().map(product -> new ProductRow(product.name(), product.article(),
                        product.barcode(), product.quantity())).toList()), null)), this::showError);
    }

    private void printAddressProducts(StorageAddress address) {
        run("Собираем список товаров адреса…", () -> {
            List<ProductRow> products = new ArrayList<>();
            for (StorageBox box : repository.boxes(address.addressId())) {
                for (StoredProduct product : repository.productsInBox(box.boxId()))
                    products.add(new ProductRow(box.boxCode() + " · " + product.name(), product.article(),
                            product.barcode(), product.quantity()));
            }
            return products;
        }, rows -> print(new PrintJobData("Список товаров · " + address.addressId(),
                new ProductListPrintable(address, "Все коробки", rows), null)), this::showError);
    }

    private void print(PrintJobData data) {
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName(data.title());
        PageFormat format = job.defaultPage();
        Paper paper = new Paper();
        double width = 210 / 25.4 * 72, height = 297 / 25.4 * 72, margin = 5 / 25.4 * 72;
        paper.setSize(width, height);
        paper.setImageableArea(margin, margin, width - margin * 2, height - margin * 2);
        format.setPaper(paper);
        format.setOrientation(PageFormat.PORTRAIT);
        job.setPrintable(data.printable(), format);
        if (!job.printDialog()) return;
        try {
            job.print();
            status.setText("Отправлено на печать: " + data.title()
                    + (data.layout() == null ? "" : " · " + data.layout().description));
        } catch (Exception error) { showError(error.getMessage() == null ? error.toString() : error.getMessage()); }
    }

    private <T> void run(String message, Work<T> work, Consumer<T> success, Consumer<String> failure) {
        status.setText(message);
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
        status.setText("Ошибка: " + message);
        JOptionPane.showMessageDialog(this, message, "Адресное хранение", JOptionPane.ERROR_MESSAGE);
    }

    private record AddressTree(StorageAddress address, List<StorageBox> boxes) { }
    private record AddressNode(StorageAddress address, int boxCount) {
        @Override public String toString() { return address.addressId() + " — " + address.name() + " · коробок: " + boxCount; }
    }
    private record BoxNode(StorageAddress address, StorageBox box) {
        @Override public String toString() { return box.boxCode() + " · товаров: " + box.productCount(); }
    }
    private record ProductRow(String name, String article, String barcode, int quantity) { }
    private record PrintJobData(String title, Printable printable, LabelLayout layout) { }
    private enum LabelLayout {
        TWO_BY_FOUR(2, 4, "2×4"), TWO_BY_SIX(2, 6, "2×6 пустой лист");
        private final int columns, rows;
        private final String description;
        LabelLayout(int columns, int rows, String description) { this.columns = columns; this.rows = rows; this.description = description; }
    }
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }

    private static final class BoxLabelsPrintable implements Printable {
        private final StorageAddress address;
        private final StorageBox box;
        private final LabelLayout layout;
        private final BufferedImage qr;
        private BoxLabelsPrintable(StorageAddress address, StorageBox box, LabelLayout layout) {
            this.address = address; this.box = box; this.layout = layout;
            this.qr = createQr(box.boxCode());
        }
        @Override public int print(java.awt.Graphics graphics, PageFormat page, int pageIndex) {
            if (pageIndex > 0) return NO_SUCH_PAGE;
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                g.translate(page.getImageableX(), page.getImageableY());
                g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 18));
                double gap = 7 / 25.4 * 72;
                double w = (page.getImageableWidth() - gap * (layout.columns - 1)) / layout.columns;
                double h = (page.getImageableHeight() - gap * (layout.rows - 1)) / layout.rows;
                for (int r = 0; r < layout.rows; r++) for (int c = 0; c < layout.columns; c++) {
                    int x = (int) (c * (w + gap)), y = (int) (r * (h + gap));
                    if (layout == LabelLayout.TWO_BY_FOUR) {
                        g.drawString(box.boxCode(), x + 12, y + 32);
                        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 11));
                        g.drawString(address.addressId() + " — " + address.name(), x + 12, y + 52);
                        g.drawString("Товаров: " + box.productCount(), x + 12, y + 69);
                        if (qr != null) g.drawImage(qr, (int) (x + w - 72), y + 8, 58, 58, null);
                        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 18));
                    }
                }
                return PAGE_EXISTS;
            } finally { g.dispose(); }
        }

        private static BufferedImage createQr(String value) {
            try {
                return MatrixToImageWriter.toBufferedImage(new QRCodeWriter().encode(value,
                        BarcodeFormat.QR_CODE, 160, 160, Map.of(EncodeHintType.MARGIN, 1)));
            } catch (WriterException | IllegalArgumentException ignored) { return null; }
        }
    }

    private static final class ProductListPrintable implements Printable {
        private final StorageAddress address;
        private final String title;
        private final List<ProductRow> rows;
        private ProductListPrintable(StorageAddress address, String title, List<ProductRow> rows) {
            this.address = address; this.title = title; this.rows = List.copyOf(rows);
        }
        @Override public int print(java.awt.Graphics graphics, PageFormat page, int pageIndex) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                int perPage = 36, from = pageIndex * perPage;
                if (from >= Math.max(1, rows.size())) return NO_SUCH_PAGE;
                g.translate(page.getImageableX(), page.getImageableY());
                g.setColor(java.awt.Color.BLACK);
                g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 14));
                g.drawString("Адрес: " + address.addressId() + " — " + address.name() + " · " + title, 0, 18);
                g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 10));
                g.drawString("ТОВАР / КОРОБКА", 0, 40); g.drawString("АРТИКУЛ", 300, 40);
                g.drawString("ШТРИХКОД", 405, 40); g.drawString("КОЛ-ВО", 520, 40);
                g.drawLine(0, 44, (int) page.getImageableWidth(), 44);
                g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 9));
                int end = Math.min(rows.size(), from + perPage);
                for (int i = from; i < end; i++) {
                    ProductRow row = rows.get(i); int y = 59 + (i - from) * 19;
                    g.drawString(clip(row.name(), 48), 0, y);
                    g.drawString(clip(row.article(), 16), 300, y);
                    g.drawString(clip(row.barcode(), 18), 405, y);
                    g.drawString(Integer.toString(row.quantity()), 520, y);
                    g.drawLine(0, y + 4, (int) page.getImageableWidth(), y + 4);
                }
                if (rows.isEmpty()) g.drawString("В этом адресе пока нет товаров.", 0, 64);
                return PAGE_EXISTS;
            } finally { g.dispose(); }
        }
        private static String clip(String text, int limit) {
            if (text == null) return "";
            String clean = text.replaceAll("[\\r\\n\\t]", " ");
            return clean.length() <= limit ? clean : clean.substring(0, limit - 1) + "…";
        }
    }
}
