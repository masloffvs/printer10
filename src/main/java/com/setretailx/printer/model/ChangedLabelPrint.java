package com.setretailx.printer.model;

/** Successfully printed label for a product selected from the price-change history. */
public record ChangedLabelPrint(long id, String printedAt, PrintHistoryEntry entry) { }
