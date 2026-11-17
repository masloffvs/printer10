package com.setretailx.printer;

import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.repository.ProductRepository;
import com.setretailx.printer.ui.MainWindow;
import com.setretailx.printer.ui.StartupSplash;

import com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.util.List;

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
                    splash.updateProgress("Запускаем рабочее пространство…", 100);
                    MainWindow mainWindow = new MainWindow();
                    splash.dispose();
                    mainWindow.setVisible(true);
                } catch (Exception error) {
                    Throwable cause = error;
                    while (cause.getCause() != null) cause = cause.getCause();
                    JOptionPane.showMessageDialog(splash,
                            "Не удалось подготовить базы данных или локальный каталог товаров:\n"
                                    + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage())
                                    + "\n\nПриложение не будет запущено, чтобы не использовать устаревшую схему.",
                            "Проблема при запуске", JOptionPane.ERROR_MESSAGE);
                    splash.dispose();
                }
            }
        }.execute();
    }
}
