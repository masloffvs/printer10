package com.setretailx.printer.service.setretailx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.setretailx.printer.service.SetRetailXConnector;
import com.setretailx.printer.service.setretailx.dto.ProductsFilterDto;
import com.setretailx.printer.service.setretailx.dto.SimpleProductsByFilterResponse;

import java.util.concurrent.CompletableFuture;

/** SetRetailX online product lookup using getSimpleProductsByFilter. */
public final class GetSimpleProductsByFilterMethod {
    private static final String ENDPOINT = "java:app/SET-Products/SET/ProductsFinder!"
            + "ru.crystals.setretailx.products.ProductManagerFinderLocal";
    private static final ObjectMapper JSON = new ObjectMapper();

    public CompletableFuture<SimpleProductsByFilterResponse> execute(
            SetRetailXConnector connector, String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Штрихкод для поиска не задан."));
        }

        ObjectNode payload = JSON.createObjectNode();
        payload.put("@class", "ru.crystals.commons.jsonrpc.domain.JsonRpcRequest");
        payload.put("id", 73);
        payload.put("version", "1.0.0");
        payload.put("endpoint", ENDPOINT);
        payload.put("method", "getSimpleProductsByFilter");
        ArrayNode params = payload.putArray("params");
        // The authenticated connector supplies the session context; the operation only specifies filter/paging.
        params.add(JSON.valueToTree(new ProductsFilterDto("use_like:%" + barcode + "%", 99)));
        params.add(0);
        params.add(20);

        return connector.call(payload).thenApply(SimpleProductsByFilterResponse::from);
    }
}
