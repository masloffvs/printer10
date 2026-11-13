package com.setretailx.printer.ui;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDesktopPane;
import javax.swing.JInternalFrame;
import javax.swing.JButton;
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
            new WindowSpec("storage", "Коробки и товары", "Коробки"),
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
    private int dynamicToolbarIndex = WINDOWS.length;
    private BiConsumer<String, Boolean> visibilityListener = (key, open) -> { };

    MdiWorkspace() {
        super(new BorderLayout());
        createToolBar();
        JScrollPane navigation = new JScrollPane(toolBar);
        navigation.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        navigation.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        navigation.setBorder(javax.swing.BorderFactory.createMatteBorder(0, 0, 0, 1,
                javax.swing.UIManager.getColor("Component.borderColor")));
        navigation.setPreferredSize(new java.awt.Dimension(164, 1));
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
        for (WindowSpec spec : WINDOWS) {
            JToggleButton button = new JToggleButton(spec.shortTitle);
            button.setToolTipText(spec.title);
            button.addActionListener(event -> showWindow(spec.key));
            toolbarButtons.put(spec.key, button);
            toolBar.add(button);
        }
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
}
