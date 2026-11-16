package com.setretailx.printer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.service.setretailx.GetNearlyPriceChangingMethod;
import com.setretailx.printer.service.setretailx.GetObjectsIdsMethod;
import com.setretailx.printer.service.setretailx.GetProductsMethod;
import com.setretailx.printer.service.setretailx.PrintGroupMethod;
import com.setretailx.printer.service.setretailx.GetSimpleProductsByFilterMethod;
import com.setretailx.printer.service.setretailx.dto.PrintGroupItemDto;
import com.setretailx.printer.service.setretailx.dto.PrintGroupResponseDto;
import com.setretailx.printer.service.setretailx.dto.ProductDto;
import com.setretailx.printer.model.Product;
import com.setretailx.printer.service.setretailx.dto.SimpleProductsByFilterResponse;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** The single application-facing entry point for SetRetailX operations. */
public final class SetRetailXConnector implements AutoCloseable {
    private static final String CHECK_ENDPOINT = "java:app/SET-OperDay/SET/CheckManager!"
            + "ru.crystals.operday.gui.flexendpoints.check.CheckManagerLocal";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Consumer<ExchangeEvent> eventHandler;
    private volatile SetRetailXClient transport;

    public SetRetailXConnector(Consumer<ExchangeEvent> eventHandler) {
        this.eventHandler = eventHandler == null ? ignored -> { } : eventHandler;
        transport = new SetRetailXClient(this.eventHandler);
    }

    SetRetailXConnector(SetRetailXClient transport) {
        this.eventHandler = ignored -> { };
        this.transport = transport;
    }

    /** Reloads connection settings and starts a fresh transport/session. */
    public synchronized void reloadSettings() {
        SetRetailXClient previous = transport;
        transport = new SetRetailXClient(eventHandler);
        previous.close();
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

    /** Lists receipt summaries for a time range; the transport supplies the session token. */
    public CompletableFuture<JsonNode> findChecks(Instant from, Instant to, int limit) {
        ArrayNode arguments = JSON.createArrayNode();
        addCheckSearchArgument(arguments, "CheckType",
                "CHECK,REFUND_CHECK,CANCEL_CHECK,DEFERRED_CHECK,EXPENSE_RECEIPT,RETURN_EXPENSE_RECEIPT");
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.format.DateTimeFormatter dateFormat =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        addCheckSearchArgument(arguments, "CheckCommitBeginDate",
                dateFormat.format(from.atZone(zone).toLocalDateTime()));
        addCheckSearchArgument(arguments, "CheckCommitEndDate",
                dateFormat.format(to.atZone(zone).toLocalDateTime()));
        return call(CHECK_ENDPOINT, "findChecks", List.of(arguments, Math.max(1, limit)));
    }

    private static void addCheckSearchArgument(ArrayNode arguments, String type, String value) {
        ObjectNode argument = arguments.addObject();
        argument.put("type", type);
        argument.put("value", value);
        argument.put("@class", "ru.crystals.operday.vo.SearchArgumentVO");
    }

    /** Loads a receipt's complete positions array, not its duplicate discountInPositions list. */
    public CompletableFuture<JsonNode> findCheck(long checkId) {
        return call(CHECK_ENDPOINT, "findCheck", List.of(checkId));
    }

    public CompletableFuture<List<String>> getObjectsIds() {
        return new GetObjectsIdsMethod().execute(this);
    }

    public CompletableFuture<List<String>> getNearlyPriceChanging() {
        return getNearlyPriceChanging(12);
    }

    public CompletableFuture<List<String>> getNearlyPriceChanging(int shelf) {
        return new GetNearlyPriceChangingMethod().execute(this, shelf);
    }

    public CompletableFuture<List<ProductDto>> getProducts(List<String> markings) {
        return getProducts(markings, 12);
    }

    public CompletableFuture<List<ProductDto>> getProducts(List<String> markings, int shelf) {
        return new GetProductsMethod().execute(this, markings, shelf);
    }

    public CompletableFuture<Optional<Product>> searchOnlineProduct(String barcode) {
        return new GetSimpleProductsByFilterMethod().execute(this, barcode)
                .thenApply(response -> response.toProduct(barcode));
    }

    public CompletableFuture<PrintGroupResponseDto> printGroup(
            AppConfig.PrintFormat format, int amountToPrint, List<PrintGroupItemDto> items) {
        return new PrintGroupMethod().execute(this, format, amountToPrint, items);
    }

    /** Compatibility name for the exchange screen's connection check. */
    public CompletableFuture<List<String>> checkConnection() {
        return getObjectsIds();
    }

    @Override public void close() {
        transport.close();
    }
}
