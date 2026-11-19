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
import java.util.Collection;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** The single application-facing entry point for SetRetailX operations. */
public final class SetRetailXConnector implements AutoCloseable {
    private static final String CHECK_ENDPOINT = "java:app/SET-OperDay/SET/CheckManager!"
            + "ru.crystals.operday.gui.flexendpoints.check.CheckManagerLocal";
    private static final ObjectMapper JSON = new ObjectMapper();
    private volatile Consumer<ExchangeEvent> eventHandler;
    private volatile SetRetailXClient transport;

    private static final class InstanceHolder {
        private static final SetRetailXConnector INSTANCE = new SetRetailXConnector(ignored -> { });
    }

    /** Returns the application-wide connector, optionally installing its diagnostics listener. */
    public static SetRetailXConnector getInstance(Consumer<ExchangeEvent> eventHandler) {
        if (AppConfig.nodeRole() != AppConfig.NodeRole.PARENT) {
            throw new IllegalStateException("Учётные данные и подключения SetRetailX доступны только на родительском ПК.");
        }
        SetRetailXConnector instance = InstanceHolder.INSTANCE;
        if (eventHandler != null) instance.setEventHandler(eventHandler);
        return instance;
    }

    public static SetRetailXConnector getInstance() { return getInstance(null); }

    public synchronized void setEventHandler(Consumer<ExchangeEvent> eventHandler) {
        this.eventHandler = eventHandler == null ? ignored -> { } : eventHandler;
    }

    private SetRetailXConnector(Consumer<ExchangeEvent> eventHandler) {
        this.eventHandler = eventHandler == null ? ignored -> { } : eventHandler;
        transport = new SetRetailXClient(this::dispatch);
    }

    SetRetailXConnector(SetRetailXClient transport) {
        this.eventHandler = ignored -> { };
        this.transport = transport;
    }

    private SetRetailXConnector() {
        this.eventHandler = ignored -> { };
        this.transport = null;
    }

    /** A local-only connector for child nodes; it never reads or creates SetRetailX credentials. */
    public static SetRetailXConnector unavailableOnChild() {
        if (AppConfig.nodeRole() != AppConfig.NodeRole.CHILD) {
            throw new IllegalStateException("Отключённый коннектор предназначен только для дочернего узла.");
        }
        return new SetRetailXConnector();
    }

    /** Reloads connection settings and starts a fresh transport/session. */
    public synchronized void reloadSettings() {
        if (transport == null) {
            throw new IllegalStateException("Подключение SetRetailX на дочернем узле отключено.");
        }
        SetRetailXClient previous = transport;
        transport = new SetRetailXClient(this::dispatch);
        previous.close();
    }

    private void dispatch(ExchangeEvent event) {
        try { eventHandler.accept(event); }
        catch (RuntimeException ignored) { /* Diagnostics must not affect API operations. */ }
    }

    public CompletableFuture<Void> connect() {
        return transport == null ? unavailable() : transport.connect();
    }

    /** Low-level JSON-RPC call for operation classes; UI code should use typed operations. */
    public CompletableFuture<JsonNode> call(String endpoint, String method, List<?> params) {
        return transport == null ? unavailable() : transport.call(endpoint, method, params);
    }

    /** Sends a pre-built JSON-RPC payload for operation classes that need custom request fields. */
    public CompletableFuture<JsonNode> call(ObjectNode payload) {
        return transport == null ? unavailable() : transport.call(payload);
    }

    private static <T> CompletableFuture<T> unavailable() {
        return CompletableFuture.failedFuture(new IllegalStateException(
                "SetRetailX доступен только через родительский ПК; локальное подключение на дочернем узле отключено."));
    }

    /** Lists receipt summaries for a time range; the transport supplies the session token. */
    public CompletableFuture<JsonNode> findChecks(Instant from, Instant to, int limit) {
        return findChecks(from, to, limit, List.of());
    }

    /** Lists receipts for a time range and filters them by configured product item/barcode keys. */
    public CompletableFuture<JsonNode> findChecks(Instant from, Instant to, int limit,
                                                   Collection<String> productItemOrBarcodes) {
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
        if (productItemOrBarcodes != null && !productItemOrBarcodes.isEmpty()) {
            String items = productItemOrBarcodes.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(","));
            if (!items.isEmpty()) addCheckSearchArgument(arguments, "ProductItemOrBarcode", items);
        }
        return call(CHECK_ENDPOINT, "findChecks", List.of(arguments, Math.max(1, limit)));
    }

    private static void addCheckSearchArgument(ArrayNode arguments, String type, String value) {
        ObjectNode argument = arguments.addObject();
        argument.put("type", type);
        argument.put("value", value);
        argument.put("@class", "ru.crystals.operday.vo.SearchArgumentVO");
    }

    /** Loads a receipt's complete positions array using the Check DTO expected by SetRetailX. */
    public CompletableFuture<JsonNode> findCheck(JsonNode checkSummary) {
        if (checkSummary == null || !checkSummary.isObject()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Для findCheck требуется объект чека из findChecks."));
        }
        ObjectNode check = ((ObjectNode) checkSummary).deepCopy();
        check.put("@class", "ru.crystals.operday.gui.datastructs.check.Check");
        return call(CHECK_ENDPOINT, "findCheck", List.of(check));
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
        if (transport != null) transport.close();
    }
}
