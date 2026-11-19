package com.setretailx.printer.ui;

import com.setretailx.printer.repository.SalesPointRepository;
import com.setretailx.printer.repository.SalesPointRepository.MapFixture;
import com.setretailx.printer.repository.SalesPointRepository.SalesPoint;

import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.FlowLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

/** Map-only view for creating and arranging virtual sales points. */
@SuppressWarnings("serial")
final class SalesPointMapPanel extends JPanel {
    private final SalesPointRepository repository;
    private final Consumer<SalesPoint> openEditor;
    private final Consumer<SalesPoint> openChanges;
    private final Runnable pointsChanged;
    private final MapCanvas map = new MapCanvas();

    SalesPointMapPanel(SalesPointRepository repository, Consumer<SalesPoint> openEditor,
                       Consumer<SalesPoint> openChanges, Runnable pointsChanged) {
        super(new java.awt.BorderLayout());
        this.repository = repository;
        this.openEditor = openEditor;
        this.openChanges = openChanges;
        this.pointsChanged = pointsChanged;
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 5));
        JButton zoomOut = new JButton("−");
        JButton zoomIn = new JButton("+");
        JButton rotateLeft = new JButton("⟲ Повернуть");
        JButton rotateRight = new JButton("⟳ Повернуть");
        JButton addShelf = new JButton("＋ Стеллаж");
        JButton addWalkway = new JButton("＋ Дорожка");
        JLabel hint = new JLabel("Колесо — масштаб · перетаскивание — перемещение · ПКМ — настройки");
        zoomOut.addActionListener(event -> map.setZoom(map.zoom / 1.15));
        zoomIn.addActionListener(event -> map.setZoom(map.zoom * 1.15));
        rotateLeft.addActionListener(event -> map.rotateView(-Math.PI / 12));
        rotateRight.addActionListener(event -> map.rotateView(Math.PI / 12));
        addShelf.addActionListener(event -> createFixture("shelf"));
        addWalkway.addActionListener(event -> createFixture("walkway"));
        toolbar.add(zoomOut); toolbar.add(zoomIn); toolbar.add(new JLabel("Вид:"));
        toolbar.add(rotateLeft); toolbar.add(rotateRight); toolbar.add(addShelf); toolbar.add(addWalkway); toolbar.add(hint);
        add(toolbar, java.awt.BorderLayout.NORTH);
        add(new JScrollPane(map), java.awt.BorderLayout.CENTER);
        refresh();
    }

    void refresh() {
        try {
            map.setPoints(repository.points());
            map.setFixtures(repository.fixtures());
            pointsChanged.run();
        } catch (Exception error) {
            JOptionPane.showMessageDialog(this, "Не удалось загрузить точки продаж: " + error.getMessage(),
                    "Карта торговых точек", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void createFixture(String kind) {
        String name = JOptionPane.showInputDialog(this, "Название объекта:",
                "walkway".equals(kind) ? "Проход" : "Стеллаж");
        if (name == null || name.isBlank()) return;
        try {
            repository.createFixture(kind, name, Math.max(30, map.getWidth() / 2 - 70),
                    Math.max(30, map.getHeight() / 2 - 25), "walkway".equals(kind) ? 220 : 140,
                    "walkway".equals(kind) ? 52 : 54, 0,
                    "walkway".equals(kind) ? "#55B98A" : "#3B82F6");
            refresh();
        } catch (Exception error) { showError("Не удалось создать объект карты", error); }
    }

    private void showError(String title, Exception error) {
        JOptionPane.showMessageDialog(this, title + ": " + error.getMessage(), "Карта торговых точек",
                JOptionPane.ERROR_MESSAGE);
    }

    private void createPoint(Rectangle bounds) {
        String name = JOptionPane.showInputDialog(this, "Название торговой точки:", "Новая точка");
        if (name == null || name.isBlank()) return;
        try {
            SalesPoint point = repository.createPoint(name, bounds.x, bounds.y, bounds.width, bounds.height);
            refresh();
            openEditor.accept(point);
        } catch (Exception error) {
            JOptionPane.showMessageDialog(this, "Не удалось создать точку: " + error.getMessage(),
                    "Карта торговых точек", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showContextMenu(SalesPoint point, Point location) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem edit = new JMenuItem("Открыть редактор точки");
        edit.addActionListener(event -> openEditor.accept(point));
        menu.add(edit);
        JMenuItem changes = new JMenuItem("Изменения цен за 24 часа…");
        changes.addActionListener(event -> openChanges.accept(point));
        menu.add(changes);
        menu.addSeparator();
        JMenuItem rename = new JMenuItem("Переименовать");
        rename.addActionListener(event -> {
            String name = JOptionPane.showInputDialog(this, "Название точки:", point.name());
            if (name == null || name.isBlank()) return;
            try {
                repository.renamePoint(point.id(), name);
                refresh();
            } catch (Exception error) {
                JOptionPane.showMessageDialog(this, "Не удалось переименовать точку: " + error.getMessage(),
                        "Карта торговых точек", JOptionPane.ERROR_MESSAGE);
            }
        });
        menu.add(rename);
        JMenuItem appearance = new JMenuItem("Цвет и значок…");
        appearance.addActionListener(event -> editPointAppearance(point));
        menu.add(appearance);
        JMenuItem delete = new JMenuItem("Удалить точку");
        delete.addActionListener(event -> {
            if (JOptionPane.showConfirmDialog(this, "Удалить точку «" + point.name() + "» и все её ценники?",
                    "Удаление точки", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            try {
                repository.deletePoint(point.id());
                refresh();
            } catch (Exception error) {
                JOptionPane.showMessageDialog(this, "Не удалось удалить точку: " + error.getMessage(),
                        "Карта торговых точек", JOptionPane.ERROR_MESSAGE);
            }
        });
        menu.add(delete);
        menu.show(map, location.x, location.y);
    }

    private void editPointAppearance(SalesPoint point) {
        Color[] selected = {Color.decode(point.color())};
        JButton color = new JButton("Выбрать цвет");
        color.setBackground(selected[0]);
        color.addActionListener(event -> {
            Color chosen = JColorChooser.showDialog(this, "Цвет торговой точки", selected[0]);
            if (chosen != null) { selected[0] = chosen; color.setBackground(chosen); }
        });
        javax.swing.JComboBox<String> icon = new javax.swing.JComboBox<>(new String[]{"store", "basket", "warehouse", "pin"});
        icon.setSelectedItem(point.icon());
        icon.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean selectedRow, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selectedRow, focus);
                setText(switch (String.valueOf(value)) {
                    case "basket" -> "Корзина";
                    case "warehouse" -> "Склад";
                    case "pin" -> "Метка";
                    default -> "Магазин";
                });
                return this;
            }
        });
        JPanel form = new JPanel(new java.awt.GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Цвет")); form.add(color);
        form.add(new JLabel("Значок")); form.add(icon);
        if (JOptionPane.showConfirmDialog(this, form, "Внешний вид точки",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            String hex = String.format("#%06X", selected[0].getRGB() & 0xFFFFFF);
            repository.updatePointAppearance(point.id(), hex, String.valueOf(icon.getSelectedItem()));
            refresh();
        } catch (Exception error) { showError("Не удалось изменить внешний вид точки", error); }
    }

    private final class MapCanvas extends JPanel {
        private List<SalesPoint> points = List.of();
        private List<MapFixture> fixtures = List.of();
        private Point press;
        private Point last;
        private SalesPoint moving;
        private MapFixture movingFixture;
        private Point pressWorld;
        private double zoom = 1.0;
        private double viewRotation;
        private SalesPoint hovered;
        private MapFixture hoveredFixture;
        private Rectangle draft;
        private boolean contextHandled;

        private MapCanvas() {
            setOpaque(true);
            setBackground(javax.swing.UIManager.getColor("Panel.background"));
            setPreferredSize(new java.awt.Dimension(1100, 720));
         setToolTipText("Перетаскивайте точку левой кнопкой для перемещения; правый щелчок — меню создания и настройки.");
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    contextHandled = false;
                    if (isContextClick(event)) {
                        showContextMenu(event);
                        contextHandled = true;
                        return;
                    }
                    if (!javax.swing.SwingUtilities.isLeftMouseButton(event)) return;
                    press = event.getPoint();
                    pressWorld = toWorld(press);
                    last = pressWorld;
                    moving = at(pressWorld);
                    movingFixture = moving == null ? fixtureAt(pressWorld) : null;
                    draft = null;
                }

                @Override public void mouseReleased(MouseEvent event) {
                    if (isContextClick(event)) {
                        if (!contextHandled) showContextMenu(event);
                        contextHandled = false;
                        return;
                    }
                    if (draft != null && draft.width >= 50 && draft.height >= 35) {
                        createPoint(draft);
                    } else if (moving != null) {
                        try {
                            SalesPoint moved = point(moving.id());
                            if (moved != null) repository.movePoint(moved.id(), moved.x(), moved.y());
                            refresh();
                        } catch (Exception error) {
                            JOptionPane.showMessageDialog(SalesPointMapPanel.this,
                                    "Не удалось переместить точку: " + error.getMessage(),
                                    "Карта торговых точек", JOptionPane.ERROR_MESSAGE);
                            refresh();
                        }
                    } else if (movingFixture != null) {
                        try {
                            MapFixture fixture = fixture(movingFixture.id());
                            if (fixture != null) repository.moveFixture(fixture.id(), fixture.x(), fixture.y());
                            refresh();
                        } catch (Exception error) { showError("Не удалось переместить объект", error); refresh(); }
                    }
                    press = null;
                    last = null;
                    moving = null;
                    movingFixture = null;
                    pressWorld = null;
                    draft = null;
                    repaint();
                }
            });
            addMouseWheelListener(event -> {
                double factor = event.getWheelRotation() < 0 ? 1.1 : 1 / 1.1;
                setZoom(zoom * factor);
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent event) {
                    Point world = toWorld(event.getPoint());
                    SalesPoint next = at(world);
                    MapFixture nextFixture = fixtureAt(world);
                    if (hovered != next || hoveredFixture != nextFixture) {
                        hovered = next;
                        hoveredFixture = nextFixture;
                        setCursor(next == null && nextFixture == null ? java.awt.Cursor.getDefaultCursor()
                                : java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
                        repaint();
                    }
                }

                @Override public void mouseDragged(MouseEvent event) {
                    if (press == null) return;
                    if (moving != null) {
                        Point world = toWorld(event.getPoint());
                        int dx = world.x - last.x;
                        int dy = world.y - last.y;
                        points = points.stream().map(point -> point.id() == moving.id()
                                ? new SalesPoint(point.id(), point.name(), Math.max(0, point.x() + dx),
                                Math.max(0, point.y() + dy), point.width(), point.height(), point.color(), point.icon()) : point).toList();
                        last = world;
                        repaint();
                    } else if (movingFixture != null) {
                        Point world = toWorld(event.getPoint());
                        int dx = world.x - last.x;
                        int dy = world.y - last.y;
                        fixtures = fixtures.stream().map(fixture -> fixture.id() == movingFixture.id()
                                ? new MapFixture(fixture.id(), fixture.kind(), fixture.name(), fixture.x() + dx,
                                fixture.y() + dy, fixture.width(), fixture.height(), fixture.rotation(), fixture.color())
                                : fixture).toList();
                        last = world;
                        repaint();
                    }
                }
            });
        }

        private boolean isContextClick(MouseEvent event) {
            return event.isPopupTrigger() || javax.swing.SwingUtilities.isRightMouseButton(event);
        }

        private void showContextMenu(MouseEvent event) {
            Point world = toWorld(event.getPoint());
            SalesPoint point = at(world);
            if (point != null) {
                SalesPointMapPanel.this.showContextMenu(point, event.getPoint());
                return;
            }
            MapFixture fixture = fixtureAt(world);
            if (fixture != null) {
                showFixtureMenu(fixture, event.getPoint());
                return;
            }
            JPopupMenu menu = new JPopupMenu();
            JMenuItem create = new JMenuItem("Создать точку здесь");
            create.addActionListener(ignored -> createPoint(new Rectangle(world.x, world.y, 150, 72)));
            menu.add(create);
            menu.show(MapCanvas.this, event.getX(), event.getY());
        }

        private void setPoints(List<SalesPoint> values) {
            points = List.copyOf(values);
            repaint();
        }

        private void setFixtures(List<MapFixture> values) { fixtures = List.copyOf(values); repaint(); }

        private void setZoom(double value) { zoom = Math.max(0.4, Math.min(2.6, value)); repaint(); }
        private void rotateView(double value) { viewRotation += value; repaint(); }

        private Point toWorld(Point screen) {
            try {
                AffineTransform transform = viewTransform();
                transform.invert();
                Point2D result = transform.transform(screen, null);
                return new Point((int) Math.round(result.getX()), (int) Math.round(result.getY()));
            } catch (java.awt.geom.NoninvertibleTransformException ignored) { return new Point(screen); }
        }

        private AffineTransform viewTransform() {
            AffineTransform transform = new AffineTransform();
            transform.translate(getWidth() / 2.0, getHeight() / 2.0);
            transform.rotate(viewRotation);
            transform.scale(zoom, zoom);
            transform.translate(-getWidth() / 2.0, -getHeight() / 2.0);
            return transform;
        }

        private MapFixture fixture(long id) {
            return fixtures.stream().filter(value -> value.id() == id).findFirst().orElse(null);
        }

        private MapFixture fixtureAt(Point world) {
            for (int index = fixtures.size() - 1; index >= 0; index--) {
                MapFixture fixture = fixtures.get(index);
                Point2D local = fixtureLocal(fixture, world);
                if (new Rectangle(-fixture.width() / 2, -fixture.height() / 2,
                        fixture.width(), fixture.height()).contains(local)) return fixture;
            }
            return null;
        }

        private Point2D fixtureLocal(MapFixture fixture, Point world) {
            AffineTransform transform = new AffineTransform();
            transform.translate(fixture.x() + fixture.width() / 2.0, fixture.y() + fixture.height() / 2.0);
            transform.rotate(Math.toRadians(fixture.rotation()));
            try { return transform.createInverse().transform(world, null); }
            catch (java.awt.geom.NoninvertibleTransformException ignored) { return new Point2D.Double(); }
        }

        private void showFixtureMenu(MapFixture fixture, Point location) {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem edit = new JMenuItem("Размер, поворот и цвет…");
            edit.addActionListener(event -> editFixture(fixture));
            menu.add(edit);
            JMenuItem delete = new JMenuItem("Удалить объект");
            delete.addActionListener(event -> {
                try { repository.deleteFixture(fixture.id()); refresh(); }
                catch (Exception error) { showError("Не удалось удалить объект", error); }
            });
            menu.add(delete);
            menu.show(MapCanvas.this, location.x, location.y);
        }

        private void editFixture(MapFixture fixture) {
            JTextField name = new JTextField(fixture.name());
            JTextField width = new JTextField(String.valueOf(fixture.width()));
            JTextField height = new JTextField(String.valueOf(fixture.height()));
            JTextField rotation = new JTextField(String.valueOf(Math.round(fixture.rotation())));
            JButton color = new JButton("Выбрать цвет");
            Color[] selectedColor = {Color.decode(fixture.color())};
            color.setBackground(selectedColor[0]);
            color.addActionListener(event -> {
                Color chosen = JColorChooser.showDialog(this, "Цвет объекта", selectedColor[0]);
                if (chosen != null) { selectedColor[0] = chosen; color.setBackground(chosen); }
            });
            JPanel form = new JPanel(new java.awt.GridLayout(0, 2, 6, 6));
            form.add(new JLabel("Название")); form.add(name);
            form.add(new JLabel("Ширина (px)")); form.add(width);
            form.add(new JLabel("Высота (px)")); form.add(height);
            form.add(new JLabel("Поворот (°)")); form.add(rotation);
            form.add(new JLabel("Цвет")); form.add(color);
            if (JOptionPane.showConfirmDialog(this, form, "Настройки объекта карты",
                    JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            try {
                String hex = String.format("#%06X", selectedColor[0].getRGB() & 0xFFFFFF);
                repository.updateFixture(fixture.id(), name.getText(), Integer.parseInt(width.getText().trim()),
                        Integer.parseInt(height.getText().trim()), Double.parseDouble(rotation.getText().trim()), hex);
                refresh();
            } catch (Exception error) { showError("Проверьте параметры объекта", error); }
        }

        private SalesPoint point(long id) {
            return points.stream().filter(value -> value.id() == id).findFirst().orElse(null);
        }

        private SalesPoint at(Point location) {
            for (int index = points.size() - 1; index >= 0; index--) {
                SalesPoint point = points.get(index);
                if (bounds(point).contains(location)) return point;
            }
            return null;
        }

        private Rectangle bounds(SalesPoint point) {
            return new Rectangle(point.x(), point.y(), point.width(), point.height());
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Color panel = javax.swing.UIManager.getColor("Panel.background");
                if (panel == null) panel = new Color(246, 248, 250);
                g.setColor(panel);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.transform(viewTransform());

                Color grid = blend(panel, javax.swing.UIManager.getColor("Label.foreground"), 0.10f);
                g.setColor(grid);
                for (int x = 24; x < getWidth(); x += 28) {
                    for (int y = 24; y < getHeight(); y += 28) g.fillOval(x, y, 2, 2);
                }
                for (SalesPoint point : points) {
                    Rectangle bounds = bounds(point);
                    paintPoint(g, point, bounds, hovered != null && hovered.id() == point.id());
                }
                for (MapFixture fixture : fixtures) paintFixture(g, fixture, movingFixture != null
                        && fixture.id() == movingFixture.id());
                if (draft != null) {
                    Color accent = accentColor();
                    g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 35));
                    g.fillRoundRect(draft.x, draft.y, draft.width, draft.height, 18, 18);
                    g.setColor(accent);
                    g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                            10f, new float[]{7f, 5f}, 0f));
                    g.draw(draft);
                }
            } finally {
                g.dispose();
            }
        }

        private void paintFixture(Graphics2D g, MapFixture fixture, boolean active) {
            Graphics2D object = (Graphics2D) g.create();
            try {
                object.translate(fixture.x() + fixture.width() / 2.0, fixture.y() + fixture.height() / 2.0);
                object.rotate(Math.toRadians(fixture.rotation()));
                Color color = Color.decode(fixture.color());
                int x = -fixture.width() / 2, y = -fixture.height() / 2;
                object.setColor(new Color(0, 0, 0, 45));
                object.fillRoundRect(x + 2, y + 4, fixture.width(), fixture.height(), 12, 12);
                object.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(),
                        "walkway".equals(fixture.kind()) ? 105 : 225));
                object.fillRoundRect(x, y, fixture.width(), fixture.height(), 12, 12);
                object.setColor(color.darker());
                object.setStroke(new BasicStroke(active ? 3f : 1.5f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND, 10f, "walkway".equals(fixture.kind()) ? new float[]{8f, 6f} : null, 0));
                object.drawRoundRect(x, y, fixture.width(), fixture.height(), 12, 12);
                if ("shelf".equals(fixture.kind())) {
                    object.setColor(new Color(255, 255, 255, 115));
                    object.setStroke(new BasicStroke(1f));
                    int rowGap = Math.max(9, fixture.height() / 4);
                    for (int row = y + rowGap; row < y + fixture.height() - 4; row += rowGap) {
                        object.drawLine(x + 8, row, x + fixture.width() - 8, row);
                    }
                    int columnGap = Math.max(24, fixture.width() / 6);
                    for (int column = x + columnGap; column < x + fixture.width() - 4; column += columnGap) {
                        object.drawLine(column, y + 5, column, y + fixture.height() - 5);
                    }
                }
                object.setColor(new Color(255, 255, 255, 225));
                object.setFont(getFont().deriveFont(java.awt.Font.BOLD, 12f));
                FontMetrics metrics = object.getFontMetrics();
                String label = fixture.name().length() > 24 ? fixture.name().substring(0, 23) + "…" : fixture.name();
                object.setColor(new Color(20, 28, 38, 210));
                object.fillRoundRect(-metrics.stringWidth(label) / 2 - 5, -9,
                        metrics.stringWidth(label) + 10, 18, 8, 8);
                object.setColor(Color.WHITE);
                object.drawString(label, -metrics.stringWidth(label) / 2, metrics.getAscent() / 2);
            } finally { object.dispose(); }
        }

        private void paintPoint(Graphics2D g, SalesPoint point, Rectangle bounds, boolean active) {
            int arc = Math.min(20, Math.max(12, bounds.height / 3));
            Color accent = accentColor();
            Color foreground = javax.swing.UIManager.getColor("Label.foreground");
            if (foreground == null) foreground = new Color(40, 52, 64);
            Color card = javax.swing.UIManager.getColor("Panel.background");
            if (card == null) card = Color.WHITE;

            g.setColor(new Color(20, 35, 50, active ? 34 : 20));
            g.fillRoundRect(bounds.x + 1, bounds.y + 4, bounds.width, bounds.height, arc, arc);
            g.setPaint(new GradientPaint(bounds.x, bounds.y, blend(card, Color.WHITE, 0.18f),
                    bounds.x, bounds.y + bounds.height, card));
            g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, arc, arc);

            Color pointColor = Color.decode(point.color());
            g.setColor(active ? pointColor : blend(pointColor, card, 0.34f));
            g.setStroke(new BasicStroke(active ? 1.8f : 1.1f));
            g.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, arc, arc);
            g.setColor(pointColor);
            g.fillRoundRect(bounds.x + 1, bounds.y + 1, 5, bounds.height - 2, arc, arc);

            int pad = Math.max(12, bounds.width / 14);
            int iconSize = Math.min(34, Math.max(24, bounds.height / 3));
            int iconX = bounds.x + pad;
            int iconY = bounds.y + Math.max(10, (bounds.height - iconSize) / 2 - 3);
            g.setColor(blend(pointColor, card, 0.84f));
            g.fillOval(iconX, iconY, iconSize, iconSize);
            paintPointIcon(g, iconX, iconY, iconSize, pointColor, point.icon());

            int textX = iconX + iconSize + Math.max(9, pad / 2);
            int textWidth = Math.max(20, bounds.x + bounds.width - pad - textX);
            int titleSize = Math.min(16, Math.max(12, bounds.height / 6));
            g.setColor(foreground);
            g.setFont(getFont().deriveFont(java.awt.Font.BOLD, (float) titleSize));
            FontMetrics titleMetrics = g.getFontMetrics();
            int titleBaseline = bounds.y + Math.min(bounds.height - 9, Math.max(titleSize + 15,
                    bounds.height / 2 - 1));
            g.drawString(ellipsize(point.name(), titleMetrics, textWidth), textX, titleBaseline);

            if (bounds.height >= 64) {
                g.setFont(getFont().deriveFont(java.awt.Font.PLAIN, Math.max(10f, titleSize - 3f)));
                g.setColor(blend(foreground, card, 0.42f));
                String meta = "ТОРГОВАЯ ТОЧКА  ·  №" + point.id();
                FontMetrics metaMetrics = g.getFontMetrics();
                int metaBaseline = Math.min(bounds.y + bounds.height - 12, titleBaseline + titleMetrics.getHeight() - 1);
                g.drawString(ellipsize(meta, metaMetrics, textWidth), textX, metaBaseline);
            }

            if (bounds.height >= 92 && bounds.width >= 190) {
                int chipH = 22;
                int chipY = bounds.y + bounds.height - chipH - 10;
                g.setColor(blend(accent, card, 0.88f));
                g.fillRoundRect(textX, chipY, Math.min(textWidth, 132), chipH, 11, 11);
                g.setColor(accent);
                g.setFont(getFont().deriveFont(java.awt.Font.BOLD, 10f));
                String action = "ОТКРЫТЬ ЦЕННИКИ  ›";
                g.drawString(ellipsize(action, g.getFontMetrics(), Math.min(textWidth - 12, 120)),
                        textX + 8, chipY + 15);
            }
        }

        private void paintStoreIcon(Graphics2D g, int x, int y, int size, Color accent) {
            int inset = Math.max(6, size / 5);
            int left = x + inset;
            int width = size - inset * 2;
            int roofY = y + Math.max(5, size / 5);
            g.setColor(accent);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(left, roofY + 3, left + width, roofY + 3);
            g.drawLine(left + 2, roofY + 2, left + 4, roofY - 2);
            g.drawLine(left + 4, roofY - 2, left + width - 4, roofY - 2);
            g.drawLine(left + width - 4, roofY - 2, left + width - 2, roofY + 2);
            g.drawRoundRect(left + 2, roofY + 4, width - 4, size - inset - (roofY - y), 3, 3);
            g.drawLine(left + width / 2, roofY + 5, left + width / 2, y + size - inset);
            g.drawLine(left + 3, roofY + 9, left + width - 3, roofY + 9);
        }

        private void paintPointIcon(Graphics2D g, int x, int y, int size, Color accent, String icon) {
            if ("store".equals(icon)) { paintStoreIcon(g, x, y, size, accent); return; }
            int inset = Math.max(6, size / 4), left = x + inset, top = y + inset;
            int width = size - inset * 2, height = size - inset * 2;
            g.setColor(accent);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (icon) {
                case "basket" -> {
                    g.drawLine(left + 1, top + 4, left + width - 1, top + 4);
                    g.drawLine(left + 3, top + 4, left + 5, top + height - 1);
                    g.drawLine(left + width - 3, top + 4, left + width - 5, top + height - 1);
                    g.drawLine(left + 5, top + height - 1, left + width - 5, top + height - 1);
                    g.drawLine(left + width / 3, top + 6, left + width / 3, top + height - 2);
                    g.drawLine(left + width * 2 / 3, top + 6, left + width * 2 / 3, top + height - 2);
                }
                case "warehouse" -> {
                    g.drawLine(left, top + 5, left + width / 2, top);
                    g.drawLine(left + width / 2, top, left + width, top + 5);
                    g.drawRect(left + 2, top + 5, width - 4, height - 5);
                    g.drawRect(left + width / 3, top + height / 2, width / 3, height / 2);
                }
                case "pin" -> {
                    g.drawOval(left + width / 4, top, width / 2, height / 2);
                    g.drawLine(left + width / 4, top + height / 3, left + width / 2, top + height);
                    g.drawLine(left + width * 3 / 4, top + height / 3, left + width / 2, top + height);
                    g.fillOval(left + width * 2 / 5, top + height / 6, Math.max(2, width / 5), Math.max(2, width / 5));
                }
                default -> paintStoreIcon(g, x, y, size, accent);
            }
        }

        private String ellipsize(String text, FontMetrics metrics, int width) {
            if (metrics.stringWidth(text) <= width) return text;
            String suffix = "…";
            int end = text.length();
            while (end > 0 && metrics.stringWidth(text.substring(0, end) + suffix) > width) end--;
            return end == 0 ? "" : text.substring(0, end) + suffix;
        }

        private Color accentColor() {
            Color accent = javax.swing.UIManager.getColor("Component.focusColor");
            if (accent == null) accent = javax.swing.UIManager.getColor("nimbusFocus");
            if (accent == null) accent = new Color(47, 126, 105);
            return accent;
        }

        private Color blend(Color first, Color second, float secondWeight) {
            if (first == null) first = new Color(40, 52, 64);
            if (second == null) second = Color.WHITE;
            float weight = Math.max(0f, Math.min(1f, secondWeight));
            float firstWeight = 1f - weight;
            return new Color(Math.round(first.getRed() * firstWeight + second.getRed() * weight),
                    Math.round(first.getGreen() * firstWeight + second.getGreen() * weight),
                    Math.round(first.getBlue() * firstWeight + second.getBlue() * weight));
        }
    }
}
