package com.setretailx.printer;

import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.repository.ProductRepository;
import com.setretailx.printer.repository.AccountRepository;
import com.setretailx.printer.ui.MainWindow;
import com.setretailx.printer.ui.StartupSplash;

import com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.JPanel;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.JPasswordField;
import java.awt.GridLayout;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PrinterApp {
    private PrinterApp() { }

    public static void main(String[] args) {
        AppConfig.loadDotEnv();
        FlatOneDarkIJTheme.setup();
        UIManager.put("Button.arc", 10);
        UIManager.put("Component.arc", 8);
        SwingUtilities.invokeLater(PrinterApp::startWithSplash);
    }

    private static void startWithSplash() {
        StartupSplash splash = new StartupSplash();
        splash.setVisible(true);
        ProductRepository products = new ProductRepository();
        new SwingWorker<ProductRepository.StartupSummary, ProductRepository.StartupProgress>() {
            @Override
            protected ProductRepository.StartupSummary doInBackground() throws Exception {
                LicenseClient.check();
                DatabaseMigrator.migrateAll();
                return products.prepareForStartup(progress -> publish(progress));
            }

            @Override
            protected void process(List<ProductRepository.StartupProgress> updates) {
                if (updates.isEmpty()) return;
                ProductRepository.StartupProgress latest = updates.get(updates.size() - 1);
                splash.updateProgress(latest.message(), latest.percent());
            }

            @Override
            protected void done() {
                try {
                    get();
                    if (!ensureFirstAdministrator(splash)) {
                        splash.dispose();
                        return;
                    }
                    splash.updateProgress("Запускаем рабочее пространство…", 100);
                    MainWindow mainWindow = new MainWindow();
                    splash.dispose();
                    mainWindow.setVisible(true);
                    monitorLicense(mainWindow);
                } catch (Exception error) {
                    Throwable cause = error;
                    while (cause.getCause() != null) cause = cause.getCause();
                    LicenseClient.LicenseException licenseError = findLicenseError(error);
                    boolean licenseProblem = licenseError != null;
                    JOptionPane.showMessageDialog(splash,
                            (licenseProblem ? "Проверка лицензии не пройдена:\n" :
                                    "Не удалось подготовить базы данных или локальный каталог товаров:\n")
                                    + (licenseProblem ? licenseError.getMessage()
                                            : cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage())
                                    + (licenseProblem ? "\n\nДанные на этом компьютере не удалены и не изменены."
                                            : "\n\nПриложение не будет запущено, чтобы не использовать устаревшую схему."),
                            licenseProblem ? "Лицензия недоступна" : "Проблема при запуске", JOptionPane.ERROR_MESSAGE);
                    splash.dispose();
                }
            }
        }.execute();
    }

    private static LicenseClient.LicenseException findLicenseError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof LicenseClient.LicenseException licenseError) return licenseError;
        }
        return null;
    }

    private static void monitorLicense(MainWindow mainWindow) {
        AtomicBoolean stopping = new AtomicBoolean();
        Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "license-watchdog");
            thread.setDaemon(true);
            return thread;
        }).scheduleWithFixedDelay(() -> {
            if (stopping.get()) return;
            try {
                LicenseClient.checkForRenewal();
            } catch (LicenseClient.LicenseException error) {
                if (!stopping.compareAndSet(false, true)) return;
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(mainWindow,
                            "Рабочее пространство будет закрыто: проверка лицензии не пройдена.\n"
                                    + error.getMessage()
                                    + "\n\nЛокальные данные не удаляются.",
                            "Лицензия недоступна", JOptionPane.ERROR_MESSAGE);
                    mainWindow.dispose();
                    System.exit(0);
                });
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    private static boolean ensureFirstAdministrator(java.awt.Component parent) throws SQLException {
        if (AppConfig.nodeRole() != AppConfig.NodeRole.PARENT) return true;
        AccountRepository accounts = new AccountRepository();
        if (accounts.countAccounts() > 0) return true;
        while (true) {
            JTextField username = new JTextField(22);
            JPasswordField password = new JPasswordField(22);
            JPasswordField confirmation = new JPasswordField(22);
            JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
            form.add(new JLabel("Создайте первого администратора этого родительского ПК."));
            form.add(new JLabel("Пароль хранится только в виде salted PBKDF2-хеша."));
            form.add(new JLabel("Имя пользователя:")); form.add(username);
            form.add(new JLabel("Пароль (не менее 10 символов):")); form.add(password);
            form.add(new JLabel("Повторите пароль:")); form.add(confirmation);
            int choice = JOptionPane.showConfirmDialog(parent, form, "Первый администратор",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return false;
            char[] first = password.getPassword();
            char[] second = confirmation.getPassword();
            try {
                if (!java.util.Arrays.equals(first, second)) {
                    JOptionPane.showMessageDialog(parent, "Пароли не совпадают.", "Не удалось создать аккаунт",
                            JOptionPane.WARNING_MESSAGE);
                    continue;
                }
                accounts.createFirstAdmin(username.getText(), first);
                first = null; // createFirstAdmin clears the supplied array.
                JOptionPane.showMessageDialog(parent, "Первый административный аккаунт создан.",
                        "Настройка завершена", JOptionPane.INFORMATION_MESSAGE);
                return true;
            } catch (IllegalArgumentException | SQLException error) {
                JOptionPane.showMessageDialog(parent, error.getMessage(), "Не удалось создать аккаунт",
                        JOptionPane.WARNING_MESSAGE);
            } finally {
                if (first != null) java.util.Arrays.fill(first, '\0');
                java.util.Arrays.fill(second, '\0');
                password.setText(""); confirmation.setText("");
            }
        }
    }
}
