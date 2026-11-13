package com.setretailx.printer.ui;

import com.setretailx.printer.repository.AddressStorageRepository;
import com.setretailx.printer.repository.AddressStorageRepository.LocationResult;
import com.setretailx.printer.repository.AddressStorageRepository.StorageAddress;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.function.Consumer;

/** MDI screen for managing storage addresses and finding product locations. */
@SuppressWarnings("serial")
final class AddressManagementPanel extends JPanel {
    private final AddressStorageRepository repository;
    private final Runnable addressesChanged;
    private final JComboBox<StorageAddress> addresses = new JComboBox<>();
    private final JButton renameButton = new JButton("Переименовать");
    private final JButton deleteButton = new JButton("Удалить адрес");
    private final JTextField locationQuery = new JTextField(24);
    private final DefaultTableModel locationsModel = new DefaultTableModel(
            new String[]{"Адрес", "Коробка", "Товар", "Артикул", "Штрихкод", "Кол-во"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable locationsTable = new JTable(locationsModel);
    private final JLabel status = new JLabel("Создайте адрес хранения или найдите товар по артикулу/штрихкоду.");

    AddressManagementPanel(AddressStorageRepository repository, Runnable addressesChanged) {
        super(new BorderLayout(8, 8));
        this.repository = repository;
        this.addressesChanged = addressesChanged == null ? () -> { } : addressesChanged;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(buildAddressControls(), BorderLayout.NORTH);
        add(buildLocationSearch(), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        addresses.setPrototypeDisplayValue(new StorageAddress("Склад-01", "Основной склад"));
        addresses.addActionListener(event -> updateAddressButtons());
        locationQuery.addActionListener(event -> findLocations());
        refreshAddresses(null);
    }

    private JPanel buildAddressControls() {
        JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 6, 0));
        controls.setBorder(BorderFactory.createTitledBorder("Адреса хранения"));
        controls.add(new JLabel("Адрес:"));
        controls.add(addresses);
        JButton create = new JButton("Создать адрес");
        create.addActionListener(event -> createAddress());
        controls.add(create);
        renameButton.addActionListener(event -> renameAddress());
        controls.add(renameButton);
        deleteButton.addActionListener(event -> deleteAddress());
        controls.add(deleteButton);
        return controls;
    }

    private JPanel buildLocationSearch() {
        JPanel search = new JPanel(new BorderLayout(6, 6));
        search.setBorder(BorderFactory.createTitledBorder("Найти, где хранится товар"));
        JPanel query = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 6, 0));
        query.add(new JLabel("Артикул, штрихкод или название:"));
        query.add(locationQuery);
        JButton find = new JButton("Найти");
        find.addActionListener(event -> findLocations());
        query.add(find);
        search.add(query, BorderLayout.NORTH);
        search.add(new JScrollPane(locationsTable), BorderLayout.CENTER);
        return search;
    }

    private void createAddress() {
        JTextField id = new JTextField(18);
        JTextField name = new JTextField(18);
        JPanel fields = new JPanel(new GridLayout(0, 1, 4, 4));
        fields.add(new JLabel("ID хранилища (уникальный):"));
        fields.add(id);
        fields.add(new JLabel("Название (необязательно):"));
        fields.add(name);
        if (JOptionPane.showConfirmDialog(this, fields, "Создать адрес хранения",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        run("Создаём адрес…", () -> {
            repository.createAddress(id.getText(), name.getText());
            return id.getText().trim();
        }, createdId -> refreshAddresses(createdId), this::showError);
    }

    private void renameAddress() {
        StorageAddress address = (StorageAddress) addresses.getSelectedItem();
        if (address == null) return;
        String name = JOptionPane.showInputDialog(this, "Новое название адреса:", address.name());
        if (name == null) return;
        run("Переименовываем адрес…", () -> {
            repository.renameAddress(address.addressId(), name);
            return address.addressId();
        }, this::refreshAddresses, this::showError);
    }

    private void deleteAddress() {
        StorageAddress address = (StorageAddress) addresses.getSelectedItem();
        if (address == null) return;
        int answer = JOptionPane.showConfirmDialog(this,
                "Удалить адрес «" + address.addressId() + " — " + address.name()
                        + "» вместе со всеми коробками и товарами в них? Это действие нельзя отменить.",
                "Удалить адрес", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        run("Удаляем адрес и его коробки…", () -> {
            repository.deleteAddress(address.addressId());
            return null;
        }, ignored -> refreshAddresses(null), this::showError);
    }

    private void refreshAddresses(String selectId) {
        run("Загружаем адреса…", repository::addresses, result -> {
            addresses.removeAllItems();
            result.forEach(addresses::addItem);
            if (selectId != null) {
                for (int i = 0; i < addresses.getItemCount(); i++) {
                    if (addresses.getItemAt(i).addressId().equals(selectId)) {
                        addresses.setSelectedIndex(i);
                        break;
                    }
                }
            }
            updateAddressButtons();
            addressesChanged.run();
            if (result.isEmpty()) setStatus("Адресов пока нет — создайте адрес хранения.");
        }, this::showError);
    }

    private void updateAddressButtons() {
        boolean selected = addresses.getSelectedItem() != null;
        renameButton.setEnabled(selected);
        deleteButton.setEnabled(selected);
    }

    private void findLocations() {
        String query = locationQuery.getText().trim();
        if (query.isEmpty()) {
            setStatus("Введите артикул, штрихкод или название для поиска.");
            return;
        }
        run("Ищем места хранения…", () -> repository.findLocations(query), rows -> {
            locationsModel.setRowCount(0);
            for (LocationResult location : rows) locationsModel.addRow(new Object[]{
                    location.addressId() + " — " + location.addressName(), location.boxCode(), location.name(),
                    location.vendorCode(), location.barcode(), location.quantity()});
            setStatus(rows.isEmpty() ? "Товар не найден в адресном хранении." : "Найдено размещений: " + rows.size());
        }, this::showError);
    }

    private <T> void run(String message, AddressWork<T> work, Consumer<T> success, Consumer<String> failure) {
        setStatus(message);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return work.run(); }
            @Override protected void done() {
                try { success.accept(get()); }
                catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    failure.accept(cause.getMessage() == null ? cause.toString() : cause.getMessage());
                }
            }
        }.execute();
    }

    private void showError(String message) {
        setStatus("Ошибка: " + message);
        JOptionPane.showMessageDialog(this, message, "Адреса хранения", JOptionPane.ERROR_MESSAGE);
    }

    private void setStatus(String message) { status.setText(message); }

    @FunctionalInterface private interface AddressWork<T> { T run() throws Exception; }
}
