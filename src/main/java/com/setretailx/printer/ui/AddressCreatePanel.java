package com.setretailx.printer.ui;

import com.setretailx.printer.repository.AddressStorageRepository;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Consumer;

/** Dedicated MDI form for creating a storage address. */
@SuppressWarnings("serial")
final class AddressCreatePanel extends JPanel {
    private final AddressStorageRepository repository;
    private final Runnable created;
    private final JTextField addressId = new JTextField(24);
    private final JTextField name = new JTextField(24);
    private final JLabel status = new JLabel("Укажите уникальный код адреса и, при необходимости, название.");
    private final JButton save = new JButton("Создать адрес");

    AddressCreatePanel(AddressStorageRepository repository, Runnable created) {
        super(new BorderLayout(8, 8));
        this.repository = repository;
        this.created = created == null ? () -> { } : created;
        setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));

        JPanel fields = new JPanel(new GridBagLayout());
        addField(fields, 0, "ID адреса (уникальный)", addressId);
        addField(fields, 1, "Название (необязательно)", name);
        add(fields, BorderLayout.NORTH);

        save.addActionListener(event -> saveAddress());
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.add(status, BorderLayout.CENTER);
        footer.add(save, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        addressId.addActionListener(event -> saveAddress());
        name.addActionListener(event -> saveAddress());
    }

    private static void addField(JPanel panel, int row, String label, JTextField field) {
        GridBagConstraints caption = new GridBagConstraints();
        caption.gridx = 0;
        caption.gridy = row;
        caption.anchor = GridBagConstraints.WEST;
        caption.insets = new Insets(4, 0, 4, 10);
        panel.add(new JLabel(label + ":"), caption);
        GridBagConstraints input = new GridBagConstraints();
        input.gridx = 1;
        input.gridy = row;
        input.weightx = 1;
        input.fill = GridBagConstraints.HORIZONTAL;
        input.insets = new Insets(4, 0, 4, 0);
        panel.add(field, input);
    }

    private void saveAddress() {
        String id = addressId.getText().trim();
        if (id.isEmpty()) {
            status.setText("Введите ID адреса.");
            addressId.requestFocusInWindow();
            return;
        }
        save.setEnabled(false);
        status.setText("Создаём адрес…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                repository.createAddress(id, name.getText());
                return null;
            }

            @Override protected void done() {
                save.setEnabled(true);
                try {
                    get();
                    status.setText("Адрес «" + id + "» создан.");
                    created.run();
                    name.setText("");
                    addressId.setText("");
                    addressId.requestFocusInWindow();
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    status.setText("Не удалось создать адрес: " + safeMessage(cause));
                }
            }
        }.execute();
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }
}
