package com.setretailx.printer.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/** Catalog money value stored as an integer number of kopecks. */
public record Price(long kopecks) {
    public static Price fromDatabase(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new Price(new BigDecimal(value.trim()).setScale(0, RoundingMode.UNNECESSARY).longValueExact());
        } catch (NumberFormatException | ArithmeticException error) {
            throw new IllegalArgumentException("Некорректная цена в копейках: " + value, error);
        }
    }

    public String format() {
        NumberFormat formatter = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"));
        formatter.setMinimumFractionDigits(2);
        formatter.setMaximumFractionDigits(2);
        formatter.setGroupingUsed(false);
        return formatter.format(BigDecimal.valueOf(kopecks, 2)) + " ₽";
    }
}
