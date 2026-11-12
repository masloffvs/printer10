package com.setretailx.printer.ui;

import com.setretailx.printer.model.Product;
import com.setretailx.printer.service.ProductImageService;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Readable product details dialog with asynchronously loaded catalog photography. */
@SuppressWarnings("serial")
final class ProductCardDialog extends JDialog {
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("brand", "Бренд"), Map.entry("brandname", "Бренд"),
            Map.entry("manufacturer", "Производитель"), Map.entry("manufacturername", "Производитель"),
            Map.entry("model", "Модель"), Map.entry("color", "Цвет"), Map.entry("colorname", "Цвет"),
            Map.entry("size", "Размер"), Map.entry("sizename", "Размер"),
            Map.entry("material", "Материал"), Map.entry("composition", "Состав"),
            Map.entry("country", "Страна производства"), Map.entry("countryoforigin", "Страна производства"),
            Map.entry("season", "Сезон"), Map.entry("weight", "Вес"), Map.entry("volume", "Объём"),
            Map.entry("cost", "Себестоимость"), Map.entry("warranty", "Гарантия"),
            Map.entry("series", "Серия"), Map.entry("collection", "Коллекция"));
    private static final List<String> HIDDEN_FIELDS = List.of(
            "productid", "name", "vendorcode", "barcode", "uom", "price", "totalqty", "totalquantity",
            "stcode", "source", "description",
            "mainimage", "mainimageurl", "image", "imageurl", "photourl", "photo", "pictures", "images");

    private final JLabel image = new JLabel("Загружаем фото…", SwingConstants.CENTER);

    ProductCardDialog(java.awt.Window owner, Product product) {
        super(owner, "Карточка товара — " + product.name(), ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(680, 500));

        JPanel content = new JPanel(new BorderLayout(16, 14));
        content.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        JLabel title = new JLabel(product.name());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 21f));
        content.add(title, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(18, 0));
        image.setPreferredSize(new Dimension(280, 330));
        image.setMinimumSize(new Dimension(240, 280));
        image.setBorder(BorderFactory.createEtchedBorder());
        image.setVerticalAlignment(SwingConstants.CENTER);
        body.add(image, BorderLayout.WEST);
        body.add(createDetails(product), BorderLayout.CENTER);
        content.add(body, BorderLayout.CENTER);
        setContentPane(content);
        pack();
        setSize(Math.max(720, getWidth()), Math.max(540, getHeight()));
        setLocationRelativeTo(owner);
        loadImage(product);
    }

    private JScrollPane createDetails(Product product) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"Цена", product.price() == null ? "—" : product.price().format()});
        rows.add(new Object[]{"Остаток", blankAsDash(product.totalQuantity())});
        rows.add(new Object[]{"Артикул", blankAsDash(product.vendorCode())});
        rows.add(new Object[]{"Штрихкод", blankAsDash(product.barcode())});
        rows.add(new Object[]{"Код SetRetailX", blankAsDash(product.stCode())});
        rows.add(new Object[]{"Ед. измерения", blankAsDash(product.uom())});

        String description = findDetail(product.details(), "description");
        if (!description.isBlank()) rows.add(new Object[]{"Описание", description});
        for (Map.Entry<String, String> detail : product.details().entrySet()) {
            String normalized = normalizeKey(detail.getKey());
            if (HIDDEN_FIELDS.contains(normalized) || detail.getValue().isBlank()) continue;
            String label = LABELS.getOrDefault(normalized, humanize(detail.getKey()));
            rows.add(new Object[]{label, detail.getValue()});
        }

        DefaultTableModel model = new DefaultTableModel(new Object[]{"Поле", "Значение"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        rows.forEach(model::addRow);
        JTable table = new JTable(model);
        table.setRowHeight(26);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(130);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.setFillsViewportHeight(true);
        return new JScrollPane(table);
    }

    private void loadImage(Product product) {
        String url = ProductImageService.findImageUrl(product.details());
        if (url.isBlank()) {
            image.setText("Для этого товара нет фотографии");
            return;
        }
        new SwingWorker<BufferedImage, Void>() {
            @Override protected BufferedImage doInBackground() throws Exception {
                byte[] bytes = ProductImageService.download(url);
                BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
                if (source == null) throw new java.io.IOException("Не удалось прочитать файл изображения.");
                int width = 280;
                int height = 330;
                double scale = Math.min((double) width / source.getWidth(), (double) height / source.getHeight());
                int scaledWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
                int scaledHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
                Image scaled = source.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH);
                BufferedImage result = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = result.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(scaled, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
                return result;
            }

            @Override protected void done() {
                try {
                    image.setText("");
                    image.setIcon(new ImageIcon(get()));
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    image.setIcon(null);
                    image.setText("Не удалось загрузить фото: " + safeMessage(cause));
                }
            }
        }.execute();
    }

    private static String findDetail(Map<String, String> details, String key) {
        return details.entrySet().stream()
                .filter(entry -> normalizeKey(entry.getKey()).equals(key))
                .map(Map.Entry::getValue)
                .findFirst().orElse("");
    }

    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-zа-я0-9]", "");
    }

    private static String humanize(String key) {
        String spaced = key.replaceAll("([a-zа-я])([A-ZА-Я])", "$1 $2")
                .replace('_', ' ').replace('-', ' ').trim();
        return spaced.isEmpty() ? key : spaced.substring(0, 1).toUpperCase(Locale.forLanguageTag("ru-RU")) + spaced.substring(1);
    }

    private static String blankAsDash(String text) { return text == null || text.isBlank() ? "—" : text; }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? "ошибка сети или формата" : error.getMessage();
    }
}
