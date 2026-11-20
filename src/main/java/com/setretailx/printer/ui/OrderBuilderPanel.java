package com.setretailx.printer.ui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.setretailx.printer.model.Product;
import com.setretailx.printer.repository.OrderBuilderRepository;
import com.setretailx.printer.repository.OrderBuilderRepository.Candidate;
import com.setretailx.printer.repository.OrderBuilderRepository.Group;
import com.setretailx.printer.repository.OrderBuilderRepository.Order;
import com.setretailx.printer.repository.OrderBuilderRepository.Position;
import com.setretailx.printer.repository.OrderBuilderRepository.ProductData;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterJob;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent order tree: ordinary lines, request nodes, and their candidate products. */
@SuppressWarnings("serial")
final class OrderBuilderPanel extends JPanel {
    private final OrderBuilderRepository repository;
    private final JComboBox<Order> orders = new JComboBox<>();
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Заказ");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    private final JLabel status = new JLabel("Заказы сохраняются автоматически.");
    private Order current;
    private boolean loading;

    OrderBuilderPanel(OrderBuilderRepository repository) {
        super(new BorderLayout(8, 8));
        this.repository = repository;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        orders.setRenderer(new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
                    Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setText(value instanceof Order order ? order.name() : "Нет заказов");
                return this;
            }
        });
        orders.addActionListener(event -> {
            if (!loading && orders.getSelectedItem() instanceof Order order) open(order);
        });
        JButton create = new JButton("Новый заказ");
        create.addActionListener(event -> createOrder());
        JButton rename = new JButton("Переименовать");
        rename.addActionListener(event -> renameOrder());
        JButton delete = new JButton("Удалить заказ");
        delete.addActionListener(event -> deleteOrder());
        JPanel orderBar = new JPanel(new BorderLayout(8, 0));
        orderBar.add(new JLabel("Заказ:"), BorderLayout.WEST);
        orderBar.add(orders, BorderLayout.CENTER);
        JPanel orderActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 5, 0));
        orderActions.add(create);
        orderActions.add(rename);
        orderActions.add(delete);
        orderBar.add(orderActions, BorderLayout.EAST);
        add(orderBar, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new OrderTreeRenderer());
        tree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setLargeModel(true);
        add(new JScrollPane(tree), BorderLayout.CENTER);

        JButton group = new JButton("Новая группа…");
        group.addActionListener(event -> addGroup());
        JButton need = new JButton("Позиция-потребность…");
        need.addActionListener(event -> addNeed());
        JButton comment = new JButton("Комментарий…");
        comment.addActionListener(event -> editComment());
        JButton move = new JButton("В группу…");
        move.addActionListener(event -> moveSelectedToGroup());
        JButton remove = new JButton("Удалить выбранное");
        remove.addActionListener(event -> removeSelected());
        JButton print = new JButton("Печать списка заказа");
        print.addActionListener(event -> printOrder());
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 6, 0));
        actions.add(group);
        actions.add(need);
        actions.add(comment);
        actions.add(move);
        actions.add(remove);
        actions.add(print);
        JPanel footer = new JPanel(new BorderLayout(6, 4));
        footer.add(actions, BorderLayout.NORTH);
        footer.add(status, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        refreshOrders(null);
    }

    void addProducts(List<Product> products) {
        if (products == null || products.isEmpty()) return;
        if (current == null) createOrder();
        if (current == null) return;
        Position selectedNeed = selectedNeed();
        String[] choices = selectedNeed == null
                ? new String[]{"Прямыми позициями", "Создать потребность и добавить кандидатами"}
                : new String[]{"Прямыми позициями", "Кандидатами к выбранной потребности", "Создать потребность и добавить кандидатами"};
        int defaultChoice = selectedNeed == null ? 0 : 1;
        int choice = JOptionPane.showOptionDialog(this,
                "Куда добавить выбранные товары (" + products.size() + ")?",
                "Добавление в сборку заказа", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, choices, choices[defaultChoice]);
        if (choice < 0) return;
        if (selectedNeed == null && choice == 1 || selectedNeed != null && choice == 2) {
            String title = JOptionPane.showInputDialog(this, "Что требуется найти?", "Новая потребность",
                    JOptionPane.QUESTION_MESSAGE);
            if (title == null || title.isBlank()) return;
            Position need = new Position(UUID.randomUUID().toString(), selectedGroupId(), title.trim(), "", null, List.of());
            List<Position> positions = new ArrayList<>(current.positions());
            positions.add(need);
            current = current.with(current.name(), current.groups(), positions);
            selectedNeed = need;
            choice = 1;
        }
        List<Position> positions = new ArrayList<>(current.positions());
        if (choice == 1 && selectedNeed != null) {
            Position target = selectedNeed;
            for (Product product : products) {
                List<Candidate> candidates = new ArrayList<>(target.candidates());
                candidates.add(new Candidate(UUID.randomUUID().toString(), ProductData.from(product), ""));
                target = new Position(target.id(), target.groupId(), target.title(), target.comment(), null, candidates);
            }
            replacePosition(positions, target);
        } else {
            for (Product product : products) {
                String groupId = ensureCategoryGroup(product, current.groups());
                positions.add(new Position(UUID.randomUUID().toString(), groupId, product.name(), "",
                        ProductData.from(product), List.of()));
            }
        }
        save(current.with(current.name(), current.groups(), positions));
        status.setText("Добавлено товаров в сборку заказа: " + products.size());
    }

    private void refreshOrders(String selectId) {
        try {
            List<Order> values = repository.list();
            loading = true;
            orders.setModel(new DefaultComboBoxModel<>(values.toArray(Order[]::new)));
            Order selected = values.stream().filter(order -> order.id().equals(selectId)).findFirst()
                    .orElse(values.isEmpty() ? null : values.get(0));
            orders.setSelectedItem(selected);
            loading = false;
            open(selected);
        } catch (Exception error) { showError(error); }
    }

    private void open(Order order) {
        current = order;
        root.removeAllChildren();
        if (order != null) {
            for (Group group : order.groups()) {
                DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(new NodeRef(NodeKind.GROUP, group.id(), group.name()));
                root.add(groupNode);
                for (Position position : order.positions()) {
                    if (!group.id().equals(position.groupId())) continue;
                    addPositionNode(groupNode, position);
                }
            }
            DefaultMutableTreeNode noGroup = null;
            for (Position position : order.positions()) {
                if (order.groups().stream().anyMatch(group -> group.id().equals(position.groupId()))) continue;
                if (noGroup == null) { noGroup = new DefaultMutableTreeNode(new NodeRef(NodeKind.GROUP, "", "Без группы")); root.add(noGroup); }
                addPositionNode(noGroup, position);
            }
        }
        treeModel.reload();
        for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
        status.setText(order == null ? "Создайте заказ или добавьте товар из поиска." : "Заказ сохраняется автоматически.");
    }

    private static void addPositionNode(DefaultMutableTreeNode parent, Position position) {
        DefaultMutableTreeNode positionNode = new DefaultMutableTreeNode(new NodeRef(
                position.need() ? NodeKind.NEED : NodeKind.DIRECT, position.id(), positionLabel(position)));
        parent.add(positionNode);
        if (position.need()) for (Candidate candidate : position.candidates()) {
            ProductData product = candidate.product();
            positionNode.add(new DefaultMutableTreeNode(new NodeRef(NodeKind.CANDIDATE,
                    candidate.id(), product.name() + " · ST " + product.stCode()
                            + (candidate.comment().isBlank() ? "" : " · " + candidate.comment()))));
        }
    }

    private void createOrder() {
        String name = JOptionPane.showInputDialog(this, "Название заказа:", "Новый заказ", JOptionPane.QUESTION_MESSAGE);
        if (name == null) return;
        try {
            Order order = repository.create(name);
            refreshOrders(order.id());
        } catch (Exception error) { showError(error); }
    }

    private void renameOrder() {
        if (current == null) return;
        String name = JOptionPane.showInputDialog(this, "Название заказа:", current.name());
        if (name == null || name.isBlank()) return;
        save(current.with(name.trim(), current.groups(), current.positions()));
    }

    private void deleteOrder() {
        if (current == null || JOptionPane.showConfirmDialog(this, "Удалить заказ «" + current.name() + "»?",
                "Удаление заказа", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try { repository.delete(current.id()); refreshOrders(null); }
        catch (Exception error) { showError(error); }
    }

    private void addGroup() {
        if (current == null) { createOrder(); if (current == null) return; }
        String name = JOptionPane.showInputDialog(this, "Название группы:", "Новая группа", JOptionPane.QUESTION_MESSAGE);
        if (name == null || name.isBlank()) return;
        List<Group> groups = new ArrayList<>(current.groups());
        groups.add(new Group(UUID.randomUUID().toString(), name.trim()));
        save(current.with(current.name(), groups, current.positions()));
    }

    private void addNeed() {
        if (current == null) { createOrder(); if (current == null) return; }
        String title = JOptionPane.showInputDialog(this, "Что требуется найти?", "Позиция-потребность", JOptionPane.QUESTION_MESSAGE);
        if (title == null || title.isBlank()) return;
        List<Position> positions = new ArrayList<>(current.positions());
        positions.add(new Position(UUID.randomUUID().toString(), selectedGroupId(), title.trim(), "", null, List.of()));
        save(current.with(current.name(), current.groups(), positions));
    }

    private void editComment() {
        if (current == null) return;
        NodeRef ref = selectedRef();
        if (ref == null) return;
        String prior = "";
        boolean candidateLevel = ref.kind() == NodeKind.CANDIDATE;
        if (candidateLevel) prior = candidate(ref.id()).comment();
        else if (ref.kind() == NodeKind.NEED || ref.kind() == NodeKind.DIRECT) prior = position(ref.id()).comment();
        else { status.setText("Выберите позицию или кандидата для комментария."); return; }
        JTextFieldWithNote note = new JTextFieldWithNote(prior);
        if (JOptionPane.showConfirmDialog(this, note.panel(), "Комментарий сборщика",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (candidateLevel) {
            Candidate old = candidate(ref.id());
            Position parent = current.positions().stream().filter(pos -> pos.candidates().contains(old)).findFirst().orElse(null);
            if (parent == null) return;
            List<Candidate> candidates = new ArrayList<>(parent.candidates());
            for (int i = 0; i < candidates.size(); i++) if (candidates.get(i).id().equals(old.id()))
                candidates.set(i, new Candidate(old.id(), old.product(), note.value()));
            replacePosition(new ArrayList<>(current.positions()), new Position(parent.id(), parent.groupId(), parent.title(),
                    parent.comment(), null, candidates));
        } else {
            Position old = position(ref.id());
            List<Position> values = new ArrayList<>(current.positions());
            replacePosition(values, new Position(old.id(), old.groupId(), old.title(), note.value(), old.product(), old.candidates()));
        }
    }

    private void removeSelected() {
        if (current == null) return;
        NodeRef ref = selectedRef();
        if (ref == null) return;
        List<Position> positions = new ArrayList<>(current.positions());
        List<Group> groups = new ArrayList<>(current.groups());
        if (ref.kind() == NodeKind.GROUP) {
            groups.removeIf(group -> group.id().equals(ref.id()));
            positions.replaceAll(position -> position.groupId().equals(ref.id())
                    ? new Position(position.id(), "", position.title(), position.comment(), position.product(), position.candidates()) : position);
        } else if (ref.kind() == NodeKind.CANDIDATE) {
            Position parent = positions.stream().filter(position -> position.candidates().stream()
                    .anyMatch(candidate -> candidate.id().equals(ref.id()))).findFirst().orElse(null);
            if (parent != null) {
                List<Candidate> candidates = parent.candidates().stream().filter(candidate -> !candidate.id().equals(ref.id())).toList();
                replacePosition(positions, new Position(parent.id(), parent.groupId(), parent.title(), parent.comment(), null, candidates));
            }
        } else positions.removeIf(position -> position.id().equals(ref.id()));
        save(current.with(current.name(), groups, positions));
    }

    private void moveSelectedToGroup() {
        if (current == null) return;
        NodeRef ref = selectedRef();
        if (ref == null || (ref.kind() != NodeKind.NEED && ref.kind() != NodeKind.DIRECT)) {
            status.setText("Выберите прямую позицию или потребность для перемещения.");
            return;
        }
        if (current.groups().isEmpty()) addGroup();
        if (current == null || current.groups().isEmpty()) return;
        Group selected = (Group) JOptionPane.showInputDialog(this, "Новая группа позиции:", "Переместить в группу",
                JOptionPane.QUESTION_MESSAGE, null, current.groups().toArray(Group[]::new), current.groups().get(0));
        if (selected == null) return;
        Position old = position(ref.id());
        if (old == null) return;
        List<Position> values = new ArrayList<>(current.positions());
        replacePosition(values, new Position(old.id(), selected.id(), old.title(), old.comment(), old.product(), old.candidates()));
    }

    private void printOrder() {
        if (current == null || current.positions().isEmpty()) { status.setText("В заказе нет позиций для печати."); return; }
        List<PrintRow> rows = new ArrayList<>();
        Map<String, List<Position>> grouped = new LinkedHashMap<>();
        for (Group group : current.groups()) grouped.put(group.id(), new ArrayList<>());
        grouped.putIfAbsent("", new ArrayList<>());
        for (Position position : current.positions()) grouped.computeIfAbsent(position.groupId(), ignored -> new ArrayList<>()).add(position);
        for (Map.Entry<String, List<Position>> entry : grouped.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            String groupName = current.groups().stream().filter(group -> group.id().equals(entry.getKey()))
                    .map(Group::name).findFirst().orElse("Без группы");
            rows.add(PrintRow.group(groupName));
            for (Position position : entry.getValue()) {
                if (position.need()) {
                    rows.add(PrintRow.need(position.title(), position.comment()));
                    for (Candidate candidate : position.candidates()) rows.add(PrintRow.product(candidate.product(),
                            true, candidate.comment()));
                } else rows.add(PrintRow.product(ProductData.from(position.product().toProduct()), false, position.comment()));
            }
        }
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("Заказ · " + current.name());
        PageFormat page = job.defaultPage();
        Paper paper = new Paper();
        double width = 210 / 25.4 * 72, height = 297 / 25.4 * 72, margin = 12 / 25.4 * 72;
        paper.setSize(width, height);
        paper.setImageableArea(margin, margin, width - margin * 2, height - margin * 2);
        page.setPaper(paper);
        page.setOrientation(PageFormat.PORTRAIT);
        job.setPrintable(new OrderPrintable(current.name(), rows), page);
        if (!job.printDialog()) return;
        try { job.print(); status.setText("Заказ отправлен на печать."); }
        catch (Exception error) { showError(error); }
    }

    private String selectedGroupId() {
        NodeRef ref = selectedRef();
        if (ref != null && ref.kind() == NodeKind.GROUP) return ref.id();
        Position selectedNeed = selectedNeed();
        if (selectedNeed != null) return selectedNeed.groupId();
        if (ref != null) {
            Position position = position(ref.id());
            if (position != null) return position.groupId();
            TreePath path = tree.getSelectionPath();
            if (path != null && path.getPathCount() > 1) {
                Object parent = ((DefaultMutableTreeNode) path.getPathComponent(path.getPathCount() - 2)).getUserObject();
                if (parent instanceof NodeRef group && group.kind() == NodeKind.GROUP) return group.id();
            }
        }
        if (current.groups().isEmpty()) {
            List<Group> groups = new ArrayList<>();
            Group group = new Group(UUID.randomUUID().toString(), "Без группы");
            groups.add(group);
            current = current.with(current.name(), groups, current.positions());
            return group.id();
        }
        return current.groups().get(0).id();
    }

    private String ensureCategoryGroup(Product product, List<Group> existing) {
        String category = product.details().entrySet().stream()
                .filter(detail -> detail.getKey().replaceAll("[^a-zA-Zа-яА-Я0-9]", "").equalsIgnoreCase("categorypath"))
                .map(Map.Entry::getValue).filter(value -> value != null && !value.isBlank()).findFirst().orElse("Без группы");
        Group found = existing.stream().filter(group -> group.name().equalsIgnoreCase(category)).findFirst().orElse(null);
        if (found != null) return found.id();
        Group created = new Group(UUID.randomUUID().toString(), category);
        List<Group> groups = new ArrayList<>(existing);
        groups.add(created);
        current = current.with(current.name(), groups, current.positions());
        return created.id();
    }

    private Position selectedNeed() {
        NodeRef ref = selectedRef();
        if (ref == null) return null;
        if (ref.kind() == NodeKind.NEED) return position(ref.id());
        if (ref.kind() == NodeKind.CANDIDATE && tree.getSelectionPath() != null) {
            TreePath parent = tree.getSelectionPath().getParentPath();
            if (parent != null && ((DefaultMutableTreeNode) parent.getLastPathComponent()).getUserObject() instanceof NodeRef need
                    && need.kind() == NodeKind.NEED) return position(need.id());
        }
        return null;
    }

    private NodeRef selectedRef() {
        TreePath path = tree.getSelectionPath();
        return path == null ? null : (NodeRef) ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }

    private Position position(String id) { return current.positions().stream().filter(value -> value.id().equals(id)).findFirst().orElse(null); }
    private Candidate candidate(String id) { return current.positions().stream().flatMap(value -> value.candidates().stream())
            .filter(value -> value.id().equals(id)).findFirst().orElse(null); }
    private void replacePosition(List<Position> values, Position replacement) {
        for (int i = 0; i < values.size(); i++) if (values.get(i).id().equals(replacement.id())) { values.set(i, replacement); break; }
        save(current.with(current.name(), current.groups(), values));
    }
    private void save(Order order) {
        try { repository.save(order); refreshOrders(order.id()); }
        catch (Exception error) { showError(error); }
    }
    private void showError(Exception error) { status.setText("Ошибка заказа: " + (error.getMessage() == null ? error : error.getMessage())); }
    private static String positionLabel(Position position) {
        return position.title() + (position.comment().isBlank() ? "" : " · " + position.comment());
    }

    private enum NodeKind { GROUP, NEED, DIRECT, CANDIDATE }
    private record NodeRef(NodeKind kind, String id, String label) { @Override public String toString() { return label; } }
    private static final class OrderTreeRenderer extends javax.swing.tree.DefaultTreeCellRenderer {
        @Override public java.awt.Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                boolean expanded, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof NodeRef ref) {
                setText(switch (ref.kind()) {
                    case GROUP -> "▸ " + ref.label();
                    case NEED -> "◉ Нужно: " + ref.label();
                    case DIRECT -> "• " + ref.label();
                    case CANDIDATE -> "↳ Кандидат: " + ref.label();
                });
                if (!selected) setForeground(ref.kind() == NodeKind.CANDIDATE ? new Color(70, 100, 125) : tree.getForeground());
            }
            return this;
        }
    }
    private record PrintRow(String kind, String name, String stCode, String article, String barcode,
                            String price, String stock, String comment) {
        static PrintRow group(String title) { return new PrintRow("group", title, "", "", "", "", "", ""); }
        static PrintRow need(String title, String comment) { return new PrintRow("need", title, "", "", "", "", "", comment); }
        static PrintRow product(ProductData product, boolean candidate, String comment) {
            String price = product.priceKopecks() == null ? "—" : product.toProduct().price().format();
            return new PrintRow(candidate ? "candidate" : "product", product.name(), product.stCode(),
                    product.vendorCode(), product.barcode(), price, product.totalQuantity(), comment);
        }
    }

    private static final class OrderPrintable implements Printable {
        private static final double HEAD = 46, TABLE_HEAD = 18, FOOTER = 80, ROW = 24;
        private final String name;
        private final List<PrintRow> rows;
        OrderPrintable(String name, List<PrintRow> rows) { this.name = name; this.rows = List.copyOf(rows); }
        @Override public int print(Graphics graphics, PageFormat page, int pageIndex) {
            double width = page.getImageableWidth(), height = page.getImageableHeight();
            int perPage = Math.max(1, (int) ((height - HEAD - TABLE_HEAD - FOOTER) / ROW));
            List<List<PrintRow>> pages = paginate(rows, perPage);
            if (pageIndex < 0 || pageIndex >= pages.size()) return NO_SUCH_PAGE;
            List<PrintRow> pageRows = pages.get(pageIndex);
            List<PrintRow> qrRows = pageRows.stream()
                    .filter(row -> !row.stCode().isBlank() || !row.article().isBlank() || !row.barcode().isBlank())
                    .toList();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(page.getImageableX(), page.getImageableY());
                g.setColor(Color.BLACK);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
                g.drawString("СБОРКА ЗАКАЗА", 0, 15);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 7));
                g.drawString(name, 0, 29);
                g.drawString("ЛИСТ " + (pageIndex + 1) + "/" + pages.size(), (int) width - 48, 15);
                g.drawLine(0, 37, (int) width, 37);
                double tableTop = HEAD;
                double[] x = {0, width * .39, width * .53, width * .66, width * .80, width * .91};
                double[] w = {width * .39, width * .14, width * .13, width * .14, width * .11, width * .09};
                g.setColor(Color.BLACK);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 6));
                String[] headings = {"ТОВАР / ПОТРЕБНОСТЬ", "SAP / ST", "АРТИКУЛ", "ШТРИХКОД", "ЦЕНА", "ОСТАТОК"};
                for (int col = 0; col < headings.length; col++) g.drawString(headings[col], (int) x[col] + 2, (int) tableTop + 12);
                g.drawLine(0, (int) (tableTop + TABLE_HEAD), (int) width, (int) (tableTop + TABLE_HEAD));
                for (int i = 0; i < pageRows.size(); i++) {
                    PrintRow row = pageRows.get(i);
                    int y = (int) (tableTop + TABLE_HEAD + i * ROW);
                    if (row.kind().equals("group")) {
                        g.setColor(Color.BLACK); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 7));
                        g.drawString("ГРУППА  /  " + clip(row.name(), width - 12, g), 7, y + 16); continue;
                    }
                    if (row.kind().equals("need")) {
                        g.setColor(Color.BLACK); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 7));
                        g.drawString("ПОТРЕБНОСТЬ  /  " + clip(row.name(), width - 20, g), 14, y + 10);
                        if (!row.comment().isBlank()) {
                            g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 6));
                            g.drawString("Сборщик: " + clip(row.comment(), width - 24, g), 18, y + 21);
                        }
                        continue;
                    }
                    boolean candidate = row.kind().equals("candidate");
                    if (candidate) {
                        g.setColor(Color.BLACK);
                        g.drawLine(15, y, 15, y + (int) ROW);
                        g.drawLine(15, y + (int) ROW / 2, 21, y + (int) ROW / 2);
                    }
                    g.setColor(Color.BLACK);
                    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 7));
                    int nameIndent = candidate ? 24 : 3;
                    String prefix = candidate ? "↳ " : "• ";
                    g.drawString(clip(prefix + row.name(), w[0] - nameIndent - 2, g), (int) x[0] + nameIndent, y + 10);
                    g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 7));
                    String st = row.stCode();
                    g.drawString(clip(st, w[1] - 4, g), (int) x[1] + 2, y + 11);
                    g.drawString(clip(row.article(), w[2] - 4, g), (int) x[2] + 2, y + 11);
                    g.drawString(clip(row.barcode(), w[3] - 4, g), (int) x[3] + 2, y + 11);
                    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 7));
                    g.drawString(row.price(), (int) x[4] + 2, y + 11);
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 7));
                    g.drawString(clip(row.stock(), w[5] - 4, g), (int) x[5] + 2, y + 11);
                    if (!row.comment().isBlank()) {
                        g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 6));
                        g.setColor(new Color(75, 75, 75));
                        g.drawString("Сборщик: " + clip(row.comment(), width - x[0] - 10, g), (int) x[0] + nameIndent + 5, y + 21);
                    }
                    g.setColor(Color.BLACK); g.drawLine(0, y + (int) ROW, (int) width, y + (int) ROW);
                }
                int footerY = (int) (height - FOOTER + 2);
                g.setColor(Color.DARK_GRAY); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 7));
                g.drawString("QR: SAP, АРТИКУЛ, ШК · " + qrRows.size(), 0, footerY);
                if (!qrRows.isEmpty()) try {
                    String payload = qrRows.stream()
                            .map(row -> String.join(",", row.stCode(), row.article(), row.barcode()))
                            .collect(java.util.stream.Collectors.joining("\n"));
                    var matrix = new MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 62, 62,
                            Map.of(EncodeHintType.MARGIN, 1));
                    g.drawImage(MatrixToImageWriter.toBufferedImage(matrix), 0, footerY + 5, 62, 62, null);
                } catch (Exception ignored) { }
                return PAGE_EXISTS;
            } finally { g.dispose(); }
        }
        private static String clip(String value, double max, Graphics2D g) {
            String text = value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ");
            while (!text.isEmpty() && g.getFontMetrics().stringWidth(text) > max) text = text.substring(0, text.length() - 1);
            return text;
        }

        /** Keep group headings with their first item and keep a need with its candidates where possible. */
        private static List<List<PrintRow>> paginate(List<PrintRow> rows, int capacity) {
            capacity = Math.max(3, capacity); // group + need + at least one candidate must always fit
            List<List<PrintRow>> pages = new ArrayList<>();
            List<PrintRow> page = new ArrayList<>();
            int index = 0;
            while (index < rows.size()) {
                PrintRow group = rows.get(index++);
                if (!group.kind().equals("group")) continue;
                int sectionEnd = index;
                while (sectionEnd < rows.size() && !rows.get(sectionEnd).kind().equals("group")) sectionEnd++;
                List<PrintRow> section = rows.subList(index, sectionEnd);
                int item = 0;
                while (item < section.size()) {
                    List<PrintRow> unit = new ArrayList<>();
                    PrintRow first = section.get(item++);
                    unit.add(first);
                    if (first.kind().equals("need")) {
                        while (item < section.size() && section.get(item).kind().equals("candidate")) {
                            unit.add(section.get(item++));
                        }
                    }

                    boolean headingOnPage = hasGroupHeading(page, group.name());
                    if (unit.size() + 1 <= capacity) {
                        int required = unit.size() + (headingOnPage ? 0 : 1);
                        if (!page.isEmpty() && page.size() + required > capacity) {
                            pages.add(List.copyOf(page));
                            page.clear();
                            headingOnPage = false;
                        }
                        if (!headingOnPage) page.add(group);
                        page.addAll(unit);
                        continue;
                    }

                    // A request with unusually many candidates must span pages; repeat its context.
                    int candidateIndex = 1;
                    while (candidateIndex < unit.size()) {
                        headingOnPage = hasGroupHeading(page, group.name());
                        int contextRows = headingOnPage ? 0 : 1;
                        boolean needAlreadyOnPage = !page.isEmpty() && page.get(page.size() - 1).kind().equals("need")
                                && page.get(page.size() - 1).name().equals(first.name());
                        if (!needAlreadyOnPage) contextRows++;
                        if (page.size() + contextRows >= capacity) {
                            if (!page.isEmpty()) pages.add(List.copyOf(page));
                            page = new ArrayList<>();
                            headingOnPage = false;
                            needAlreadyOnPage = false;
                            contextRows = 2;
                        }
                        if (!headingOnPage) page.add(group);
                        if (!needAlreadyOnPage) {
                            PrintRow need = first;
                            if (candidateIndex > 1) need = PrintRow.need("Продолжение: " + first.name(), "");
                            page.add(need);
                        }
                        int room = capacity - page.size();
                        int count = Math.min(room, unit.size() - candidateIndex);
                        page.addAll(unit.subList(candidateIndex, candidateIndex + count));
                        candidateIndex += count;
                        if (candidateIndex < unit.size()) {
                            pages.add(List.copyOf(page));
                            page = new ArrayList<>();
                        }
                    }
                }
                index = sectionEnd;
            }
            if (!page.isEmpty()) pages.add(List.copyOf(page));
            return pages;
        }

        private static boolean hasGroupHeading(List<PrintRow> page, String name) {
            for (int i = page.size() - 1; i >= 0; i--) {
                if (page.get(i).kind().equals("group")) return page.get(i).name().equals(name);
            }
            return false;
        }
    }

    private static final class JTextFieldWithNote {
        private final javax.swing.JTextArea field = new javax.swing.JTextArea(4, 36);
        JTextFieldWithNote(String text) { field.setText(text); field.setLineWrap(true); field.setWrapStyleWord(true); }
        JPanel panel() { JPanel panel = new JPanel(new BorderLayout(4, 4)); panel.add(new JLabel("Комментарий на листе печати:"), BorderLayout.NORTH); panel.add(new JScrollPane(field)); return panel; }
        String value() { return field.getText().trim(); }
    }
}
