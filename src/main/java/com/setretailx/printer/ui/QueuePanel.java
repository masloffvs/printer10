package com.setretailx.printer.ui;

import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.model.PriceType;

import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTree;
import javax.swing.SpinnerNumberModel;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Hierarchical queue grouped by format and normalized price type. */
@SuppressWarnings("serial")
final class QueuePanel extends JPanel {
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Очередь печати");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final JLabel summary = new JLabel("В очереди: 0");
    private final QuadConsumer<List<Integer>, AppConfig.PrintFormat, PriceType, Integer> assignSelected;
    private final Consumer<List<Integer>> removeSelected;
    private final Runnable clearQueue;
    private final java.util.function.BiConsumer<List<Integer>, AppConfig.PrintFormat> moveSelected;

    QueuePanel(QuadConsumer<List<Integer>, AppConfig.PrintFormat, PriceType, Integer> assignSelected,
               Consumer<List<Integer>> removeSelected, Runnable clearQueue,
               java.util.function.BiConsumer<List<Integer>, AppConfig.PrintFormat> moveSelected) {
        super(new BorderLayout(8, 8));
        this.assignSelected = assignSelected;
        this.removeSelected = removeSelected;
        this.clearQueue = clearQueue;
        this.moveSelected = moveSelected;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON_OR_INSERT);
        tree.setTransferHandler(new QueueTransferHandler());
        tree.addMouseListener(new MouseAdapter() {
            private void showMenu(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path == null) return;
                if (!tree.isPathSelected(path)) tree.setSelectionPath(path);
                createContextMenu().show(tree, event.getX(), event.getY());
            }
            @Override public void mousePressed(MouseEvent event) { showMenu(event); }
            @Override public void mouseReleased(MouseEvent event) { showMenu(event); }
        });
        add(new JScrollPane(tree), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(summary, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    void showEntries(List<QueueItem> entries) {
        root.removeAllChildren();
        for (AppConfig.PrintFormat format : AppConfig.PRINT_FORMATS) {
            DefaultMutableTreeNode formatNode = child(root, format.name());
            for (PriceType type : PriceType.values()) child(formatNode, type.title());
        }
        child(root, "Без формата");
        for (int i = 0; i < entries.size(); i++) {
            QueueItem entry = entries.get(i);
            String label = (i + 1) + ". " + entry.name() + " · " + entry.barcode()
                    + " · ST " + entry.stCode() + " · тираж "
                    + (entry.amount() == null ? "—" : entry.amount()) + " · кол-во " + entry.quantity();
            DefaultMutableTreeNode formatNode = child(root, entry.format() == null
                    ? "Без формата" : entry.format().name());
            DefaultMutableTreeNode typeNode = entry.format() == null
                    ? formatNode : child(formatNode, entry.priceType().title());
            DefaultMutableTreeNode itemNode = new DefaultMutableTreeNode(
                    new QueueItemNode(i, label, entry.format(), entry.priceType()));
            typeNode.add(itemNode);
        }
        model.reload();
        for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
    }

    void setSummary(String text) { summary.setText(text); }

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
        print.setEnabled(false);
        print.setToolTipText("Отправка заданий на печать в Java пока не подключена.");
        menu.add(print);
        return menu;
    }

    private void showAssignmentDialog(List<Integer> selected) {
        JComboBox<AppConfig.PrintFormat> formatChoice = new JComboBox<>(
                AppConfig.PRINT_FORMATS.toArray(AppConfig.PrintFormat[]::new));
        JComboBox<PriceType> typeChoice = new JComboBox<>(PriceType.values());
        JSpinner amountChoice = new JSpinner(new SpinnerNumberModel(1, 1, 999, 1));
        JPanel options = new JPanel(new java.awt.GridLayout(0, 2, 8, 6));
        options.add(new JLabel("Формат:"));
        options.add(formatChoice);
        options.add(new JLabel("Тип цены:"));
        options.add(typeChoice);
        options.add(new JLabel("Тираж:"));
        options.add(amountChoice);
        if (JOptionPane.showConfirmDialog(this, options, "Назначить выбранным",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            assignSelected.accept(selected, (AppConfig.PrintFormat) formatChoice.getSelectedItem(),
                    (PriceType) typeChoice.getSelectedItem(), (Integer) amountChoice.getValue());
        }
    }

    private List<Integer> selectedEntryIndexes() {
        List<Integer> indexes = new ArrayList<>();
        for (TreePath path : tree.getSelectionPaths() == null ? new TreePath[0] : tree.getSelectionPaths()) {
            Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (value instanceof QueueItemNode item) indexes.add(item.index());
            else collectIndexes((DefaultMutableTreeNode) path.getLastPathComponent(), indexes);
        }
        return indexes.stream().distinct().sorted().toList();
    }

    private static void collectIndexes(DefaultMutableTreeNode node, List<Integer> indexes) {
        if (node.getUserObject() instanceof QueueItemNode item) indexes.add(item.index());
        for (int i = 0; i < node.getChildCount(); i++)
            collectIndexes((DefaultMutableTreeNode) node.getChildAt(i), indexes);
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
            return selected.isEmpty() ? null : new StringSelection(selected.stream()
                    .map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
        }

        @Override public int getSourceActions(javax.swing.JComponent component) { return MOVE; }

        @Override public boolean canImport(TransferSupport support) {
            if (!support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)
                    || !support.isDrop()) return false;
            return targetFormat(support) != null;
        }

        @Override public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            AppConfig.PrintFormat target = targetFormat(support);
            try {
                String raw = (String) support.getTransferable().getTransferData(
                        java.awt.datatransfer.DataFlavor.stringFlavor);
                List<Integer> selected = java.util.Arrays.stream(raw.split(","))
                        .map(Integer::parseInt).toList();
                moveSelected.accept(selected, target);
                return true;
            } catch (Exception ignored) { return false; }
        }

        private AppConfig.PrintFormat targetFormat(TransferSupport support) {
            TreePath path = tree.getPathForLocation(support.getDropLocation().getDropPoint().x,
                    support.getDropLocation().getDropPoint().y);
            if (path == null) return null;
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            while (node != null) {
                if (node.getUserObject() instanceof QueueItemNode) {
                    node = (DefaultMutableTreeNode) node.getParent();
                    continue;
                }
                String text = node.getUserObject().toString();
                if (text.startsWith("Формат ")) return AppConfig.formatByKey(text.substring("Формат ".length()));
                node = (DefaultMutableTreeNode) node.getParent();
            }
            return null;
        }
    }

    record QueueItem(String name, String barcode, String stCode, AppConfig.PrintFormat format,
                     PriceType priceType, Integer amount, int quantity) { }

    private record QueueItemNode(int index, String label, AppConfig.PrintFormat format, PriceType priceType) {
        @Override public String toString() { return label; }
    }

    @FunctionalInterface
    interface QuadConsumer<A, B, C, D> { void accept(A first, B second, C third, D fourth); }
}
