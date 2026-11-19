package com.setretailx.printer.ui;

import com.setretailx.printer.repository.AccountRepository;
import com.setretailx.printer.service.ParentAccessService;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings("serial")
final class AccountManagementPanel extends JPanel {
    private final AccountRepository repository;
    private final ParentAccessService access;
    private final AccountTableModel model = new AccountTableModel();
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel("Управление локальными учётными записями родительского ПК.");
    private final JButton create = new JButton("Создать аккаунт");
    private final JButton edit = new JButton("Изменить права");
    private final JButton disable = new JButton("Отключить / включить");
    private boolean unlocked;
    private String sessionToken;

    AccountManagementPanel(AccountRepository repository, ParentAccessService access) {
        super(new BorderLayout(8, 8));
        this.repository = repository;
        this.access = access;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.add(status, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton refresh = new JButton("Обновить");
        JButton unlock = new JButton("Войти как администратор");
        refresh.addActionListener(event -> refreshAccounts());
        create.addActionListener(event -> createAccount());
        edit.addActionListener(event -> editPermissions());
        disable.addActionListener(event -> toggleSelected());
        unlock.addActionListener(event -> unlock());
        actions.add(refresh); actions.add(unlock); actions.add(create); actions.add(edit); actions.add(disable);
        footer.add(actions, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        create.setEnabled(false); edit.setEnabled(false); disable.setEnabled(false);
        status.setText("Для просмотра и управления аккаунтами требуется вход администратора.");
    }

    void refreshAccounts() {
        if (!unlocked) return;
        try {
            access.require(sessionToken, AccountRepository.Permission.ACCOUNT_ADMIN);
            model.setAccounts(repository.listAccounts());
            status.setText("Учётных записей: " + model.accounts.size()
                    + ". Чтение каталога и справочников разрешено по умолчанию.");
        } catch (SQLException error) { showError("Не удалось загрузить аккаунты", error); }
    }

    private void unlock() {
        JTextField username = new JTextField(20);
        JPasswordField password = new JPasswordField(20);
        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Администратор родительского ПК:"));
        form.add(new JLabel("Имя пользователя:")); form.add(username);
        form.add(new JLabel("Пароль:")); form.add(password);
        if (JOptionPane.showConfirmDialog(this, form, "Вход администратора", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        char[] secret = password.getPassword();
        try {
            ParentAccessService.LoginResult result = access.login(username.getText(), secret);
            secret = null; // login clears the supplied array.
            if (result == null || !result.account().admin()) {
                status.setText("Требуется действующая административная учётная запись.");
                JOptionPane.showMessageDialog(this, status.getText(), "Доступ запрещён", JOptionPane.ERROR_MESSAGE);
                return;
            }
            sessionToken = result.token();
            unlocked = true;
            create.setEnabled(true); edit.setEnabled(true); disable.setEnabled(true);
            status.setText("Выполнен локальный вход администратора: " + result.account().username());
            refreshAccounts();
        } catch (SQLException error) { showError("Не удалось проверить учётную запись", error); }
        finally {
            if (secret != null) java.util.Arrays.fill(secret, '\0');
            password.setText("");
        }
    }

    private void createAccount() {
        JTextField username = new JTextField(20);
        JPasswordField password = new JPasswordField(20);
        JCheckBox admin = new JCheckBox("Администратор");
        JCheckBox sales = new JCheckBox("Чтение продаж и чеков");
        JCheckBox writes = new JCheckBox("Изменение общих данных");
        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Имя пользователя:")); form.add(username);
        form.add(new JLabel("Пароль (10–1024 символа):")); form.add(password);
        form.add(admin); form.add(sales); form.add(writes);
        if (JOptionPane.showConfirmDialog(this, form, "Новая учётная запись", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        char[] secret = password.getPassword();
        try {
            access.require(sessionToken, AccountRepository.Permission.ACCOUNT_ADMIN);
            EnumSet<AccountRepository.Permission> grants = EnumSet.noneOf(AccountRepository.Permission.class);
            if (sales.isSelected()) grants.add(AccountRepository.Permission.SALES_READ);
            if (writes.isSelected()) grants.add(AccountRepository.Permission.SHARED_DATA_WRITE);
            repository.create(username.getText(), secret, grants, admin.isSelected());
            secret = null;
            status.setText("Учётная запись создана.");
            refreshAccounts();
        } catch (IllegalArgumentException | SQLException error) { showError("Не удалось создать аккаунт", error); }
        finally {
            if (secret != null) java.util.Arrays.fill(secret, '\0');
            password.setText("");
        }
    }

    private void editPermissions() {
        AccountRepository.Account account = selected();
        if (account == null) return;
        JCheckBox sales = new JCheckBox("Чтение продаж и чеков",
                account.permissions().contains(AccountRepository.Permission.SALES_READ));
        JCheckBox writes = new JCheckBox("Изменение общих данных",
                account.permissions().contains(AccountRepository.Permission.SHARED_DATA_WRITE));
        JCheckBox admin = new JCheckBox("Администратор", account.admin());
        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Права для «" + account.username() + "»:"));
        form.add(sales); form.add(writes); form.add(admin);
        if (JOptionPane.showConfirmDialog(this, form, "Права аккаунта", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            access.require(sessionToken, AccountRepository.Permission.ACCOUNT_ADMIN);
            repository.updateAccess(account.id(), permissions(sales, writes), admin.isSelected());
            status.setText("Права обновлены; действующие сессии применят их при следующем запросе.");
            refreshAccounts();
        } catch (SQLException error) { showError("Не удалось изменить права", error); }
    }

    private static Set<AccountRepository.Permission> permissions(JCheckBox sales, JCheckBox writes) {
        EnumSet<AccountRepository.Permission> grants = EnumSet.noneOf(AccountRepository.Permission.class);
        if (sales.isSelected()) grants.add(AccountRepository.Permission.SALES_READ);
        if (writes.isSelected()) grants.add(AccountRepository.Permission.SHARED_DATA_WRITE);
        return grants;
    }

    private void toggleSelected() {
        AccountRepository.Account account = selected();
        if (account == null) return;
        boolean enabled = !account.enabled();
        try {
            access.require(sessionToken, AccountRepository.Permission.ACCOUNT_ADMIN);
            repository.setEnabled(account.id(), enabled);
            status.setText(enabled ? "Учётная запись включена." : "Учётная запись отключена.");
            refreshAccounts();
        } catch (SQLException error) { showError("Не удалось изменить состояние аккаунта", error); }
    }

    private AccountRepository.Account selected() {
        int view = table.getSelectedRow();
        if (view < 0) { status.setText("Сначала выберите аккаунт."); return null; }
        return model.accounts.get(table.convertRowIndexToModel(view));
    }

    private void showError(String title, Exception error) {
        status.setText(title + ".");
        JOptionPane.showMessageDialog(this, error.getMessage(), title, JOptionPane.ERROR_MESSAGE);
    }

    private static final class AccountTableModel extends AbstractTableModel {
        private final String[] columns = {"Имя", "Состояние", "Администратор", "Продажи / чеки", "Общие данные"};
        private List<AccountRepository.Account> accounts = List.of();

        void setAccounts(Set<AccountRepository.Account> next) {
            accounts = next.stream().sorted(java.util.Comparator.comparing(
                    AccountRepository.Account::username, String.CASE_INSENSITIVE_ORDER)).toList();
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return accounts.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) {
            AccountRepository.Account account = accounts.get(row);
            return switch (column) {
                case 0 -> account.username();
                case 1 -> account.enabled() ? "Включён" : "Отключён";
                case 2 -> account.admin() ? "Да" : "Нет";
                case 3 -> account.can(AccountRepository.Permission.SALES_READ) ? "Да" : "Нет";
                case 4 -> account.permissions().contains(AccountRepository.Permission.SHARED_DATA_WRITE) ? "Да" : "Нет";
                default -> "";
            };
        }
    }
}
