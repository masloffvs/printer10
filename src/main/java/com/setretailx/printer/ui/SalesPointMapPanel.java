package com.setretailx.printer.ui;

import com.setretailx.printer.repository.SalesPointRepository;
import com.setretailx.printer.repository.SalesPointRepository.SalesPoint;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

/** Map-only view for creating and arranging virtual sales points. */
@SuppressWarnings("serial")
final class SalesPointMapPanel extends JPanel {
    private final SalesPointRepository repository;
    private final Consumer<SalesPoint> openEditor;
    private final Runnable pointsChanged;
    private final MapCanvas map = new MapCanvas();

    SalesPointMapPanel(SalesPointRepository repository, Consumer<SalesPoint> openEditor, Runnable pointsChanged) {
        super(new java.awt.BorderLayout());
        this.repository = repository;
        this.openEditor = openEditor;
        this.pointsChanged = pointsChanged;
        add(new JScrollPane(map), java.awt.BorderLayout.CENTER);
        refresh();
    }

    void refresh() {
        try {
            map.setPoints(repository.points());
            pointsChanged.run();
        } catch (Exception error) {
            JOptionPane.showMessageDialog(this, "Не удалось загрузить точки продаж: " + error.getMessage(),
                    "Карта торговых точек", JOptionPane.ERROR_MESSAGE);
        }
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

    private final class MapCanvas extends JPanel {
        private List<SalesPoint> points = List.of();
        private Point press;
        private Point last;
        private SalesPoint moving;
        private Rectangle draft;
        private boolean contextHandled;

        private MapCanvas() {
            setOpaque(true);
            setBackground(javax.swing.UIManager.getColor("Panel.background"));
            setPreferredSize(new java.awt.Dimension(1100, 720));
            setToolTipText("Нарисуйте прямоугольник, чтобы добавить точку; щёлкните точку для редактора, перетащите для перемещения, правый щелчок — меню.");
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
                    last = press;
                    moving = at(press);
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
                    } else if (moving != null && press != null && press.distance(event.getPoint()) < 4) {
                        openEditor.accept(point(moving.id()));
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
                    }
                    press = null;
                    last = null;
                    moving = null;
                    draft = null;
                    repaint();
                }
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override public void mouseDragged(MouseEvent event) {
                    if (press == null) return;
                    if (moving != null) {
                        int dx = event.getX() - last.x;
                        int dy = event.getY() - last.y;
                        points = points.stream().map(point -> point.id() == moving.id()
                                ? new SalesPoint(point.id(), point.name(), Math.max(0, point.x() + dx),
                                Math.max(0, point.y() + dy), point.width(), point.height()) : point).toList();
                        last = event.getPoint();
                        repaint();
                    } else {
                        draft = new Rectangle(Math.min(press.x, event.getX()), Math.min(press.y, event.getY()),
                                Math.abs(event.getX() - press.x), Math.abs(event.getY() - press.y));
                        repaint();
                    }
                }
            });
        }

        private boolean isContextClick(MouseEvent event) {
            return event.isPopupTrigger() || javax.swing.SwingUtilities.isRightMouseButton(event);
        }

        private void showContextMenu(MouseEvent event) {
            SalesPoint point = at(event.getPoint());
            if (point != null) {
                SalesPointMapPanel.this.showContextMenu(point, event.getPoint());
                return;
            }
            JPopupMenu menu = new JPopupMenu();
            JMenuItem create = new JMenuItem("Создать точку здесь");
            create.addActionListener(ignored -> createPoint(new Rectangle(event.getX(), event.getY(), 150, 72)));
            menu.add(create);
            menu.show(MapCanvas.this, event.getX(), event.getY());
        }

        private void setPoints(List<SalesPoint> values) {
            points = List.copyOf(values);
            repaint();
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
                g.setColor(new Color(128, 128, 128, 35));
                for (int x = 20; x < getWidth(); x += 24) {
                    for (int y = 20; y < getHeight(); y += 24) g.fillOval(x, y, 2, 2);
                }
                for (SalesPoint point : points) {
                    Rectangle bounds = bounds(point);
                    g.setColor(new Color(80, 170, 130, 90));
                    g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 12, 12);
                    g.setColor(new Color(45, 125, 90));
                    g.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 12, 12);
                    g.drawString(point.name(), bounds.x + 10, bounds.y + 23);
                }
                if (draft != null) {
                    g.setColor(new Color(70, 145, 210, 65));
                    g.fill(draft);
                    g.setColor(new Color(40, 110, 180));
                    g.draw(draft);
                }
            } finally {
                g.dispose();
            }
        }
    }
}
