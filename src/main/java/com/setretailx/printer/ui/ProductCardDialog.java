package com.setretailx.printer.ui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setretailx.printer.model.Product;
import com.setretailx.printer.repository.AddressStorageRepository;
import com.setretailx.printer.repository.AddressStorageRepository.LocationResult;
import com.setretailx.printer.repository.SalesPointRepository;
import com.setretailx.printer.repository.SalesPointRepository.PointItemLocation;
import com.setretailx.printer.service.ProductImageService;
import com.setretailx.printer.service.PriceMonitoringTaskService;
import com.setretailx.printer.service.ParentApiClient;
import com.setretailx.printer.service.setretailx.dto.ProductDto;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JSlider;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.PrinterJob;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    private static final ObjectMapper JSON = new ObjectMapper();
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

    private final Product product;
    private final JLabel image = new JLabel("Загружаем фото…", SwingConstants.CENTER);
    private final JButton printInfoButton = new JButton("Печать информационного листа…");
    private final AddressStorageRepository addressStorage;
    private final SalesPointRepository salesPoints;
    private final ParentApiClient connector;
    private BufferedImage originalImage;

    ProductCardDialog(Product product, AddressStorageRepository addressStorage,
                      PriceMonitoringTaskService priceHistoryService, ParentApiClient connector) {
        super(new BorderLayout());
        this.product = product;
        this.addressStorage = addressStorage;
        this.salesPoints = new SalesPointRepository();
        this.connector = connector;
        JPanel content = new JPanel(new BorderLayout(16, 10));
        content.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JPanel header = new JPanel(new BorderLayout(0, 4));
        JLabel title = new JLabel(product.name());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 21f));
        header.add(title, BorderLayout.NORTH);
        header.add(categoryLabel(product), BorderLayout.CENTER);
        content.add(header, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Общее", createOverview(product, priceHistoryService));
        tabs.addTab("Характеристики", createCharacteristics(product));
        tabs.addTab("Размещение", createLocations(product));
        tabs.addTab("История цен", createPriceHistory(product, priceHistoryService));
        tabs.addTab("Продажи", createSalesHistory(product, priceHistoryService));
        tabs.setTabPlacement(JTabbedPane.BOTTOM);
        content.add(tabs, BorderLayout.CENTER);
        printInfoButton.addActionListener(event -> printProductInfo(product));

        add(content, BorderLayout.CENTER);
        setPreferredSize(new Dimension(620, 540));
        loadImage(product);
    }

    void printInfoFromSearch() {
        printProductInfo(product);
    }

    private void printProductInfo(Product product) {
        JComboBox<PaperSize> paperSize = new JComboBox<>(PaperSize.values());
        paperSize.setSelectedItem(PaperSize.A4);
        JCheckBox includePhoto = new JCheckBox("Добавить чёрно-белое фото", originalImage != null);
        includePhoto.setEnabled(originalImage != null);
        if (originalImage == null) includePhoto.setToolTipText("Фото ещё не загружено или недоступно");
        JPanel options = new JPanel(new GridBagLayout());
        JLabel formatLabel = new JLabel("Формат листа");
        formatLabel.setForeground(LABEL_COLOR);
        GridBagConstraints formatConstraints = gridBagConstraints(0);
        options.add(formatLabel, formatConstraints);
        formatConstraints.gridx = 1;
        formatConstraints.weightx = 1;
        options.add(paperSize, formatConstraints);
        GridBagConstraints photoConstraints = gridBagConstraints(1);
        photoConstraints.gridwidth = 2;
        options.add(includePhoto, photoConstraints);
        int choice = JOptionPane.showConfirmDialog(this, options, "Печать информации о товаре",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;

        printInfoButton.setEnabled(false);
        printInfoButton.setText("Запрашиваем цены…");
        BufferedImage photo = includePhoto.isSelected() && originalImage != null ? toGrayscale(originalImage) : null;
        PaperSize selectedSize = (PaperSize) paperSize.getSelectedItem();
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() {
                try {
                    List<ProductDto> results = connector.getProducts(List.of(product.marking()));
                    ProductDto match = results.stream()
                            .filter(item -> item.marking() != null && item.marking().equalsIgnoreCase(product.marking()))
                            .findFirst().orElse(results.size() == 1 ? results.get(0) : null);
                    List<String> prices = new ArrayList<>(match == null ? List.of() : productPrices(match));
                    try {
                        List<PointItemLocation> locations = salesPoints.locationsForProduct(product.marking(), product.barcode());
                        String points = locations.stream().map(PointItemLocation::pointName).distinct()
                                .collect(java.util.stream.Collectors.joining(", "));
                        prices.add("Торговые точки: " + (points.isBlank() ? "не найдено" : points));
                    } catch (Exception ignored) {
                        prices.add("Торговые точки: нет данных");
                    }
                    return prices;
                } catch (Exception ignored) {
                    return List.of();
                }
            }

            @Override protected void done() {
                try {
                    List<String> prices = get();
                    String priceNote = prices.isEmpty()
                            ? "Ценника, к сожалению, нет. Узнайте цену в прайсчекере."
                            : "";
                    openProductPrintDialog(product, selectedSize, photo, prices, priceNote);
                } catch (Exception error) {
                    JOptionPane.showMessageDialog(ProductCardDialog.this,
                            "Не удалось подготовить лист: " + safeMessage(error), "Печать",
                            JOptionPane.ERROR_MESSAGE);
                } finally {
                    printInfoButton.setEnabled(true);
                    printInfoButton.setText("Печать информационного листа…");
                }
            }
        }.execute();
    }

    private void openProductPrintDialog(Product product, PaperSize size, BufferedImage photo,
                                        List<String> prices, String priceNote) {
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("Информация о товаре · " + product.name());
        PageFormat page = job.defaultPage();
        Paper paper = new Paper();
        double width = size.widthMm / 25.4 * 72;
        double height = size.heightMm / 25.4 * 72;
        double margin = 28;
        paper.setSize(width, height);
        paper.setImageableArea(margin, margin, width - margin * 2, height - margin * 2);
        page.setPaper(paper);
        page.setOrientation(PageFormat.PORTRAIT);
        job.setPrintable(new ProductInfoPrintable(product, photo, prices, priceNote, size,
                LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))), page);
        if (!job.printDialog()) return;
        try {
            job.print();
        } catch (Exception error) {
            JOptionPane.showMessageDialog(this, "Не удалось напечатать лист: " + safeMessage(error),
                    "Печать", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static List<String> productPrices(ProductDto product) {
        List<String> prices = new ArrayList<>();
        appendPrices(prices, "Цена", product.pricesMap());
        appendPrices(prices, "Цена 99", product.prices99Map());
        return List.copyOf(prices);
    }

    private static void appendPrices(List<String> target, String group,
                                     Map<String, com.fasterxml.jackson.databind.JsonNode> values) {
        values.forEach((key, rawValue) -> {
            com.fasterxml.jackson.databind.JsonNode value = rawValue != null && rawValue.isObject()
                    ? rawValue.get("value") : rawValue;
            if (value == null || value.isNull() || value.asText().isBlank()) return;
            String label = switch (key) {
                case "2" -> group + " (обычная)";
                case "4" -> group + " (акционная)";
                default -> group + " · " + key;
            };
            target.add(label + ": " + value.asText().replace('.', ',') + " ₽");
        });
    }

    private static BufferedImage toGrayscale(BufferedImage source) {
        BufferedImage gray = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = gray.createGraphics();
        try { graphics.drawImage(source, 0, 0, null); }
        finally { graphics.dispose(); }
        BufferedImage contrasted = new BufferedImage(gray.getWidth(), gray.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        new java.awt.image.RescaleOp(1.15f, -18f, null).filter(gray, contrasted);
        return contrasted;
    }

    private enum PaperSize {
        A5("A5 · половина A4", 148, 210), A4("A4", 210, 297),
        COMPACT_TOP_LEFT("Компактный · ¼ A4 · слева сверху", 210, 297, true, false),
        COMPACT_TOP_RIGHT("Компактный · ¼ A4 · справа сверху", 210, 297, true, true);
        private final String label;
        private final int widthMm;
        private final int heightMm;
        private final boolean compact;
        private final boolean rightSide;
        PaperSize(String label, int widthMm, int heightMm) {
            this(label, widthMm, heightMm, false, false);
        }
        PaperSize(String label, int widthMm, int heightMm, boolean compact, boolean rightSide) {
            this.label = label; this.widthMm = widthMm; this.heightMm = heightMm;
            this.compact = compact; this.rightSide = rightSide;
        }
        @Override public String toString() { return label; }
    }

    private JPanel createPriceHistory(Product product, PriceMonitoringTaskService service) {
        if (product.marking() == null || product.marking().isBlank()) {
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(new JLabel("Для этого товара не указан артикул — история цен недоступна."), BorderLayout.NORTH);
            return panel;
        }
        return new PriceHistoryPanel(service, product.marking(), product.name(), product.barcode());
    }

    private JPanel createSalesHistory(Product product, PriceMonitoringTaskService service) {
        if (product.marking() == null || product.marking().isBlank()) {
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(new JLabel("Для этого товара не указан артикул — история продаж недоступна."), BorderLayout.NORTH);
            return panel;
        }
        return new SalesHistoryPanel(service, product.marking(), product.name(), product.barcode());
    }

    private JPanel createLocations(Product product) {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 8, 8, 8));
        JLabel status = new JLabel("Загружаем адресное размещение…");
        DefaultTableModel model = new DefaultTableModel(
                new String[]{"Адрес", "Название", "Коробка", "Кол-во"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(status, BorderLayout.SOUTH);
        if (addressStorage == null) {
            status.setText("Информация о размещении недоступна.");
            return panel;
        }
        new SwingWorker<List<LocationResult>, Void>() {
            @Override protected List<LocationResult> doInBackground() throws Exception {
                return addressStorage.findProductLocations(product);
            }
            @Override protected void done() {
                try {
                    List<LocationResult> locations = get();
                    model.setRowCount(0);
                    for (LocationResult location : locations) model.addRow(new Object[]{location.addressId(),
                            location.addressName(), location.boxCode(), location.quantity()});
                    status.setText(locations.isEmpty() ? "Товар не размещён в адресном хранении."
                            : "Мест размещения: " + locations.size());
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    status.setText("Не удалось загрузить размещение: " + safeMessage(cause));
                }
            }
        }.execute();
        return panel;
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

        image.setToolTipText("Открыть фотографию в отдельном окне");
        image.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        image.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 1 && originalImage != null) openPhotoInNewWindow();
            }
        });
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(image, BorderLayout.CENTER);
        return panel;
    }

    private void openPhotoInNewWindow() {
        if (originalImage == null) return;
        showEnlargedImage();
    }

    private JPanel createOverview(Product product, PriceMonitoringTaskService priceHistoryService) {
        JPanel overview = new JPanel(new BorderLayout(16, 10));
        overview.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        overview.add(createPhotoPanel(), BorderLayout.WEST);
        JPanel details = new JPanel(new BorderLayout(0, 10));
        JLabel price = new JLabel("Цена каталога: " + (product.price() == null ? "—" : product.price().format()));
        price.setFont(price.getFont().deriveFont(Font.BOLD, 19f));
        details.add(price, BorderLayout.NORTH);
        JLabel pointStatus = new JLabel("Проверяем торговые точки…");
        pointStatus.setForeground(CATEGORY_COLOR);
        JPanel latestPrice = new JPanel(new java.awt.GridLayout(0, 1));
        latestPrice.add(pointStatus);
        details.add(latestPrice, BorderLayout.SOUTH);
        loadSalesPointLocations(product, pointStatus);
        loadLatestPrice(product, priceHistoryService, latestPrice);

        JPanel keyFields = new JPanel(new GridBagLayout());
        keyFields.setBorder(BorderFactory.createTitledBorder("Основное"));
        addField(keyFields, 0, "Остаток", blankAsDash(product.totalQuantity()));
        addField(keyFields, 1, "Артикул", blankAsDash(product.marking()));
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

    private void loadSalesPointLocations(Product product, JLabel status) {
        new SwingWorker<List<PointItemLocation>, Void>() {
            @Override protected List<PointItemLocation> doInBackground() throws Exception {
                return salesPoints.locationsForProduct(product.marking(), product.barcode());
            }
            @Override protected void done() {
                try {
                    List<PointItemLocation> locations = get();
                    status.setText(locations.isEmpty() ? "На торговых точках не найдено" : "Торговые точки: "
                            + locations.stream().map(PointItemLocation::pointName).distinct().limit(3)
                            .collect(java.util.stream.Collectors.joining(", "))
                            + (locations.size() > 3 ? " и ещё " + (locations.size() - 3) : ""));
                } catch (Exception error) { status.setText("Торговые точки: нет данных"); }
            }
        }.execute();
    }

    private void loadLatestPrice(Product product, PriceMonitoringTaskService service, JPanel details) {
        new SwingWorker<PriceMonitoringTaskService.ShelfPrice, Void>() {
            @Override protected PriceMonitoringTaskService.ShelfPrice doInBackground() throws Exception {
                return service.latestShelfPrice(product.marking());
            }

            @Override protected void done() {
                try {
                    PriceMonitoringTaskService.ShelfPrice latest = get();
                    if (latest == null || (latest.regularPrice().isBlank() && latest.discountPrice().isBlank())) return;
                    String current = latest.regularPrice().isBlank() ? latest.discountPrice() : latest.regularPrice();
                    JLabel shelf = new JLabel("Последняя цена мониторинга: " + current + " ₽");
                    shelf.setForeground(new Color(36, 99, 173));
                    details.add(shelf);
                    boolean changed = product.price() != null && !samePrice(product.price(), current);
                    if (changed) {
                        JLabel warning = new JLabel("Цена отличается от каталога. Проверьте историю изменений.");
                        warning.setForeground(new Color(170, 80, 20));
                        details.add(warning);
                    }
                    details.revalidate();
                    details.repaint();
                } catch (Exception ignored) { }
            }
        }.execute();
    }

    private static boolean samePrice(com.setretailx.printer.model.Price price, String value) {
        try { return price.kopecks() == Math.round(Double.parseDouble(value.replace(',', '.')) * 100); }
        catch (NumberFormatException error) { return false; }
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
                    image.setToolTipText("Открыть фотографию в отдельном окне");
                    image.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    image.addMouseListener(new java.awt.event.MouseAdapter() {
                        @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                            if (event.getClickCount() == 1 && originalImage != null) showEnlargedImage();
                        }
                    });
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
        java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
        javax.swing.JDialog dialog = new javax.swing.JDialog(owner, "Фотография товара",
                java.awt.Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(javax.swing.JDialog.DISPOSE_ON_CLOSE);
        JLabel preview = new JLabel("", SwingConstants.CENTER);
        JSlider zoom = new JSlider(25, 300, 100);
        JButton rotate = new JButton("Повернуть");
        final int[] angle = {0};
        Runnable render = () -> {
            double scale = zoom.getValue() / 100.0;
            BufferedImage source = originalImage;
            if (angle[0] % 180 != 0) source = new BufferedImage(originalImage.getHeight(), originalImage.getWidth(), BufferedImage.TYPE_INT_ARGB);
            else source = new BufferedImage(originalImage.getWidth(), originalImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = source.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                java.awt.geom.AffineTransform transform = new java.awt.geom.AffineTransform();
                transform.translate(source.getWidth() / 2.0, source.getHeight() / 2.0);
                transform.rotate(Math.toRadians(angle[0]));
                transform.scale(scale, scale);
                transform.translate(-originalImage.getWidth() / 2.0, -originalImage.getHeight() / 2.0);
                graphics.drawImage(originalImage, transform, null);
            } finally { graphics.dispose(); }
            preview.setIcon(new ImageIcon(source));
            preview.revalidate();
        };
        zoom.addChangeListener(event -> render.run());
        rotate.addActionListener(event -> { angle[0] = (angle[0] + 90) % 360; render.run(); });
        JPanel controls = new JPanel(new BorderLayout(8, 0));
        controls.add(new JLabel("Масштаб"), BorderLayout.WEST);
        controls.add(zoom, BorderLayout.CENTER);
        controls.add(rotate, BorderLayout.EAST);
        dialog.add(new JScrollPane(preview), BorderLayout.CENTER);
        dialog.add(controls, BorderLayout.SOUTH);
        dialog.setSize(780, 620);
        dialog.setLocationRelativeTo(this);
        render.run();
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

    private static final class ProductInfoPrintable implements java.awt.print.Printable {
        private static final int CONTENT_WIDTH = 540;
        private static final Font TITLE = new Font("DejaVu Sans", Font.BOLD, 17);
        private static final Font SUBTITLE = new Font("DejaVu Sans", Font.PLAIN, 8);
        private static final Font SECTION = new Font("DejaVu Sans", Font.BOLD, 9);
        private static final Font BODY = new Font("DejaVu Sans", Font.PLAIN, 9);
        private static final Font BODY_BOLD = new Font("DejaVu Sans", Font.BOLD, 9);
        private static final Font SMALL = new Font("DejaVu Sans", Font.PLAIN, 7);
        private static final Font SERVICE = new Font("DejaVu Sans", Font.PLAIN, 7);
        private static final Font CODE = new Font("DejaVu Sans Mono", Font.BOLD, 34);
        private static final Font ARTICLE = new Font("DejaVu Sans", Font.BOLD, 15);
        private static final Font BARCODE_TEXT = new Font("DejaVu Sans Mono", Font.PLAIN, 13);
        private static final Color MID_GRAY = new Color(68, 68, 68);
        private static final Color LIGHT_GRAY = new Color(228, 228, 228);
        private static final Color RULE = new Color(187, 187, 187);
        private final Product product;
        private final BufferedImage photo;
        private final List<String> prices;
        private final String priceNote;
        private final PaperSize paperSize;
        private final String date;
        private final BufferedImage qr;
        private final BufferedImage barcode;
        private final boolean validEan;

        private ProductInfoPrintable(Product product, BufferedImage photo, List<String> prices,
                                     String priceNote, PaperSize paperSize, String date) {
            this.product = product;
            this.photo = photo;
            this.prices = List.copyOf(prices);
            this.priceNote = priceNote;
            this.paperSize = paperSize;
            this.date = date;
            qr = encode(product.stCode(), BarcodeFormat.QR_CODE, 180, 180);
            validEan = isValidEan13(product.barcode());
            barcode = validEan ? encode(product.barcode(), BarcodeFormat.EAN_13, 420, 90) : null;
        }

        @Override public int print(java.awt.Graphics graphics, PageFormat page, int pageIndex) {
            if (pageIndex > 0) return NO_SUCH_PAGE;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                double slotWidth = paperSize.compact ? page.getImageableWidth() / 2.0 : page.getImageableWidth();
                double slotHeight = paperSize.compact ? page.getImageableHeight() / 2.0 : page.getImageableHeight();
                double slotX = page.getImageableX()
                        + (paperSize.compact && paperSize.rightSide ? slotWidth : 0);
                g.translate(slotX, page.getImageableY());
                g.setColor(Color.BLACK);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                int neededHeight = paperSize.compact ? renderCompact(g, false) : render(g, false);
                g.dispose();
                g = (Graphics2D) graphics.create();
                g.translate(slotX, page.getImageableY());
                g.setColor(Color.BLACK);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                double layoutWidth = paperSize.compact ? CONTENT_WIDTH / 2.0 : CONTENT_WIDTH;
                double fit = Math.min(slotWidth / layoutWidth, slotHeight / Math.max(1, neededHeight));
                g.scale(fit, fit);
                if (paperSize.compact) renderCompact(g, true);
                else render(g, true);
                return PAGE_EXISTS;
            } finally {
                g.dispose();
            }
        }

        private int render(Graphics2D g, boolean paint) {
            int x = 0, width = CONTENT_WIDTH, y = 0;
            g.setColor(Color.BLACK);
            g.setFont(TITLE);
            y = drawWrapped(g, product.name(), x, y + 19, width, 20, paint);
            g.setFont(SUBTITLE);
            g.setColor(MID_GRAY);
            y = drawWrapped(g, "Информация о товаре · " + date, x, y + 2, width, 10, paint);
            g.setColor(Color.BLACK);
            if (paint) {
                g.setStroke(new java.awt.BasicStroke(2f));
                g.drawLine(x, y + 4, width, y + 4);
            }
            y += 12;

            if (photo != null) {
                int photoWidth = 150;
                int photoHeight = Math.min(145, Math.max(1, (int) ((double) photo.getHeight() / photo.getWidth() * photoWidth)));
                if (paint) {
                    g.drawImage(photo, x, y, photoWidth, photoHeight, null);
                    g.setStroke(new java.awt.BasicStroke(0.9f));
                    g.drawRect(x, y, photoWidth, photoHeight);
                }
                y += photoHeight + 8;
            }

            int tagEnd = drawTags(g, y, paint);
            y = tagEnd == y ? y : tagEnd + 4;

            String description = findDetail(product.details(), "description");
            if (!description.isBlank() && !isJsonPayload(description)) {
                y = drawSection(g, "ОПИСАНИЕ", x, y, width, paint);
                g.setFont(BODY);
                y = drawWrapped(g, normalizeText(description), x + 2, y + 10, width - 4, 12, paint) + 7;
            }

            List<Map.Entry<String, String>> main = new ArrayList<>();
            for (Map.Entry<String, String> entry : product.details().entrySet()) {
                if (!isPrintableDetail(entry)) continue;
                main.add(entry);
            }
            if (!main.isEmpty()) {
                y = drawSection(g, "ХАРАКТЕРИСТИКИ", x, y, width, paint);
                y = drawSubheading(g, "Основные", y, width, paint);
                for (Map.Entry<String, String> entry : main) {
                    String key = normalizeKey(entry.getKey());
                    String value = key.equals("isadult")
                            ? (entry.getValue().equals("1") || entry.getValue().equalsIgnoreCase("true") ? "Да" : "Нет")
                            : normalizeText(entry.getValue());
                    y = drawAttribute(g, LABELS.getOrDefault(key, humanize(entry.getKey())), value,
                            y, width, BODY, BODY_BOLD, paint);
                }
            }

            y += 4;
            y = drawSection(g, "КОДЫ", x, y, width, paint);
            y = drawCodeCells(g, x, y, width, paint) + 4;
            y += 8;
            if (paint) {
                g.setColor(RULE);
                g.setStroke(new java.awt.BasicStroke(0.5f));
                g.drawLine(x, y, width, y);
                g.setColor(Color.BLACK);
            }
            y += 10;
            g.setFont(SMALL);
            String brand = findDetail(product.details(), "brand");
            if (brand.isBlank()) brand = findDetail(product.details(), "brandname");
            String footerLeft = (product.stCode().isBlank() ? "" : "SAP " + product.stCode())
                    + (brand.isBlank() ? "" : " · " + brand)
                    + (validEan ? " · Штрихкод: EAN-13" : " · Штрихкод только при верной EAN-13");
            String footerRight = "QR содержит SAP";
            if (paint) {
                g.drawString(footerLeft, x, y);
                g.drawString(footerRight, Math.max(x, width - g.getFontMetrics().stringWidth(footerRight)), y);
            }
            return y + 8;
        }

        /** Compact top-quarter layout: keeps only the most useful fields and omits long text/service data. */
        private int renderCompact(Graphics2D g, boolean paint) {
            int width = CONTENT_WIDTH / 2;
            int y = 0;
            g.setColor(Color.BLACK);
            g.setFont(new Font("DejaVu Sans", Font.BOLD, 14));
            String title = fitText(g, normalizeText(product.name()), width - 4);
            if (paint) g.drawString(title, 0, 24);
            y = 34;
            g.setFont(new Font("DejaVu Sans", Font.PLAIN, 7));
            g.setColor(MID_GRAY);
            if (paint) g.drawString("Информация о товаре · " + date, 0, y);
            g.setColor(Color.BLACK);
            y += 9;
            if (paint) {
                g.setStroke(new java.awt.BasicStroke(1.5f));
                g.drawLine(0, y, width, y);
            }
            y += 9;

            int photoWidth = 76;
            int photoHeight = 76;
            if (photo != null) {
                double scale = Math.min((double) photoWidth / photo.getWidth(), (double) photoHeight / photo.getHeight());
                int drawWidth = Math.max(1, (int) Math.round(photo.getWidth() * scale));
                int drawHeight = Math.max(1, (int) Math.round(photo.getHeight() * scale));
                if (paint) {
                    g.drawImage(photo, 0, y, drawWidth, drawHeight, null);
                    g.setStroke(new java.awt.BasicStroke(0.7f));
                    g.drawRect(0, y, drawWidth, drawHeight);
                }
            }

            int infoX = photoWidth + 8;
            int infoWidth = width - infoX;
            int textWidth = Math.max(1, infoWidth - (qr == null ? 0 : 42));
            int infoY = y + 3;
            g.setFont(new Font("DejaVu Sans", Font.BOLD, 9));
            if (paint) g.drawString(fitText(g, "Цена: " + (product.price() == null ? "—" : product.price().format()), textWidth), infoX, infoY + 10);
            g.setFont(new Font("DejaVu Sans", Font.PLAIN, 8));
            infoY += 24;
            if (paint) g.drawString(fitText(g, "SAP: " + blankAsDash(product.stCode()), textWidth), infoX, infoY);
            infoY += 17;
            if (paint) g.drawString(fitText(g, "Артикул: " + blankAsDash(product.marking()), textWidth), infoX, infoY);
            infoY += 17;
            if (paint) g.drawString(fitText(g, "Остаток: " + blankAsDash(product.totalQuantity()), textWidth), infoX, infoY);
            infoY += 14;

            if (barcode != null && paint) {
                g.drawImage(barcode, infoX, infoY, Math.min(170, textWidth), 33, null);
            } else if (paint && !product.barcode().isBlank()) {
                g.setFont(new Font("DejaVu Sans", Font.PLAIN, 7));
                g.drawString(fitText(g, "Штрихкод: " + product.barcode(), textWidth), infoX, infoY + 10);
            }
            if (paint && qr != null) g.drawImage(qr, width - 38, y + 40, 34, 34, null);
            y += photoHeight + 7;

            List<Map.Entry<String, String>> attributes = product.details().entrySet().stream()
                    .filter(ProductInfoPrintable::isPrintableDetail)
                    .limit(7)
                    .toList();
            if (!attributes.isEmpty()) {
                y = drawSection(g, "ОСНОВНЫЕ ХАРАКТЕРИСТИКИ", 0, y, width, paint);
                g.setFont(new Font("DejaVu Sans", Font.PLAIN, 7));
                for (Map.Entry<String, String> entry : attributes) {
                    String key = normalizeKey(entry.getKey());
                    String label = LABELS.getOrDefault(key, humanize(entry.getKey()));
                    String value = key.equals("isadult")
                            ? (entry.getValue().equals("1") || entry.getValue().equalsIgnoreCase("true") ? "Да" : "Нет")
                            : normalizeText(entry.getValue());
                    if (paint) {
                        g.setFont(new Font("DejaVu Sans", Font.BOLD, 7));
                        g.drawString(fitText(g, label, 92), 4, y + 11);
                        g.setFont(new Font("DejaVu Sans", Font.PLAIN, 7));
                        g.drawString(fitText(g, value, width - 104), 100, y + 11);
                        g.setColor(RULE);
                        g.drawLine(0, y + 13, width, y + 13);
                        g.setColor(Color.BLACK);
                    }
                    y += 16;
                }
            }

            String note = !priceNote.isBlank() ? priceNote : String.join(" · ", prices);
            if (!note.isBlank()) {
                y += 3;
                g.setFont(new Font("DejaVu Sans", Font.PLAIN, 7));
                if (paint) {
                    g.setColor(LIGHT_GRAY);
                    g.fillRect(0, y, width, 24);
                    g.setColor(Color.BLACK);
                    g.drawString(fitText(g, note, width - 12), 6, y + 15);
                }
                y += 29;
            }
            return y + 3;
        }

        private static String fitText(Graphics2D g, String text, int width) {
            String result = text == null ? "" : text;
            if (g.getFontMetrics().stringWidth(result) <= width) return result;
            while (!result.isEmpty() && g.getFontMetrics().stringWidth(result + "…") > width) {
                result = result.substring(0, result.length() - 1);
            }
            return result.isEmpty() ? "…" : result + "…";
        }

        private int drawTags(Graphics2D g, int y, boolean paint) {
            List<String> tags = new ArrayList<>();
            String brand = firstDetail("brand", "brandname");
            if (!brand.isBlank()) tags.add("БРЕНД: " + brand.toUpperCase(Locale.ROOT));
            addTag(tags, "РАЗМЕР", firstDetail("size", "sizename"));
            addTag(tags, "В УПАКОВКЕ", firstDetail("quantityinpack", "packquantity", "quantityperpack", "countinpack"));
            addTag(tags, "ТИП", firstDetail("type", "producttype"));
            addTag(tags, "ТИП ГОЛОВКИ", firstDetail("headtype", "head", "headkind"));
            addTag(tags, "ПОКРЫТИЕ", firstDetail("coating", "covering"));
            addTag(tags, "МАТЕРИАЛ", firstDetail("material"));
            if (tags.isEmpty()) return y;
            int x = 0;
            for (String tag : tags) {
                boolean brandTag = tag.startsWith("БРЕНД:");
                g.setFont(SMALL);
                int tagWidth = g.getFontMetrics().stringWidth(tag) + 12;
                if (x > 0 && x + tagWidth > CONTENT_WIDTH) { x = 0; y += 20; }
                if (paint) {
                    g.setColor(brandTag ? Color.BLACK : Color.WHITE);
                    g.fillRoundRect(x, y, tagWidth, 16, 4, 4);
                    g.setColor(Color.BLACK);
                    g.drawRoundRect(x, y, tagWidth, 16, 4, 4);
                    if (brandTag) g.setColor(Color.WHITE);
                    g.drawString(tag, x + 6, y + 11);
                    g.setColor(Color.BLACK);
                }
                x += tagWidth + 5;
            }
            return y + 19;
        }

        private String firstDetail(String... keys) {
            for (String key : keys) {
                String value = findDetail(product.details(), key);
                if (!value.isBlank() && !isJsonPayload(value)) return value;
            }
            return "";
        }

        private static void addTag(List<String> tags, String label, String value) {
            if (!value.isBlank()) tags.add(label + ": " + value.toUpperCase(Locale.ROOT));
        }

        private int drawCodeCells(Graphics2D g, int x, int y, int width, boolean paint) {
            int gap = 8, left = (width - gap) * 58 / 100, right = width - gap - left;
            drawCell(g, x, y, left, 78, "SAP", paint);
            drawCell(g, x + left + gap, y, right, 78, "АРТИКУЛ", paint);
            if (paint && !product.stCode().isBlank()) {
                g.setFont(CODE); centered(g, product.stCode(), x, y + 48, left);
            }
            if (paint && !product.marking().isBlank()) {
                g.setFont(ARTICLE); centered(g, product.marking(), x + left + gap, y + 43, right);
            }
            if (paint) {
                g.setFont(SMALL);
                centered(g, "Код SAP", x, y + 70, left);
                centered(g, "Артикул товара", x + left + gap, y + 68, right);
            }
            y += 86;
            drawCell(g, x, y, left, 116, "ШТРИХКОД", paint);
            drawCell(g, x + left + gap, y, right, 116, "QR SAP", paint);
            if (paint && !product.barcode().isBlank()) {
                String digits = product.barcode().trim();
                String suffix = digits.length() > 4 ? digits.substring(digits.length() - 4) : digits;
                String prefix = digits.length() > 4 ? digits.substring(0, digits.length() - 4) : "";
                g.setFont(validEan ? BARCODE_TEXT : new Font("DejaVu Sans Mono", Font.BOLD, 13));
                int suffixWidth = g.getFontMetrics().stringWidth(suffix) + 8;
                int prefixWidth = g.getFontMetrics().stringWidth(prefix);
                int start = x + Math.max(8, (left - prefixWidth - suffixWidth - 8) / 2);
                g.setColor(MID_GRAY); g.drawString(prefix, start, y + 34); g.setColor(Color.BLACK);
                if (validEan) g.drawRoundRect(start + prefixWidth + 4, y + 18, suffixWidth, 22, 4, 4);
                g.setFont(new Font("DejaVu Sans Mono", Font.BOLD, 13));
                g.drawString(suffix, start + prefixWidth + 8, y + 34);
                if (barcode != null) g.drawImage(barcode, x + 15, y + 42, Math.min(210, left - 30), 30, null);
                else {
                    g.setFont(SMALL); g.setColor(MID_GRAY);
                    g.drawString("EAN-13: неверная контрольная цифра", x + 8, y + 63); g.setColor(Color.BLACK);
                }
            }
            if (paint) {
                g.setFont(SMALL);
                String barcodeNote = product.barcode().isBlank() ? "Штрихкод отсутствует"
                        : validEan ? "Содержимое: EAN-13" : "EAN-13 не сформирован";
                centered(g, barcodeNote, x, y + 104, left);
            }
            if (paint && qr != null) {
                g.drawImage(qr, x + left + gap + (right - 58) / 2, y + 25, 58, 58, null);
                g.setFont(SMALL);
                centered(g, "Содержимое: код SAP", x + left + gap, y + 104, right);
            }
            y += 124;
            String note = !priceNote.isBlank() ? priceNote : String.join(" · ", prices);
            if (!note.isBlank()) {
                if (paint) {
                    g.setColor(LIGHT_GRAY); g.fillRect(x, y, width, 25); g.setColor(Color.BLACK);
                    g.setFont(BODY_BOLD); centered(g, note, x, y + 17, width);
                }
                y += 31;
            }
            return y;
        }

        private static void drawCell(Graphics2D g, int x, int y, int width, int height, String label, boolean paint) {
            if (!paint) return;
            g.setColor(Color.BLACK); g.setStroke(new java.awt.BasicStroke(0.9f)); g.drawRect(x, y, width, height);
            g.setFont(SMALL);
            g.fillRoundRect(x + 5, y + 4, g.getFontMetrics().stringWidth(label) + 12, 13, 4, 4);
            g.setFont(new Font("DejaVu Sans", Font.BOLD, 7)); g.setColor(Color.WHITE);
            g.drawString(label, x + 11, y + 13); g.setColor(Color.BLACK);
        }

        private static void centered(Graphics2D g, String text, int x, int baseline, int width) {
            Font original = g.getFont();
            while (g.getFont().getSize() > 8 && g.getFontMetrics().stringWidth(text) > width - 16) {
                g.setFont(g.getFont().deriveFont((float) g.getFont().getSize() - 1));
            }
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, x + Math.max(8, (width - fm.stringWidth(text)) / 2), baseline);
            g.setFont(original);
        }

        private static int drawSection(Graphics2D g, String title, int x, int y, int width, boolean paint) {
            if (paint) {
                g.setColor(Color.BLACK); g.fillRect(x, y, width, 16); g.setFont(SECTION); g.setColor(Color.WHITE);
                g.drawString(title, x + 7, y + 11); g.setColor(Color.BLACK);
            }
            return y + 21;
        }

        private static int drawSubheading(Graphics2D g, String title, int y, int width, boolean paint) {
            if (paint) {
                g.setColor(new Color(236, 236, 236)); g.fillRect(0, y, width, 16); g.setColor(Color.BLACK);
                g.setFont(BODY_BOLD); g.drawString(title, 6, y + 11);
            }
            return y + 20;
        }

        private static int drawAttribute(Graphics2D g, String label, String value, int y, int width,
                                         Font labelFont, Font valueFont, boolean paint) {
            int labelWidth = 190, valueX = labelWidth + 8, valueWidth = width - valueX - 6;
            g.setFont(labelFont); int labelLines = wrappedLineCount(g, label, labelWidth - 12);
            g.setFont(valueFont); int valueLines = wrappedLineCount(g, value, valueWidth);
            int lineHeight = labelFont.getSize() <= 7 ? 9 : 11, lines = Math.max(labelLines, valueLines);
            if (paint) {
                g.setColor(labelFont.getSize() <= 7 ? new Color(51, 51, 51) : Color.BLACK);
                g.setFont(labelFont); drawWrapped(g, label, 6, y + lineHeight, labelWidth - 12, lineHeight, true);
                g.setFont(valueFont); drawWrapped(g, value, valueX, y + lineHeight, valueWidth, lineHeight, true);
                g.setColor(RULE); g.setStroke(new java.awt.BasicStroke(0.5f));
                g.drawLine(0, y + lines * lineHeight + 2, width, y + lines * lineHeight + 2); g.setColor(Color.BLACK);
            }
            return y + lines * lineHeight + 5;
        }

        private static boolean isServiceField(String key) {
            return key.equals("id") || key.equals("code") || key.equals("cost") || key.startsWith("is")
                    || key.contains("flag") || key.contains("raw");
        }

        private static boolean isPrintableDetail(Map.Entry<String, String> entry) {
            String key = normalizeKey(entry.getKey());
            String value = entry.getValue();
            return !value.isBlank() && !HIDDEN_FIELDS.contains(key) && !key.equals("catalogattributes")
                    && !isServiceField(key) && !key.contains("json") && !key.contains("payload")
                    && !key.contains("request") && !key.contains("response") && !isJsonPayload(value);
        }

        /** Ignore raw API/database blobs even when they were stored under a generic attribute name. */
        private static boolean isJsonPayload(String value) {
            String candidate = value == null ? "" : value.trim();
            for (int attempt = 0; attempt < 2 && !candidate.isEmpty(); attempt++) {
                if (!(candidate.startsWith("{") || candidate.startsWith("[") || candidate.startsWith("\""))) {
                    return false;
                }
                try {
                    JsonNode parsed = JSON.readTree(candidate);
                    if (parsed == null) return false;
                    if (parsed.isObject() || parsed.isArray()) return true;
                    if (parsed.isTextual()) {
                        candidate = parsed.asText().trim();
                        continue;
                    }
                    return false;
                } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                    // A truncated or malformed blob is still not product information for a printout.
                    return candidate.startsWith("{") || candidate.startsWith("[")
                            || candidate.startsWith("\"");
                }
            }
            return false;
        }

        private static boolean isValidEan13(String value) {
            if (value == null || !value.matches("\\d{13}")) return false;
            int sum = 0;
            for (int i = 0; i < 12; i++) sum += (value.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
            return (10 - sum % 10) % 10 == value.charAt(12) - '0';
        }

        private static int wrappedLineCount(Graphics2D g, String text, int width) {
            FontMetrics fm = g.getFontMetrics(); int lines = 0;
            for (String paragraph : text.split("\\n", -1)) {
                StringBuilder line = new StringBuilder();
                for (String word : paragraph.split("\\s+")) {
                    if (word.isEmpty()) continue;
                    String proposed = line.isEmpty() ? word : line + " " + word;
                    if (fm.stringWidth(proposed) > width && !line.isEmpty()) { lines++; line.setLength(0); line.append(word); }
                    else if (line.isEmpty()) line.append(word); else line.append(' ').append(word);
                }
                if (!line.isEmpty() || paragraph.isEmpty()) lines++;
            }
            return Math.max(1, lines);
        }

        private static int drawWrapped(Graphics2D g, String text, int x, int y, int width, int lineHeight, boolean paint) {
            FontMetrics metrics = g.getFontMetrics();
            for (String paragraph : text.split("\\n")) {
                StringBuilder line = new StringBuilder();
                for (String word : paragraph.split("\\s+")) {
                    if (word.isEmpty()) continue;
                    String proposed = line.isEmpty() ? word : line + " " + word;
                    if (metrics.stringWidth(proposed) > width && !line.isEmpty()) {
                        if (paint) g.drawString(line.toString(), x, y);
                        y += lineHeight;
                        line.setLength(0);
                        line.append(word);
                    } else if (line.isEmpty()) line.append(word);
                    else line.append(' ').append(word);
                }
                if (!line.isEmpty()) { if (paint) g.drawString(line.toString(), x, y); y += lineHeight; }
            }
            return y;
        }

        private static String normalizeText(String text) {
            return text.replaceAll("<[^>]*>", " ").replaceAll("&nbsp;", " ")
                    .replaceAll("&amp;", "&").replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                    .replaceAll("[\\r\\t ]+", " ").replaceAll("\\n\\s*", "\n").trim();
        }

        private static BufferedImage encode(String value, BarcodeFormat format, int width, int height) {
            if (value == null || value.isBlank()) return null;
            try {
                return MatrixToImageWriter.toBufferedImage(new MultiFormatWriter().encode(value.trim(), format,
                        width, height, Map.of(EncodeHintType.MARGIN, 1)));
            } catch (WriterException | IllegalArgumentException ignored) {
                return null;
            }
        }
    }
}
