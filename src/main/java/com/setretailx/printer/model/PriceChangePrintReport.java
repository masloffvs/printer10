package com.setretailx.printer.model;

import java.util.List;

/** A successfully submitted printed report containing selected price changes. */
public record PriceChangePrintReport(long id, String createdAt, List<PriceChangePrintEntry> entries) {
    public PriceChangePrintReport {
        createdAt = createdAt == null ? "" : createdAt;
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
