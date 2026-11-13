package com.setretailx.printer.ui;

import com.setretailx.printer.model.Product;
import com.setretailx.printer.service.ProductImageService;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Readable product details dialog with asynchronously loaded catalog photography.
 * Layout: header with title and category, photo on the left, structured details
 * (key fields + characteristics) on the right, description below.
 */
@SuppressWarnings("serial")
final class ProductCardDialog extends JPanel {
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("brand", "Бренд"), Map.entry("brandname", "Бренд"),
            Map.entry("manufacturer", "Производитель"), Map.entry("manufacturername", "Производитель"),
            Map.entry("model", "Модель"), Map.entry("color", "Цвет"), Map.entry("colorname", "Цвет"),
            Map.entry("size", "Размер"), Map.entry("sizename", "Размер"),
            Map.entry("material", "Материал"), Map.entry("composition", "Состав"),
            Map.entry("country", "Страна производства"), Map.entry("countryoforigin", "Страна производства"),
            Map.entry("season", "Сезон"), Map.entry("weight", "Вес, кг"), Map.entry("volume", "Объём"),
            Map.entry("weightgross", "Вес брутто, кг"), Map.entry("length", "Длина, мм"),
            Map.entry("height", "Высота, мм"), Map.entry("width", "Ширина, мм"),
            Map.entry("isadult", "Товар для взрослых"), Map.entry("categorypath", "Категория"),
            Map.entry("cost", "Себестоимость"), Map.entry("warranty", "Гарантия"),
            Map.entry("series", "Серия"), Map.entry("collection", "Коллекция"));
    private static final List<String> HIDDEN_FIELDS = List.of(
            "productid", "name", "vendorcode", "barcode", "uom", "price", "totalqty", "totalquantity",
            "stcode", "source", "description", "categorypath",
            "mainimage", "mainimageurl", "image", "imageurl", "photourl", "photo", "pictures", "images");

    private static final Color LABEL_COLOR = new Color(110, 114, 122);
    private static final Color CATEGORY_COLOR = new Color(36, 99, 173);

    private final JLabel image = new JLabel("Загружаем фото…", SwingConstants.CENTER);
    private final JButton openPhotoButton = new JButton("Открыть в новом окне");
    private BufferedImage originalImage;

    ProductCardDialog(Product product) {
        super(new BorderLayout());
        JPanel content = new JPanel(new BorderLayout(16, 10));
        content.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JPanel header = new JPanel(new BorderLayout(0, 4));
        JLabel title = new JLabel(product.name());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 21f));
        header.add(title, BorderLayout.NORTH);
        header.add(categoryLabel(product), BorderLayout.CENTER);
        content.add(header, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Общее", createOverview(product));
        tabs.addTab("Характеристики", createCharacteristics(product));
        tabs.addTab("Raw JSON", createRawJson(product));
        content.add(tabs, BorderLayout.CENTER);

        add(content, BorderLayout.CENTER);
        setPreferredSize(new Dimension(620, 540));
        loadImage(product);
    }

    /** Category is kept in the local catalog (product_categories_path) and must not sit in the attributes table. */
    private Component categoryLabel(Product product) {
        String category = findDetail(product.details(), "categorypath");
        JLabel label = new JLabel();
        if (category.isBlank()) {
            label.setText("Категория не указана");
            label.setForeground(LABEL_COLOR);
        } else {
            label.setText("Категория: " + category);
            label.setForeground(CATEGORY_COLOR);
        }
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 13f));
        return label;
    }

    private JPanel createPhotoPanel() {
        image.setPreferredSize(new Dimension(280, 330));
        image.setMinimumSize(new Dimension(240, 280));
        image.setBorder(BorderFactory.createEtchedBorder());
        image.setVerticalAlignment(SwingConstants.CENTER);
        image.setHorizontalAlignment(SwingConstants.CENTER);

        openPhotoButton.setEnabled(false);
        openPhotoButton.addActionListener(ignored -> openPhotoInNewWindow());

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(image, BorderLayout.CENTER);
        panel.add(openPhotoButton, BorderLayout.SOUTH);
        return panel;
    }

    private void openPhotoInNewWindow() {
        if (originalImage == null) return;
        showEnlargedImage();
    }

    private JPanel createOverview(Product product) {
        JPanel overview = new JPanel(new BorderLayout(16, 10));
        overview.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        overview.add(createPhotoPanel(), BorderLayout.WEST);
        JPanel details = new JPanel(new BorderLayout(0, 10));
        JLabel price = new JLabel("Цена: " + (product.price() == null ? "—" : product.price().format()));
        price.setFont(price.getFont().deriveFont(Font.BOLD, 19f));
        details.add(price, BorderLayout.NORTH);

        JPanel keyFields = new JPanel(new GridBagLayout());
        keyFields.setBorder(BorderFactory.createTitledBorder("Основное"));
        addField(keyFields, 0, "Остаток", blankAsDash(product.totalQuantity()));
        addField(keyFields, 1, "Артикул", blankAsDash(product.vendorCode()));
        addField(keyFields, 2, "Штрихкод", blankAsDash(product.barcode()));
        addField(keyFields, 3, "Код SetRetailX", blankAsDash(product.stCode()));
        addField(keyFields, 4, "Ед. измерения", blankAsDash(product.uom()));
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.add(keyFields, BorderLayout.NORTH);

        String description = findDetail(product.details(), "description");
        if (!description.isBlank()) {
            JEditorPane descriptionPane = new JEditorPane();
            descriptionPane.setEditorKit(new HTMLEditorKit());
            descriptionPane.setEditable(false);
            descriptionPane.setText(asHtml(description));
            descriptionPane.setCaretPosition(0);
            JScrollPane descriptionScroll = new JScrollPane(descriptionPane);
            descriptionScroll.setBorder(BorderFactory.createTitledBorder("Описание"));
            content.add(descriptionScroll, BorderLayout.CENTER);
        }
        details.add(content, BorderLayout.CENTER);
        overview.add(details, BorderLayout.CENTER);
        return overview;
    }

    private JScrollPane createCharacteristics(Product product) {
        JPanel characteristics = new JPanel(new GridBagLayout());
        characteristics.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        int row = 0;
        for (Map.Entry<String, String> detail : product.details().entrySet()) {
            String normalized = normalizeKey(detail.getKey());
            if (HIDDEN_FIELDS.contains(normalized) || normalized.equals("catalogattributes") || detail.getValue().isBlank()) continue;
            String value = detail.getValue();
            if (normalized.equals("isadult")) value = value.equals("1") || value.equalsIgnoreCase("true") ? "Да" : "Нет";
            addField(characteristics, row++, LABELS.getOrDefault(normalized, humanize(detail.getKey())), value);
        }
        if (row == 0) {
            JLabel empty = new JLabel("Для этого товара нет дополнительных характеристик");
            empty.setForeground(LABEL_COLOR);
            empty.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            characteristics.add(empty, gridBagConstraints(0));
        }
        JScrollPane characteristicsScroll = new JScrollPane(characteristics);
        characteristicsScroll.getVerticalScrollBar().setUnitIncrement(16);
        return characteristicsScroll;
    }

    private JScrollPane createRawJson(Product product) {
        JTextArea raw = new JTextArea(rawJson(product));
        raw.setEditable(false);
        raw.setLineWrap(false);
        raw.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        raw.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(raw);
        scroll.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private static String rawJson(Product product) {
        if (!product.rawJson().isBlank()) return product.rawJson();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("stCode", product.stCode());
        values.put("name", product.name());
        values.put("vendorCode", product.vendorCode());
        values.put("barcode", product.barcode());
        values.put("uom", product.uom());
        values.put("source", product.source());
        values.put("price", product.price() == null ? null : product.price().format());
        values.put("totalQuantity", product.totalQuantity());
        values.put("details", product.details());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            return values.toString();
        }
    }

    private void addField(JPanel panel, int row, String label, String value) {
        JLabel caption = new JLabel(label);
        caption.setForeground(LABEL_COLOR);
        caption.setFont(caption.getFont().deriveFont(Font.PLAIN, 12.5f));
        JLabel content = new JLabel(value);
        content.setFont(content.getFont().deriveFont(Font.PLAIN, 13.5f));
        panel.add(caption, gridBagConstraints(row));
        GridBagConstraints valueConstraints = gridBagConstraints(row);
        valueConstraints.gridx = 1;
        valueConstraints.weightx = 1.0;
        panel.add(content, valueConstraints);
    }

    private GridBagConstraints gridBagConstraints(int row) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(3, 8, 3, 12);
        return constraints;
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
                originalImage = source;
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
                    image.setToolTipText("Нажмите или используйте кнопку, чтобы открыть фото в новом окне");
                    image.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    image.addMouseListener(new java.awt.event.MouseAdapter() {
                        @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                            if (event.getClickCount() == 1 && originalImage != null) showEnlargedImage();
                        }
                    });
                    openPhotoButton.setEnabled(true);
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    image.setIcon(null);
                    image.setText("Не удалось загрузить фото: " + safeMessage(cause));
                }
            }
        }.execute();
    }

    private void showEnlargedImage() {
        if (originalImage == null) return;
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int maxWidth = Math.max(320, (int) (screen.width * 0.88));
        int maxHeight = Math.max(240, (int) (screen.height * 0.82));
        double scale = Math.min(1.0, Math.min((double) maxWidth / originalImage.getWidth(), (double) maxHeight / originalImage.getHeight()));
        int width = Math.max(1, (int) Math.round(originalImage.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(originalImage.getHeight() * scale));
        Image scaled = originalImage.getScaledInstance(width, height, Image.SCALE_SMOOTH);
        JLabel enlarged = new JLabel(new ImageIcon(scaled));
        java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
        javax.swing.JDialog dialog = new javax.swing.JDialog(owner, "Фотография товара",
                java.awt.Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(javax.swing.JDialog.DISPOSE_ON_CLOSE);
        dialog.add(new JScrollPane(enlarged));
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private static String asHtml(String description) {
        String content = description.trim();
        if (!content.matches("(?is).*<[a-z][^>]*>.*")) {
            content = content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\r\n", "\n").replace("\n", "<br>");
            return "<html><body style='font-family:sans-serif;font-size:10pt'>" + content + "</body></html>";
        }
        return "<html><body style='font-family:sans-serif;font-size:10pt'>" + content + "</body></html>";
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
