package com.setretailx.printer.ui;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Compact startup screen shown while the local catalog is optimized. */
@SuppressWarnings("serial")
public final class StartupSplash extends JFrame {
    private final JLabel message = new JLabel("Подготавливаем рабочее место…");
    private final JProgressBar progress = new JProgressBar(0, 100);

    public StartupSplash() {
        super("SetRetailX Printer");
        setUndecorated(true);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(520, 280);
        setLocationRelativeTo(null);

        JPanel background = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setPaint(new GradientPaint(0, 0, new Color(25, 31, 46), getWidth(), getHeight(),
                        new Color(14, 18, 29)));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 22, 22);
                g.dispose();
            }
        };
        background.setOpaque(false);
        background.setBorder(BorderFactory.createEmptyBorder(34, 42, 30, 42));

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));

        JLabel mark = new JLabel("S", SwingConstants.CENTER);
        mark.setForeground(new Color(255, 255, 255));
        mark.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 25));
        mark.setOpaque(true);
        mark.setBackground(new Color(77, 126, 255));
        mark.setPreferredSize(new Dimension(46, 46));
        mark.setMaximumSize(new Dimension(46, 46));
        mark.setAlignmentX(0.5f);

        JLabel title = new JLabel("SetRetailX Printer");
        title.setForeground(new Color(245, 247, 255));
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 23));
        title.setAlignmentX(0.5f);
        JLabel subtitle = new JLabel("Подготавливаем приложение к работе");
        subtitle.setForeground(new Color(164, 174, 196));
        subtitle.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        subtitle.setAlignmentX(0.5f);

        message.setForeground(new Color(212, 220, 237));
        message.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        message.setAlignmentX(0.0f);
        message.setBorder(BorderFactory.createEmptyBorder(24, 0, 9, 0));

        progress.setStringPainted(false);
        progress.setIndeterminate(true);
        progress.setForeground(new Color(89, 139, 255));
        progress.setBackground(new Color(45, 53, 71));
        progress.setBorderPainted(false);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 7));
        progress.setPreferredSize(new Dimension(430, 7));
        progress.setAlignmentX(0.0f);

        content.add(mark);
        content.add(javax.swing.Box.createVerticalStrut(14));
        content.add(title);
        content.add(javax.swing.Box.createVerticalStrut(5));
        content.add(subtitle);
        content.add(javax.swing.Box.createVerticalStrut(16));
        content.add(message);
        content.add(progress);
        background.add(content, BorderLayout.CENTER);
        setContentPane(background);
    }

    public void updateProgress(String text, int percent) {
        message.setText(text == null || text.isBlank() ? "Подготавливаем рабочее место…" : text);
        if (percent < 0) {
            progress.setIndeterminate(true);
        } else {
            progress.setIndeterminate(false);
            progress.setValue(Math.max(0, Math.min(100, percent)));
        }
    }
}
