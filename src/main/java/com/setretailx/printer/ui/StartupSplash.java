package com.setretailx.printer.ui;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;

/** Startup screen shown while the local catalog is optimized. */
@SuppressWarnings("serial")
public final class StartupSplash extends JFrame {
    private final JLabel message = new JLabel("Подготавливаем рабочее место…");

    public StartupSplash() {
        super("SetRetailX Printer");
        setUndecorated(true);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(520, 334);
        setLocationRelativeTo(null);

        BufferedImage image = loadBackground();
        JPanel background = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(image, 0, 0, getWidth(), getHeight(), null);
                g.dispose();
            }
        };
        background.setOpaque(true);

        JPanel caption = new JPanel(new BorderLayout());
        caption.setOpaque(false);
        caption.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 18, 14, 18));
        message.setForeground(Color.WHITE);
        message.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        message.setHorizontalAlignment(SwingConstants.LEFT);
        message.setUI(new javax.swing.plaf.basic.BasicLabelUI() {
            @Override protected void paintEnabledText(JLabel label, Graphics graphics, String text, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(new Color(0, 0, 0, 190));
                g.drawString(text, x + 1, y + 1);
                g.setColor(Color.WHITE);
                g.drawString(text, x, y);
                g.dispose();
            }
        });
        caption.add(message, BorderLayout.SOUTH);
        background.add(caption, BorderLayout.SOUTH);
        setContentPane(background);
    }

    private static BufferedImage loadBackground() {
        try (var stream = StartupSplash.class.getResourceAsStream("/images/startup-splash.png")) {
            return ImageIO.read(Objects.requireNonNull(stream, "Missing startup splash image"));
        } catch (IOException | NullPointerException error) {
            throw new IllegalStateException("Unable to load startup splash image", error);
        }
    }

    public void updateProgress(String text, int percent) {
        message.setText(text == null || text.isBlank() ? "Подготавливаем рабочее место…" : text);
    }
}
