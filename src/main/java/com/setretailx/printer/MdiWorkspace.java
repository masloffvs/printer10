package com.setretailx.printer;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDesktopPane;
import javax.swing.JInternalFrame;
import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.BorderLayout;
import java.beans.PropertyVetoException;
import java.util.LinkedHashMap;
import java.util.Map;

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
            new WindowSpec("tasks", "Долгие задачи", "Задачи")
    };

    private final JDesktopPane desktop = new JDesktopPane();
    private final Map<String, JInternalFrame> frames = new LinkedHashMap<>();

    MdiWorkspace() {
        super(new BorderLayout());
        add(createToolBar(), BorderLayout.NORTH);
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
        desktop.add(frame);
        frames.put(key, frame);
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

    private JToolBar createToolBar() {
        JToolBar toolBar = new JToolBar("Окна");
        toolBar.setFloatable(false);
        for (WindowSpec spec : WINDOWS) {
            JButton button = new JButton(spec.shortTitle);
            button.setToolTipText(spec.title);
            button.addActionListener(event -> showWindow(spec.key));
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
        return toolBar;
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
