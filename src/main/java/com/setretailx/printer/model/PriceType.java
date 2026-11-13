package com.setretailx.printer.model;

import java.util.Locale;

/** The two normalized price-label variants supported by every format. */
public enum PriceType {
    PROMO("Акционный"),
    REGULAR("Безакционный");

    private final String title;

    PriceType(String title) { this.title = title; }

    public String title() { return title; }

    public static PriceType fromStoredValue(String value) {
        if (value == null || value.isBlank()) return PROMO;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "regular", "normal", "обычный", "безакционный", "без акции", "false", "0" -> REGULAR;
            default -> PROMO;
        };
    }

    @Override public String toString() { return title; }
}
