package com.setretailx.printer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.setretailx.printer.service.setretailx.GetObjectsIdsMethod;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** The single application-facing entry point for SetRetailX operations. */
public final class SetRetailXConnector implements AutoCloseable {
    private final SetRetailXClient transport;

    public SetRetailXConnector(Consumer<ExchangeEvent> eventHandler) {
        transport = new SetRetailXClient(eventHandler);
    }

    SetRetailXConnector(SetRetailXClient transport) {
        this.transport = transport;
    }

    public CompletableFuture<Void> connect() {
        return transport.connect();
    }

    /** Low-level JSON-RPC call for operation classes; UI code should use typed operations. */
    public CompletableFuture<JsonNode> call(String endpoint, String method, List<?> params) {
        return transport.call(endpoint, method, params);
    }

    /** Sends a pre-built JSON-RPC payload for operation classes that need custom request fields. */
    public CompletableFuture<JsonNode> call(ObjectNode payload) {
        return transport.call(payload);
    }

    public CompletableFuture<List<String>> getObjectsIds() {
        return new GetObjectsIdsMethod().execute(this);
    }

    /** Compatibility name for the exchange screen's connection check. */
    public CompletableFuture<List<String>> checkConnection() {
        return getObjectsIds();
    }

    @Override public void close() {
        transport.close();
    }
}
