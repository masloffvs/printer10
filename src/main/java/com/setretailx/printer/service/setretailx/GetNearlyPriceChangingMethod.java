package com.setretailx.printer.service.setretailx;

import com.setretailx.printer.service.SetRetailXConnector;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Returns product markings flagged by SetRetailX as nearly changing price. */
public final class GetNearlyPriceChangingMethod {
    public CompletableFuture<List<String>> execute(SetRetailXConnector connector) {
        return execute(connector, 12);
    }

    public CompletableFuture<List<String>> execute(SetRetailXConnector connector, int shelf) {
        return new GetObjectsIdsMethod().execute(connector, shelf);
    }
}
