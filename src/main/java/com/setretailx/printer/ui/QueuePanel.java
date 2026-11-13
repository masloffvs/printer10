package com.setretailx.printer.ui;

import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.model.PriceType;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.TransferHandler;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.JTree;
import java.awt.BorderLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Hierarchical queue table grouped by format and price type. */
@SuppressWarnings("serial")
final class QueuePanel extends JPanel {
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Очередь печати");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new Object[]{"Товар", "Штрихкод", "ST", "Формат", "Тип цены", "Тираж", "Кол-во"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(tableModel);
    private final JLabel summary = new JLabel("В очереди: 0");
    private final QuadConsumer<List<Integer>, AppConfig.PrintFormat, PriceType, Integer> assignSelected;
    private final Consumer<List<Integer>> removeSelected;
    private final Runnable clearQueue;
    private final java.util.function.BiConsumer<List<Integer>, AppConfig.PrintFormat> moveSelected;
    private final Runnable printAssigned;
    private List<QueueItem> entries = List.of();
    private boolean synchronizing;

    QueuePanel(QuadConsumer<List<Integer>, AppConfig.PrintFormat, PriceType, Integer> assignSelected,
               Consumer<List<Integer>> removeSelected, Runnable clearQueue,
               java.util.function.BiConsumer<List<Integer>, AppConfig.PrintFormat> moveSelected,
               Runnable printAssigned) {
        super(new BorderLayout(8, 8));
        this.assignSelected = assignSelected;
        this.removeSelected = removeSelected;
        this.clearQueue = clearQueue;
        this.moveSelected = moveSelected;
        this.printAssigned = printAssigned;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setDragEnabled(true);
        tree.setDropMode(javax.swing.DropMode.ON_OR_INSERT);
        tree.setTransferHandler(new QueueTransferHandler());
        table.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !synchronizing) selectTreeRows(table.getSelectedRows());
        });
        tree.addTreeSelectionListener(event -> {
            if (!synchronizing) selectTableRows(selectedEntryIndexes());
        });
        MouseAdapter popup = new MouseAdapter() {
            private void show(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                if (event.getSource() == table) {
                    int row = table.rowAtPoint(event.getPoint());
                    if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
                } else {
                    TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                    if (path != null && !tree.isPathSelected(path)) tree.setSelectionPath(path);
                }
                createContextMenu().show((java.awt.Component) event.getSource(), event.getX(), event.getY());
            }
            @Override public void mousePressed(MouseEvent event) { show(event); }
            @Override public void mouseReleased(MouseEvent event) { show(event); }
        };
        table.addMouseListener(popup);
        tree.addMouseListener(popup);
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setPreferredSize(new java.awt.Dimension(210, 300));
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT,
                treeScroll, new JScrollPane(table));
        split.setResizeWeight(0.23);
        add(split, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(summary, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    void showEntries(List<QueueItem> values) {
        entries = List.copyOf(values);
        int[] selected = table.getSelectedRows();
        synchronizing = true;
        try {
            tableModel.setRowCount(0);
            for (QueueItem entry : entries) {
                tableModel.addRow(new Object[]{entry.name(), entry.barcode(), entry.stCode(),
                        entry.format() == null ? "Без формата" : entry.format().name(),
                        entry.format() == null ? "—" : entry.priceType().title(),
                        entry.amount() == null ? "—" : entry.amount(), entry.quantity()});
            }
            root.removeAllChildren();
            for (AppConfig.PrintFormat format : AppConfig.PRINT_FORMATS) {
                DefaultMutableTreeNode formatNode = child(root, format.name());
                for (PriceType type : PriceType.values()) child(formatNode, type.title());
            }
            child(root, "Без формата");
            for (int i = 0; i < entries.size(); i++) {
                QueueItem entry = entries.get(i);
                DefaultMutableTreeNode formatNode = child(root, entry.format() == null
                        ? "Без формата" : entry.format().name());
                DefaultMutableTreeNode typeNode = entry.format() == null
                        ? formatNode : child(formatNode, entry.priceType().title());
                typeNode.add(new DefaultMutableTreeNode(new QueueItemNode(i, entry.name())));
            }
            treeModel.reload();
            for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
            for (int row : selected) if (row >= 0 && row < table.getRowCount()) table.addRowSelectionInterval(row, row);
            selectTreeRows(table.getSelectedRows());
        } finally { synchronizing = false; }
    }

    void setSummary(String text) { summary.setText(text); }

    private void selectTreeRows(int[] rows) {
        synchronizing = true;
        try {
            tree.clearSelection();
            for (int row : rows) {
                if (row < 0 || row >= entries.size()) continue;
                TreePath path = findItemPath(root, row);
                if (path != null) tree.addSelectionPath(path);
            }
        } finally { synchronizing = false; }
    }

    private void selectTableRows(List<Integer> rows) {
        synchronizing = true;
        try {
            table.clearSelection();
            for (int row : rows) if (row >= 0 && row < table.getRowCount()) table.addRowSelectionInterval(row, row);
        } finally { synchronizing = false; }
    }

    private static TreePath findItemPath(DefaultMutableTreeNode node, int index) {
        if (node.getUserObject() instanceof QueueItemNode item && item.index() == index) return new TreePath(node.getPath());
        for (int i = 0; i < node.getChildCount(); i++) {
            TreePath path = findItemPath((DefaultMutableTreeNode) node.getChildAt(i), index);
            if (path != null) return path;
        }
        return null;
    }

    private JPopupMenu createContextMenu() {
        List<Integer> selected = selectedEntryIndexes();
        JPopupMenu menu = new JPopupMenu();
        JMenuItem assign = new JMenuItem("Назначить формат, тип цены и тираж…");
        assign.addActionListener(event -> showAssignmentDialog(selected));
        menu.add(assign);
        JMenu move = new JMenu("Перенести в формат");
        for (AppConfig.PrintFormat item : AppConfig.PRINT_FORMATS) {
            JMenuItem option = new JMenuItem(item.name());
            option.addActionListener(event -> moveSelected.accept(selected, item));
            move.add(option);
        }
        menu.add(move);
        JMenuItem remove = new JMenuItem("Удалить выбранное");
        remove.addActionListener(event -> removeSelected.accept(selected));
        menu.add(remove);
        JMenuItem clear = new JMenuItem("Очистить очередь…");
        clear.addActionListener(event -> clearQueue.run());
        menu.add(clear);
        JMenuItem print = new JMenuItem("Печать назначенных");
        print.addActionListener(event -> printAssigned.run());
        menu.add(print);
        return menu;
    }

    private void showAssignmentDialog(List<Integer> selected) {
        JComboBox<AppConfig.PrintFormat> formatChoice = new JComboBox<>(AppConfig.PRINT_FORMATS.toArray(AppConfig.PrintFormat[]::new));
        JComboBox<PriceType> typeChoice = new JComboBox<>(PriceType.values());
        JSpinner amountChoice = new JSpinner(new SpinnerNumberModel(1, 1, 999, 1));
        JPanel options = new JPanel(new java.awt.GridLayout(0, 2, 8, 6));
        options.add(new JLabel("Формат:")); options.add(formatChoice);
        options.add(new JLabel("Тип цены:")); options.add(typeChoice);
        options.add(new JLabel("Тираж:")); options.add(amountChoice);
        if (JOptionPane.showConfirmDialog(this, options, "Назначить выбранным", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            assignSelected.accept(selected, (AppConfig.PrintFormat) formatChoice.getSelectedItem(),
                    (PriceType) typeChoice.getSelectedItem(), (Integer) amountChoice.getValue());
        }
    }

    private List<Integer> selectedEntryIndexes() {
        List<Integer> indexes = new ArrayList<>();
        for (TreePath path : tree.getSelectionPaths() == null ? new TreePath[0] : tree.getSelectionPaths()) {
            collectIndexes((DefaultMutableTreeNode) path.getLastPathComponent(), indexes);
        }
        if (indexes.isEmpty()) for (int row : table.getSelectedRows()) if (row >= 0 && row < entries.size()) indexes.add(row);
        return indexes.stream().distinct().sorted().toList();
    }

    private static void collectIndexes(DefaultMutableTreeNode node, List<Integer> indexes) {
        if (node.getUserObject() instanceof QueueItemNode item) indexes.add(item.index());
        for (int i = 0; i < node.getChildCount(); i++) collectIndexes((DefaultMutableTreeNode) node.getChildAt(i), indexes);
    }

    private static DefaultMutableTreeNode child(DefaultMutableTreeNode parent, String name) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode current = (DefaultMutableTreeNode) parent.getChildAt(i);
            if (name.equals(current.getUserObject())) return current;
        }
        DefaultMutableTreeNode created = new DefaultMutableTreeNode(name);
        parent.add(created);
        return created;
    }

    private final class QueueTransferHandler extends TransferHandler {
        @Override protected Transferable createTransferable(javax.swing.JComponent component) {
            List<Integer> selected = selectedEntryIndexes();
            return selected.isEmpty() ? null : new StringSelection(selected.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(",")));
        }
        @Override public int getSourceActions(javax.swing.JComponent component) { return MOVE; }
        @Override public boolean canImport(TransferSupport support) {
            return support.isDrop() && support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)
                    && targetFormat(support) != null;
        }
        @Override public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                List<Integer> selected = java.util.Arrays.stream(((String) support.getTransferable()
                        .getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor)).split(","))
                        .map(Integer::parseInt).toList();
                moveSelected.accept(selected, targetFormat(support));
                return true;
            } catch (Exception ignored) { return false; }
        }
        private AppConfig.PrintFormat targetFormat(TransferSupport support) {
            TreePath path = tree.getPathForLocation(support.getDropLocation().getDropPoint().x,
                    support.getDropLocation().getDropPoint().y);
            if (path == null) return null;
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            while (node != null) {
                if (!(node.getUserObject() instanceof QueueItemNode)) {
                    String text = node.getUserObject().toString();
                    if (text.startsWith("Формат ")) return AppConfig.formatByKey(text.substring("Формат ".length()));
                }
                node = (DefaultMutableTreeNode) node.getParent();
            }
            return null;
        }
    }

    record QueueItem(String name, String barcode, String stCode, AppConfig.PrintFormat format,
                     PriceType priceType, Integer amount, int quantity) { }
    private record QueueItemNode(int index, String name) {
        @Override public String toString() { return name; }
    }
    @FunctionalInterface interface QuadConsumer<A, B, C, D> { void accept(A first, B second, C third, D fourth); }
}
