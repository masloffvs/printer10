package com.setretailx.printer.model;

import com.setretailx.printer.config.AppConfig;

/** Product snapshot and queue settings persisted as one item in a print-history batch. */
public record PrintHistoryEntry(Product product, String formatKey, Integer amount, int quantity,
                                PriceType priceType) {
    public PrintHistoryEntry {
        if (product == null) throw new IllegalArgumentException("Товар истории не задан.");
        formatKey = AppConfig.normalizeFormatKey(formatKey);
        quantity = Math.max(1, quantity);
        priceType = priceType == null ? PriceType.PROMO : priceType;
    }

    /** Backward-compatible constructor for old callers and historical entries. */
    public PrintHistoryEntry(Product product, String formatKey, Integer amount, int quantity) {
        this(product, formatKey, amount, quantity, PriceType.PROMO);
    }

    public static PrintHistoryEntry fromQueue(Product product, AppConfig.PrintFormat format,
                                               Integer amount, int quantity, PriceType priceType) {
        return new PrintHistoryEntry(product, format == null ? "" : format.key(), amount, quantity,
                priceType);
    }
}
