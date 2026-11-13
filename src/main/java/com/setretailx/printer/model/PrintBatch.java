package com.setretailx.printer.model;

import java.util.List;

/** A persisted print attempt with the indexes successfully submitted to SetRetailX. */
public record PrintBatch(long id, String createdAt, String status,
                         List<PrintHistoryEntry> entries, List<Integer> successfulIndexes) {
    public PrintBatch {
        entries = List.copyOf(entries);
        successfulIndexes = List.copyOf(successfulIndexes);
    }
}
