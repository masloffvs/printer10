package com.setretailx.printer.ui;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDesktopPane;
import javax.swing.JInternalFrame;
import javax.swing.JButton;
import javax.swing.Icon;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToolBar;
import javax.swing.JToggleButton;
import javax.swing.event.InternalFrameAdapter;
import javax.swing.event.InternalFrameEvent;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.beans.PropertyVetoException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.function.BiConsumer;

/** Owns the MDI desktop, its window menu, and window arrangement commands. */
@SuppressWarnings("serial")
final class MdiWorkspace extends JPanel {
    private static final WindowSpec[] WINDOWS = {
            new WindowSpec("scanner", "Единый сканер", "Сканер"),
            new WindowSpec("comparison", "Сверка ценников", "Сверка"),
            new WindowSpec("search", "Поиск товаров", "Поиск"),
            new WindowSpec("queue", "Очередь печати", "Очередь"),
            new WindowSpec("exchange", "Обмен с SetRetailX", "Обмен"),
            new WindowSpec("history", "История печати", "История"),
            new WindowSpec("tasks", "Долгие задачи", "Задачи"),
            new WindowSpec("storage", "Сканирование единичных коробок", "Единичные коробки"),
            new WindowSpec("storageAddresses", "Адреса хранения", "Адреса"),
            new WindowSpec("palletScanning", "Сканирование паллет", "Паллеты"),
            new WindowSpec("priceChanges", "История изменения цен", "История цен"),
            new WindowSpec("environment", "Обслуживание и администрирование", "Администрирование")
    };

    private final JDesktopPane desktop = new JDesktopPane();
    private final JToolBar toolBar = new JToolBar("Окна");
    private final Map<String, JInternalFrame> frames = new LinkedHashMap<>();
    private final Map<String, JInternalFrame> keyedDynamicFrames = new LinkedHashMap<>();
    private final Map<String, JToggleButton> toolbarButtons = new LinkedHashMap<>();
    private final Map<JInternalFrame, JToggleButton> dynamicToolbarButtons = new IdentityHashMap<>();
    private int dynamicFrameCount;
    private int dynamicToolbarIndex;
    private BiConsumer<String, Boolean> visibilityListener = (key, open) -> { };

    MdiWorkspace() {
        super(new BorderLayout());
        createToolBar();
        JScrollPane navigation = new JScrollPane(toolBar);
        navigation.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        navigation.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        navigation.setBorder(javax.swing.BorderFactory.createMatteBorder(0, 0, 0, 1,
                javax.swing.UIManager.getColor("Component.borderColor")));
        navigation.setPreferredSize(new java.awt.Dimension(210, 1));
        add(navigation, BorderLayout.WEST);
        add(desktop, BorderLayout.CENTER);
    }

    void addWindow(String key, JPanel content, int width, int height) {
        WindowSpec spec = specFor(key);
        JInternalFrame frame = new JInternalFrame(spec.title, true, true, true, true);
        frame.setDefaultCloseOperation(JInternalFrame.HIDE_ON_CLOSE);
        frame.setContentPane(content);
        frame.setSize(width, height);
        int offset = frames.size() * 28;
        frame.setLocation(18 + offset, 18 + offset);
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentShown(ComponentEvent event) {
                visibilityListener.accept(key, true);
                if (frame.isSelected()) updateActiveToolbar(frame);
            }
            @Override public void componentHidden(ComponentEvent event) {
                visibilityListener.accept(key, false);
                updateActiveToolbar(null);
            }
        });
        frame.addInternalFrameListener(new InternalFrameAdapter() {
            @Override public void internalFrameActivated(InternalFrameEvent event) { updateActiveToolbar(frame); }
            @Override public void internalFrameDeactivated(InternalFrameEvent event) { updateActiveToolbar(null); }
        });
        desktop.add(frame);
        frames.put(key, frame);
    }

    void addProductCard(String title, JPanel content) {
        JInternalFrame frame = new JInternalFrame(title, true, true, true, true);
        frame.setDefaultCloseOperation(JInternalFrame.DISPOSE_ON_CLOSE);
        frame.setContentPane(content);
        frame.setMinimumSize(new java.awt.Dimension(480, 420));
        frame.setMaximumSize(new java.awt.Dimension(680, 900));
        frame.setSize(620, 560);
        addDynamicToolbarButton(frame, title);
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                if (frame.getWidth() > 680) frame.setSize(680, frame.getHeight());
            }
        });
        int offset = (dynamicFrameCount++ % 12) * 26;
        frame.setLocation(26 + offset, 24 + offset);
        desktop.add(frame);
        frame.setVisible(true);
        frame.toFront();
        try { frame.setSelected(true); }
        catch (PropertyVetoException ignored) { }
    }

    void addDynamicWindow(String title, JPanel content, int width, int height) {
        JInternalFrame frame = new JInternalFrame(title, true, true, true, true);
        frame.setDefaultCloseOperation(JInternalFrame.DISPOSE_ON_CLOSE);
        frame.setContentPane(content);
        frame.setSize(width, height);
        addDynamicToolbarButton(frame, title);
        int offset = (dynamicFrameCount++ % 12) * 26;
        frame.setLocation(26 + offset, 24 + offset);
        desktop.add(frame);
        frame.setVisible(true);
        frame.toFront();
        try { frame.setSelected(true); }
        catch (PropertyVetoException ignored) { }
    }

    void showDynamicWindow(String key, String title, JPanel content, int width, int height) {
        JInternalFrame existing = keyedDynamicFrames.get(key);
        if (existing != null && !existing.isClosed()) {
            existing.setVisible(true);
            restore(existing);
            existing.toFront();
            try { existing.setSelected(true); }
            catch (PropertyVetoException ignored) { }
            return;
        }

        JInternalFrame frame = new JInternalFrame(title, true, true, true, true);
        frame.setDefaultCloseOperation(JInternalFrame.DISPOSE_ON_CLOSE);
        frame.setContentPane(content);
        frame.setSize(width, height);
        int offset = (dynamicFrameCount++ % 12) * 26;
        frame.setLocation(26 + offset, 24 + offset);
        frame.addInternalFrameListener(new InternalFrameAdapter() {
            @Override public void internalFrameClosed(InternalFrameEvent event) {
                keyedDynamicFrames.remove(key, frame);
                removeDynamicToolbarButton(frame);
            }
            @Override public void internalFrameActivated(InternalFrameEvent event) { updateActiveToolbar(frame); }
            @Override public void internalFrameDeactivated(InternalFrameEvent event) { updateActiveToolbar(null); }
        });
        addDynamicToolbarButton(frame, title);
        keyedDynamicFrames.put(key, frame);
        desktop.add(frame);
        frame.setVisible(true);
        frame.toFront();
        try { frame.setSelected(true); }
        catch (PropertyVetoException ignored) { }
    }

    void setWindowVisibilityListener(BiConsumer<String, Boolean> listener) {
        visibilityListener = listener == null ? (key, open) -> { } : listener;
        frames.forEach((key, frame) -> visibilityListener.accept(key, frame.isVisible()));
    }

    void showWindow(String key) {
        JInternalFrame frame = frames.get(key);
        if (frame == null) return;
        frame.setVisible(true);
        restore(frame);
        try {
            frame.setSelected(true);
        } catch (PropertyVetoException ignored) {
            // Another internal-frame listener may veto activation.
        }
        frame.toFront();
        updateActiveToolbar(frame);
    }

    JMenuBar createMenuBar(Runnable exitAction, Runnable aboutAction) {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("Файл");
        JMenuItem exit = new JMenuItem("Выход");
        exit.addActionListener(event -> exitAction.run());
        file.add(exit);

        JMenu view = new JMenu("Вид");
        for (WindowSpec spec : WINDOWS) {
            JMenuItem item = new JMenuItem(spec.title);
            item.addActionListener(event -> showWindow(spec.key));
            view.add(item);
        }

        JMenu windows = new JMenu("Окна");
        JMenuItem cascade = new JMenuItem("Каскадом");
        cascade.addActionListener(event -> cascadeWindows());
        windows.add(cascade);
        JMenuItem tile = new JMenuItem("Плиткой");
        tile.addActionListener(event -> tileWindows());
        windows.add(tile);
        windows.addSeparator();
        JMenu openWindows = new JMenu("Открытые окна");
        openWindows.addMenuListener(new MenuListener() {
            @Override public void menuSelected(MenuEvent event) { refreshOpenWindows(openWindows); }
            @Override public void menuDeselected(MenuEvent event) { }
            @Override public void menuCanceled(MenuEvent event) { }
        });
        windows.add(openWindows);

        JMenu help = new JMenu("Справка");
        JMenuItem about = new JMenuItem("О программе");
        about.addActionListener(event -> aboutAction.run());
        help.add(about);
        bar.add(file);
        bar.add(view);
        bar.add(windows);
        bar.add(help);
        return bar;
    }

    private void createToolBar() {
        toolBar.setOrientation(JToolBar.VERTICAL);
        toolBar.setFloatable(false);
        toolBar.setRollover(true);
        toolBar.setToolTipText("Навигация по разделам и открытым окнам");
        addNavigationGroup("ТОВАРЫ", "scanner", "comparison", "search", "queue");
        addNavigationGroup("СКЛАД", "storage", "storageAddresses", "palletScanning");
        addNavigationGroup("ИСТОРИЯ И ЗАДАЧИ", "history", "priceChanges", "tasks");
        addNavigationGroup("ОБМЕН И СЕРВИС", "exchange", "environment");
        dynamicToolbarIndex = toolBar.getComponentCount();
        toolBar.addSeparator();
        JButton cascade = new JButton("Каскад");
        cascade.setToolTipText("Расположить окна каскадом");
        cascade.addActionListener(event -> cascadeWindows());
        toolBar.add(cascade);
        JButton tile = new JButton("Плитка");
        tile.setToolTipText("Расположить окна плиткой");
        tile.addActionListener(event -> tileWindows());
        toolBar.add(tile);
    }

    private void addNavigationGroup(String title, String... keys) {
        JPanel group = new JPanel();
        group.setLayout(new javax.swing.BoxLayout(group, javax.swing.BoxLayout.Y_AXIS));
        group.setBorder(javax.swing.BorderFactory.createTitledBorder(title));
        group.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (String key : keys) {
            WindowSpec spec = specFor(key);
            JToggleButton button = new JToggleButton(spec.shortTitle, new NavigationIcon(key));
            button.setToolTipText(spec.title);
            button.setHorizontalAlignment(JToggleButton.LEFT);
            button.setIconTextGap(8);
            button.setMargin(new java.awt.Insets(5, 7, 5, 5));
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.setMaximumSize(new java.awt.Dimension(220, 48));
            button.addActionListener(event -> showWindow(spec.key));
            toolbarButtons.put(spec.key, button);
            group.add(button);
        }
        group.setMaximumSize(new java.awt.Dimension(204, group.getPreferredSize().height));
        toolBar.add(group);
    }

    private void addDynamicToolbarButton(JInternalFrame frame, String title) {
        String buttonTitle = title.length() > 24 ? title.substring(0, 21) + "…" : title;
        JToggleButton button = new JToggleButton(buttonTitle);
        button.setToolTipText(title);
        button.addActionListener(event -> {
            frame.setVisible(true);
            restore(frame);
            frame.toFront();
            try { frame.setSelected(true); }
            catch (PropertyVetoException ignored) { }
            updateActiveToolbar(frame);
        });
        dynamicToolbarButtons.put(frame, button);
        toolBar.add(button, dynamicToolbarIndex++);
        frame.addInternalFrameListener(new InternalFrameAdapter() {
            @Override public void internalFrameActivated(InternalFrameEvent event) { updateActiveToolbar(frame); }
            @Override public void internalFrameDeactivated(InternalFrameEvent event) { updateActiveToolbar(null); }
            @Override public void internalFrameClosed(InternalFrameEvent event) { removeDynamicToolbarButton(frame); }
        });
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentHidden(ComponentEvent event) { updateActiveToolbar(null); }
        });
    }

    private void removeDynamicToolbarButton(JInternalFrame frame) {
        JToggleButton button = dynamicToolbarButtons.remove(frame);
        if (button == null) return;
        toolBar.remove(button);
        dynamicToolbarIndex--;
        toolBar.revalidate();
        toolBar.repaint();
        updateActiveToolbar(null);
    }

    private void updateActiveToolbar(JInternalFrame active) {
        if (active == null) active = desktop.getSelectedFrame();
        if (active != null && !active.isVisible()) active = null;
        JInternalFrame selected = active;
        toolbarButtons.forEach((key, button) -> button.setSelected(selected != null && frames.get(key) == selected));
        dynamicToolbarButtons.forEach((frame, button) -> button.setSelected(selected != null && frame == selected));
    }

    private void refreshOpenWindows(JMenu menu) {
        menu.removeAll();
        int visibleCount = 0;
        for (WindowSpec spec : WINDOWS) {
            JInternalFrame frame = frames.get(spec.key);
            if (frame == null || !frame.isVisible()) continue;
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(spec.title, frame.isSelected());
            item.addActionListener(event -> showWindow(spec.key));
            menu.add(item);
            visibleCount++;
        }
        if (visibleCount == 0) {
            JMenuItem empty = new JMenuItem("Нет открытых окон");
            empty.setEnabled(false);
            menu.add(empty);
        }
    }

    private void cascadeWindows() {
        JInternalFrame[] visible = visibleFrames();
        if (visible.length == 0) return;
        int offset = 28;
        int width = Math.max(360, desktop.getWidth() - offset * (visible.length - 1) - 36);
        int height = Math.max(260, desktop.getHeight() - offset * (visible.length - 1) - 36);
        for (int i = 0; i < visible.length; i++) {
            JInternalFrame frame = visible[i];
            restore(frame);
            frame.setBounds(18 + i * offset, 18 + i * offset, width, height);
        }
        visible[visible.length - 1].toFront();
    }

    private void tileWindows() {
        JInternalFrame[] visible = visibleFrames();
        if (visible.length == 0) return;
        int columns = (int) Math.ceil(Math.sqrt(visible.length));
        int rows = (int) Math.ceil((double) visible.length / columns);
        int cellWidth = Math.max(1, desktop.getWidth() / columns);
        int cellHeight = Math.max(1, desktop.getHeight() / rows);
        for (int i = 0; i < visible.length; i++) {
            JInternalFrame frame = visible[i];
            restore(frame);
            frame.setBounds((i % columns) * cellWidth, (i / columns) * cellHeight, cellWidth, cellHeight);
        }
        visible[visible.length - 1].toFront();
    }

    private JInternalFrame[] visibleFrames() {
        return java.util.Arrays.stream(desktop.getAllFrames())
                .filter(JInternalFrame::isVisible)
                .toArray(JInternalFrame[]::new);
    }

    private void restore(JInternalFrame frame) {
        if (!frame.isIcon()) return;
        try {
            frame.setIcon(false);
        } catch (PropertyVetoException ignored) {
            // Keep the frame minimized if it refuses to restore.
        }
    }

    private static WindowSpec specFor(String key) {
        for (WindowSpec spec : WINDOWS) if (spec.key.equals(key)) return spec;
        throw new IllegalArgumentException("Unknown MDI window: " + key);
    }

    private static final class WindowSpec {
        private final String key;
        private final String title;
        private final String shortTitle;

        private WindowSpec(String key, String title, String shortTitle) {
            this.key = key;
            this.title = title;
            this.shortTitle = shortTitle;
        }
    }

    /** Small, font-independent pictograms for the left navigation. */
    @SuppressWarnings("serial")
    private static final class NavigationIcon implements Icon {
        private final String key;

        private NavigationIcon(String key) { this.key = key; }

        @Override public int getIconWidth() { return 20; }
        @Override public int getIconHeight() { return 20; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(iconColor());
                switch (key) {
                    case "scanner" -> {
                        g.drawRoundRect(x + 3, y + 4, 14, 11, 2, 2);
                        g.drawLine(x + 5, y + 8, x + 15, y + 8);
                        g.drawLine(x + 6, y + 12, x + 14, y + 12);
                    }
                    case "comparison", "priceChanges" -> {
                        g.drawLine(x + 3, y + 15, x + 3, y + 5);
                        g.drawLine(x + 3, y + 15, x + 17, y + 15);
                        g.drawLine(x + 5, y + 12, x + 9, y + 9);
                        g.drawLine(x + 9, y + 9, x + 12, y + 11);
                        g.drawLine(x + 12, y + 11, x + 17, y + 5);
                    }
                    case "search" -> {
                        g.drawOval(x + 3, y + 3, 10, 10);
                        g.drawLine(x + 12, y + 12, x + 18, y + 18);
                    }
                    case "queue", "tasks" -> {
                        g.drawLine(x + 4, y + 5, x + 17, y + 5);
                        g.drawLine(x + 4, y + 10, x + 17, y + 10);
                        g.drawLine(x + 4, y + 15, x + 17, y + 15);
                        g.fillOval(x + 1, y + 4, 2, 2);
                        g.fillOval(x + 1, y + 9, 2, 2);
                        g.fillOval(x + 1, y + 14, 2, 2);
                    }
                    case "storage", "palletScanning" -> {
                        g.drawRect(x + 3, y + 6, 14, 11);
                        g.drawLine(x + 3, y + 9, x + 10, y + 13);
                        g.drawLine(x + 17, y + 9, x + 10, y + 13);
                        g.drawLine(x + 10, y + 13, x + 10, y + 17);
                        if ("palletScanning".equals(key)) g.drawLine(x + 5, y + 3, x + 15, y + 3);
                    }
                    case "storageAddresses" -> {
                        g.drawOval(x + 5, y + 2, 10, 10);
                        g.drawLine(x + 5, y + 9, x + 10, y + 18);
                        g.drawLine(x + 15, y + 9, x + 10, y + 18);
                        g.fillOval(x + 8, y + 5, 4, 4);
                    }
                    case "history" -> {
                        g.drawOval(x + 3, y + 3, 14, 14);
                        g.drawLine(x + 10, y + 5, x + 10, y + 10);
                        g.drawLine(x + 10, y + 10, x + 14, y + 12);
                    }
                    case "exchange" -> {
                        g.drawLine(x + 3, y + 6, x + 16, y + 6);
                        g.drawLine(x + 13, y + 3, x + 16, y + 6);
                        g.drawLine(x + 16, y + 6, x + 13, y + 9);
                        g.drawLine(x + 17, y + 14, x + 4, y + 14);
                        g.drawLine(x + 7, y + 11, x + 4, y + 14);
                        g.drawLine(x + 4, y + 14, x + 7, y + 17);
                    }
                    default -> {
                        g.drawOval(x + 4, y + 4, 12, 12);
                        g.fillOval(x + 8, y + 8, 4, 4);
                    }
                }
            } finally {
                g.dispose();
            }
        }

        private Color iconColor() {
            return switch (key) {
                case "scanner" -> new Color(42, 123, 190);
                case "comparison", "priceChanges" -> new Color(135, 81, 170);
                case "search" -> new Color(56, 133, 97);
                case "queue", "history", "tasks" -> new Color(190, 125, 45);
                case "storage", "storageAddresses", "palletScanning" -> new Color(46, 139, 132);
                default -> new Color(105, 113, 125);
            };
        }
    }
}
