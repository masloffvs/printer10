package com.setretailx.printer.ui;

import com.setretailx.printer.model.Product;
import com.setretailx.printer.model.ScannedProbe;
import com.setretailx.printer.repository.SalesPointRepository;
import com.setretailx.printer.repository.SalesPointRepository.SalesPoint;
import com.setretailx.printer.repository.SalesPointRepository.ShelfItem;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Simple virtual sales-point map and each point's current article/label price. */
@SuppressWarnings("serial")
final class SalesPointsPanel extends JPanel {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());
    private final SalesPointRepository repository;
    private final MapCanvas map = new MapCanvas();
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Артикул", "Товар", "Цена на ценнике", "Обновлено"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel("Нарисуйте прямоугольник на карте, чтобы создать точку.");
    private SalesPoint selected;

    SalesPointsPanel(SalesPointRepository repository) {
        super(new BorderLayout(8, 8));
        this.repository = repository;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        map.setBorder(BorderFactory.createTitledBorder("Карта точек продаж · перетаскивание создаёт точку или перемещает её"));
        map.setPreferredSize(new Dimension(800, 390));
        JPanel lower = new JPanel(new BorderLayout(6, 6));
        JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING));
        JButton add = new JButton("Добавить артикул вручную");
        add.addActionListener(event -> addItem());
        JButton remove = new JButton("Удалить выбранный артикул");
        remove.addActionListener(event -> removeItem());
        JButton refresh = new JButton("Обновить");
        refresh.addActionListener(event -> refreshItems());
        controls.add(add); controls.add(remove); controls.add(refresh);
        lower.add(controls, BorderLayout.NORTH);
        table.setFillsViewportHeight(true);
        lower.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.add(new JScrollPane(map), BorderLayout.CENTER);
        content.add(lower, BorderLayout.SOUTH);
        add(content, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        reloadPoints();
    }

    void acceptPriceScan(Product product, ScannedProbe probe) {
        if (selected == null) { status.setText("Выберите точку продаж на карте перед сканированием ценника."); return; }
        String article = product.vendorCode().isBlank() ? product.stCode() : product.vendorCode();
        if (article.isBlank()) { status.setText("В найденном товаре нет артикула."); return; }
        try {
            var scanned = probe.pricesAsDecimals();
            BigDecimal price = scanned.get(4);
            if (price == null) price = scanned.get(2);
            if (price == null) { status.setText("На ценнике нет price4 или price2 — нечего записать."); return; }
            repository.putItem(selected.id(), article, product.name(), price.toPlainString());
            refreshItems();
            status.setText("Обновлён ценник «" + article + "» в точке «" + selected.name() + "».");
        } catch (Exception error) { showError(error); }
    }

    long selectedPointId() { return selected == null ? -1 : selected.id(); }

    void acceptPriceScan(long expectedPointId, Product product, ScannedProbe probe) {
        if (selected == null || selected.id() != expectedPointId) {
            status.setText("Выбор точки изменился во время поиска товара; ценник не сохранён. Повторите скан.");
            return;
        }
        acceptPriceScan(product, probe);
    }

    private void addItem() {
        if (selected == null) { status.setText("Сначала выберите точку на карте."); return; }
        JTextField article = new JTextField(18), name = new JTextField(18), price = new JTextField(12);
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Артикул:")); form.add(article);
        form.add(new JLabel("Название (необязательно):")); form.add(name);
        form.add(new JLabel("Цена ценника:")); form.add(price);
        if (JOptionPane.showConfirmDialog(this, form, "Текущий ценник точки",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            BigDecimal parsed = new BigDecimal(price.getText().trim().replace(',', '.'));
            if (parsed.signum() < 0) throw new NumberFormatException();
            repository.putItem(selected.id(), article.getText(), name.getText(), parsed.toPlainString());
            refreshItems();
        } catch (NumberFormatException error) { JOptionPane.showMessageDialog(this, "Введите корректную цену."); }
        catch (Exception error) { showError(error); }
    }

    private void removeItem() {
        if (selected == null || table.getSelectedRow() < 0) return;
        String article = String.valueOf(model.getValueAt(table.convertRowIndexToModel(table.getSelectedRow()), 0));
        try { repository.removeItem(selected.id(), article); refreshItems(); }
        catch (Exception error) { showError(error); }
    }

    private void reloadPoints() {
        try { map.setPoints(repository.points()); }
        catch (Exception error) { showError(error); }
    }

    private void select(SalesPoint point) {
        selected = point;
        map.setSelected(point == null ? -1 : point.id());
        model.setRowCount(0);
        if (point == null) status.setText("Выберите или создайте точку продаж.");
        else { status.setText("Точка: " + point.name() + " · выберите её, чтобы направить сюда ценник сканером."); refreshItems(); }
    }

    private void refreshItems() {
        model.setRowCount(0);
        if (selected == null) return;
        try {
            for (ShelfItem item : repository.items(selected.id()))
                model.addRow(new Object[]{item.article(), item.name(), item.price(), DATE.format(Instant.ofEpochMilli(item.updatedAt()))});
        } catch (Exception error) { showError(error); }
    }

    private void createPoint(Rectangle bounds) {
        String name = JOptionPane.showInputDialog(this, "Название торговой точки:", "Новая точка");
        if (name == null || name.isBlank()) return;
        try { SalesPoint point = repository.createPoint(name, bounds.x, bounds.y, bounds.width, bounds.height); reloadPoints(); select(point); }
        catch (Exception error) { showError(error); }
    }

    private void showPointMenu(SalesPoint point, Point location) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem open = new JMenuItem("Открыть товары / выбрать точку");
        open.addActionListener(event -> select(point)); menu.add(open);
        JMenuItem rename = new JMenuItem("Переименовать");
        rename.addActionListener(event -> {
            String name = JOptionPane.showInputDialog(this, "Название точки:", point.name());
            if (name == null || name.isBlank()) return;
            try { repository.renamePoint(point.id(), name); reloadPoints(); select(map.point(point.id())); }
            catch (Exception error) { showError(error); }
        }); menu.add(rename);
        JMenuItem delete = new JMenuItem("Удалить точку и её ценники");
        delete.addActionListener(event -> {
            if (JOptionPane.showConfirmDialog(this, "Удалить точку «" + point.name() + "» и её текущие ценники?",
                    "Удаление точки", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            try { repository.deletePoint(point.id()); reloadPoints(); if (selected != null && selected.id() == point.id()) select(null); }
            catch (Exception error) { showError(error); }
        }); menu.add(delete);
        menu.show(map, location.x, location.y);
    }

    private void showError(Exception error) {
        status.setText("Ошибка: " + (error.getMessage() == null ? error : error.getMessage()));
    }

    private final class MapCanvas extends JPanel {
        private List<SalesPoint> points = List.of();
        private long selectedId = -1;
        private Point press;
        private Point last;
        private SalesPoint moving;
        private Rectangle draft;
        private boolean contextHandled;

        private MapCanvas() {
            setOpaque(true);
            setBackground(javax.swing.UIManager.getColor("Panel.background"));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    contextHandled = false;
                    if (isContextClick(event)) {
                        showContextMenu(event);
                        contextHandled = true;
                        return;
                    }
                    if (!javax.swing.SwingUtilities.isLeftMouseButton(event)) return;
                    press = event.getPoint(); last = press; moving = at(press);
                    draft = null;
                    if (moving != null) select(moving);
                }
                @Override public void mouseReleased(MouseEvent event) {
                    if (isContextClick(event)) {
                        if (!contextHandled) showContextMenu(event);
                        contextHandled = false;
                        return;
                    }
                    if (draft != null && draft.width >= 50 && draft.height >= 35) createPoint(draft);
                    else if (moving != null) {
                        try {
                            SalesPoint moved = point(moving.id());
                            if (moved != null) repository.movePoint(moved.id(), moved.x(), moved.y());
                            reloadPoints();
                        }
                        catch (Exception error) { showError(error); }
                    } else if (press != null && event.getPoint().distance(press) < 4) {
                        SalesPoint point = at(event.getPoint()); if (point != null) select(point);
                    }
                    press = null; last = null; moving = null; draft = null; repaint();
                }
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override public void mouseDragged(MouseEvent event) {
                    if (press == null) return;
                    if (moving != null) {
                        int dx = event.getX() - last.x, dy = event.getY() - last.y;
                        List<SalesPoint> shifted = points.stream().map(p -> p.id() == moving.id()
                                ? new SalesPoint(p.id(), p.name(), Math.max(0, p.x() + dx), Math.max(0, p.y() + dy), p.width(), p.height()) : p).toList();
                        points = shifted; last = event.getPoint(); repaint();
                    } else {
                        draft = new Rectangle(Math.min(press.x, event.getX()), Math.min(press.y, event.getY()),
                                Math.abs(event.getX() - press.x), Math.abs(event.getY() - press.y)); repaint();
                    }
                }
            });
        }

        private boolean isContextClick(MouseEvent event) {
            return event.isPopupTrigger() || javax.swing.SwingUtilities.isRightMouseButton(event);
        }

        private void showContextMenu(MouseEvent event) {
            SalesPoint point = at(event.getPoint());
            if (point != null) showPointMenu(point, event.getPoint());
            else {
                JPopupMenu menu = new JPopupMenu();
                JMenuItem create = new JMenuItem("Создать точку здесь");
                create.addActionListener(ignored -> createPoint(new Rectangle(event.getX(), event.getY(), 150, 72)));
                menu.add(create);
                menu.show(MapCanvas.this, event.getX(), event.getY());
            }
        }

        private void setPoints(List<SalesPoint> values) { points = List.copyOf(values); repaint(); }
        private void setSelected(long id) { selectedId = id; repaint(); }
        private SalesPoint point(long id) { return points.stream().filter(p -> p.id() == id).findFirst().orElse(null); }
        private SalesPoint at(Point point) {
            for (int i = points.size() - 1; i >= 0; i--) if (bounds(points.get(i)).contains(point)) return points.get(i);
            return null;
        }
        private Rectangle bounds(SalesPoint point) { return new Rectangle(point.x(), point.y(), point.width(), point.height()); }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(new java.awt.Color(128, 128, 128, 35));
                for (int x = 20; x < getWidth(); x += 24) for (int y = 20; y < getHeight(); y += 24) g.fillOval(x, y, 2, 2);
                for (SalesPoint point : points) {
                    Rectangle r = bounds(point);
                    g.setColor(point.id() == selectedId ? new java.awt.Color(70, 145, 210, 100) : new java.awt.Color(80, 170, 130, 90));
                    g.fillRoundRect(r.x, r.y, r.width, r.height, 12, 12);
                    g.setColor(point.id() == selectedId ? new java.awt.Color(40, 110, 180) : new java.awt.Color(45, 125, 90));
                    g.drawRoundRect(r.x, r.y, r.width, r.height, 12, 12);
                    g.drawString(point.name(), r.x + 10, r.y + 23);
                }
                if (draft != null) {
                    g.setColor(new java.awt.Color(70, 145, 210, 65)); g.fill(draft);
                    g.setColor(new java.awt.Color(40, 110, 180)); g.draw(draft);
                }
            } finally { g.dispose(); }
        }
    }
}
