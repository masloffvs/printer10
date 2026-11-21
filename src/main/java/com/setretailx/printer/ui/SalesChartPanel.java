package com.setretailx.printer.ui;

import com.setretailx.printer.service.PriceMonitoringTaskService.SalesDay;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/** Standalone chart view used by the sales-history MDI window. */
@SuppressWarnings("serial")
final class SalesChartPanel extends JPanel {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");
    private static final NumberFormat MONEY = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("ru-RU"));
    private List<SalesDay> days;

    SalesChartPanel(List<SalesDay> days) {
        this.days = days == null ? List.of() : List.copyOf(days);
        setPreferredSize(new Dimension(760, 430));
        setMinimumSize(new Dimension(420, 260));
        setBorder(BorderFactory.createTitledBorder("Выручка по дням · выбранные артикулы"));
    }

    void setDays(List<SalesDay> days) {
        this.days = days == null ? List.of() : List.copyOf(days);
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (days.isEmpty()) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int left = 78, right = 18, top = 24, bottom = 34;
            int width = getWidth() - left - right, height = getHeight() - top - bottom;
            if (width <= 0 || height <= 0) return;
            double max = days.stream().mapToDouble(SalesDay::revenue).max().orElse(1);
            if (max <= 0) max = 1;
            Color foreground = UIManager.getColor("Label.foreground");
            g.setColor(foreground == null ? getForeground() : foreground);
            g.drawLine(left, top + height, left + width, top + height);
            int count = Math.min(days.size(), Math.max(1, width / 38));
            double slot = (double) width / count;
            for (int i = 0; i < count; i++) {
                int index = count == 1 ? 0
                        : (int) Math.round((double) (count - i - 1) * (days.size() - 1) / (count - 1));
                SalesDay value = days.get(index);
                int barHeight = (int) Math.round(height * value.revenue() / max);
                int barWidth = Math.max(4, (int) (slot * .64));
                int x = left + (int) (i * slot + (slot - barWidth) / 2);
                g.setColor(new Color(55, 155, 245));
                g.fillRoundRect(x, top + height - barHeight, barWidth, Math.max(1, barHeight), 5, 5);
                g.setColor(foreground == null ? getForeground() : foreground);
                if (count <= 16) g.drawString(Instant.ofEpochSecond(value.dayEpochSeconds())
                        .atZone(ZoneId.systemDefault()).format(DAY), x - 4, top + height + 19);
            }
            g.drawString(MONEY.format(max), 5, top + 10);
            g.drawString("0 ₽", 30, top + height);
        } finally { g.dispose(); }
    }
}
