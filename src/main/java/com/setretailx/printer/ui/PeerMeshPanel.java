package com.setretailx.printer.ui;

import com.setretailx.printer.service.PeerMeshService;
import com.setretailx.printer.config.AppConfig;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionException;

@SuppressWarnings("serial")
final class PeerMeshPanel extends JPanel {
    private final PeerMeshService service;
    private final PeerTableModel model = new PeerTableModel();
    private final JTable table = new JTable(model);
    private final JTextField name = new JTextField(22);
    private final JLabel status = new JLabel("Поиск пиров в локальной сети…");
    private final JButton sync = new JButton("Синхронизировать выбранный пир");

    PeerMeshPanel(PeerMeshService service) {
        super(new BorderLayout(8, 8));
        this.service = service;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel center = new JPanel(new BorderLayout(8, 8));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        center.add(new JScrollPane(table), BorderLayout.CENTER);
        add(new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildIdentityPanel(), center) {{
            setResizeWeight(0.0);
            setBorder(null);
        }}, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 4));
        footer.add(status, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton refresh = new JButton("Обновить список");
        refresh.addActionListener(event -> refreshPeers());
        sync.addActionListener(event -> synchronizeSelected());
        actions.add(refresh); actions.add(sync);
        footer.add(actions, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        refreshPeers();
        new Timer(2000, event -> refreshPeers()).start();
    }

    private JPanel buildIdentityPanel() {
        JPanel outer = new JPanel(new BorderLayout(8, 8));
        outer.setBorder(BorderFactory.createTitledBorder("Этот пир"));
        JPanel fields = new JPanel(new GridLayout(0, 1, 2, 2));
        fields.add(new JLabel("Роль: " + service.nodeRole().displayName()));
        if (service.nodeRole() == AppConfig.NodeRole.CHILD) {
            fields.add(new JLabel(service.parentNodeId().isBlank()
                    ? "Родительский ПК: ещё не выбран"
                    : "Привязан к родителю: " + service.parentNodeId()));
        }
        fields.add(new JLabel("Имя в сети и порт TCP/UDP: " + service.peerName() + " · " + service.port()));
        fields.add(new JLabel("ID: " + service.nodeId()));
        fields.add(new JLabel("Fingerprint Ed25519: " + service.fingerprint()));
        JPanel edit = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        name.setText(service.peerName());
        JButton save = new JButton("Сохранить имя");
        save.addActionListener(event -> {
            try {
                service.setPeerName(name.getText());
                fields.removeAll();
                fields.add(new JLabel("Роль: " + service.nodeRole().displayName()));
                if (service.nodeRole() == AppConfig.NodeRole.CHILD) {
                    fields.add(new JLabel(service.parentNodeId().isBlank()
                            ? "Родительский ПК: ещё не выбран"
                            : "Привязан к родителю: " + service.parentNodeId()));
                }
                fields.add(new JLabel("Имя в сети и порт TCP/UDP: " + service.peerName() + " · " + service.port()));
                fields.add(new JLabel("ID: " + service.nodeId()));
                fields.add(new JLabel("Fingerprint Ed25519: " + service.fingerprint()));
                fields.revalidate(); fields.repaint();
                status.setText("Имя пира сохранено.");
            } catch (Exception error) { showError("Не удалось сохранить имя", error); }
        });
        edit.add(new JLabel("Имя:")); edit.add(name); edit.add(save);
        outer.add(fields, BorderLayout.NORTH); outer.add(edit, BorderLayout.SOUTH);
        return outer;
    }

    void refreshPeers() {
        List<PeerMeshService.Peer> peers = service.peers().values().stream()
                .sorted(Comparator.comparing(PeerMeshService.Peer::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(PeerMeshService.Peer::address)).toList();
        model.setPeers(peers);
        boolean child = service.nodeRole() == AppConfig.NodeRole.CHILD;
        sync.setEnabled(child && peers.stream().anyMatch(peer -> peer.role().equals("parent")));
        if (peers.isEmpty()) status.setText("Узлы пока не найдены. Проверьте, что ПК в одной подсети и UDP/TCP-порт 45873 разрешён в firewall.");
        else if (!child) status.setText("Родительский ПК принимает дочерние узлы; синхронизацию запускают на дочернем ПК.");
    }

    private void synchronizeSelected() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) { status.setText("Выберите пир в списке."); return; }
        int row = table.convertRowIndexToModel(viewRow);
        PeerMeshService.Peer peer = model.peerAt(row);
        if (service.nodeRole() != AppConfig.NodeRole.CHILD || !"parent".equals(peer.role())) {
            status.setText("На дочернем узле выберите родительский ПК для синхронизации.");
            return;
        }
        if (!peer.trusted()) {
            status.setText("При подключении появится запрос подтверждения fingerprint.");
        }
        sync.setEnabled(false);
        status.setText("Шифрованная синхронизация с " + peer.name() + "…");
        service.synchronize(peer).whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
            sync.setEnabled(true);
            if (error != null) {
                showError("Синхронизация не завершена", unwrap(error));
                status.setText("Синхронизация не завершена.");
            } else {
                status.setText("Готово. Добавлено товаров: " + result.productsAdded()
                        + "; записей начальной настройки: " + result.workspaceRowsAdded()
                        + "; доменных изменений: " + result.changesApplied()
                        + (result.conflicts() == 0 ? "." : "; конфликтов, оставленных локально: " + result.conflicts() + "."));
            }
            refreshPeers();
        }));
    }

    static boolean confirmTrust(PeerMeshService.Peer peer, String fingerprint) throws Exception {
        final int[] answer = {JOptionPane.NO_OPTION};
        Runnable prompt = () -> answer[0] = JOptionPane.showConfirmDialog(null,
                "Обнаружен новый пир. Разрешить доверие этому ключу?\n\n"
                        + "Имя: " + peer.name() + "\nАдрес: " + peer.address() + "\nID: " + peer.id()
                        + "\n\nFingerprint SHA-256:\n" + fingerprint
                        + "\n\nСверьте fingerprint с владельцем другого ПК по независимому каналу.",
                "Подтверждение нового пира", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (SwingUtilities.isEventDispatchThread()) prompt.run();
        else {
            try { SwingUtilities.invokeAndWait(prompt); }
            catch (InvocationTargetException error) { throw new IllegalStateException(error.getCause()); }
        }
        return answer[0] == JOptionPane.YES_OPTION;
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }

    private void showError(String title, Throwable error) {
        JOptionPane.showMessageDialog(this, error.getMessage() == null ? error.toString() : error.getMessage(),
                title, JOptionPane.ERROR_MESSAGE);
    }

    private final class PeerTableModel extends AbstractTableModel {
        private final String[] columns = {"Имя", "Адрес", "Состояние доверия", "Статус"};
        private List<PeerMeshService.Peer> peers = new ArrayList<>();
        void setPeers(List<PeerMeshService.Peer> values) { peers = new ArrayList<>(values); fireTableDataChanged(); }
        PeerMeshService.Peer peerAt(int row) { return peers.get(row); }
        @Override public int getRowCount() { return peers.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) {
            PeerMeshService.Peer peer = peers.get(row);
            return switch (column) {
                case 0 -> peer.name();
                case 1 -> peer.address() + ":" + peer.port();
                case 2 -> ("parent".equals(peer.role()) ? "Родитель · " : "Дочерний · ")
                        + (service.isTrusted(peer.id()) ? "доверенный" : "не подтверждён");
                default -> peer.status().isBlank() ? (peer.online() ? "В сети" : "Не в сети") : peer.status();
            };
        }
    }
}
