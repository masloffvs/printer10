package com.setretailx.printer;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class PrinterApp {
    private PrinterApp() { }

    public static void main(String[] args) {
        AppConfig.loadDotEnv();
        FlatDarkLaf.setup();
        UIManager.put("Button.arc", 10);
        UIManager.put("Component.arc", 8);
        SwingUtilities.invokeLater(() -> new MainWindow().setVisible(true));
    }
}
