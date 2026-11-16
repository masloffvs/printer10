package com.setretailx.printer.ui;

import com.setretailx.printer.repository.SalesPointRepository;
import com.setretailx.printer.repository.SalesPointRepository.SalesPoint;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
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
        private SalesPoint hovered;
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
                @Override public void mouseMoved(MouseEvent event) {
                    SalesPoint next = at(event.getPoint());
                    if (hovered != next) {
                        hovered = next;
                        setCursor(next == null ? java.awt.Cursor.getDefaultCursor()
                                : java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
                        repaint();
                    }
                }

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
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Color panel = javax.swing.UIManager.getColor("Panel.background");
                if (panel == null) panel = new Color(246, 248, 250);
                g.setColor(panel);
                g.fillRect(0, 0, getWidth(), getHeight());

                Color grid = blend(panel, javax.swing.UIManager.getColor("Label.foreground"), 0.10f);
                g.setColor(grid);
                for (int x = 24; x < getWidth(); x += 28) {
                    for (int y = 24; y < getHeight(); y += 28) g.fillOval(x, y, 2, 2);
                }
                for (SalesPoint point : points) {
                    Rectangle bounds = bounds(point);
                    paintPoint(g, point, bounds, hovered != null && hovered.id() == point.id());
                }
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

            g.setColor(active ? accent : blend(accent, card, 0.34f));
            g.setStroke(new BasicStroke(active ? 1.8f : 1.1f));
            g.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, arc, arc);
            g.setColor(accent);
            g.fillRoundRect(bounds.x + 1, bounds.y + 1, 5, bounds.height - 2, arc, arc);

            int pad = Math.max(12, bounds.width / 14);
            int iconSize = Math.min(34, Math.max(24, bounds.height / 3));
            int iconX = bounds.x + pad;
            int iconY = bounds.y + Math.max(10, (bounds.height - iconSize) / 2 - 3);
            g.setColor(blend(accent, card, 0.84f));
            g.fillOval(iconX, iconY, iconSize, iconSize);
            paintStoreIcon(g, iconX, iconY, iconSize, accent);

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
