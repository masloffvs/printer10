package com.setretailx.printer.service.setretailx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.setretailx.printer.service.SetRetailXConnector;
import com.setretailx.printer.service.setretailx.dto.GetProductsResponse;
import com.setretailx.printer.service.setretailx.dto.ProductDto;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** SetRetailX getProducts JSON-RPC operation used to capture product price snapshots. */
public final class GetProductsMethod {
    private static final String ENDPOINT = "java:app/SET-PrintPriceTags/PriceTagFacadeBean!"
            + "ru.crystals.setretailx.printing.common.api.PriceTagFacadeLocal";
    private static final ObjectMapper JSON = new ObjectMapper();

    public CompletableFuture<List<ProductDto>> execute(SetRetailXConnector connector, List<String> markings) {
        return execute(connector, markings, 12);
    }

    public CompletableFuture<List<ProductDto>> execute(
            SetRetailXConnector connector, List<String> markings, int shelf) {
        if (markings == null || markings.isEmpty()) return CompletableFuture.completedFuture(List.of());

        ObjectNode payload = JSON.createObjectNode();
        payload.put("@class", "ru.crystals.commons.jsonrpc.domain.JsonRpcRequest");
        payload.put("id", 954);
        payload.put("version", "1.0.0");
        payload.put("endpoint", ENDPOINT);
        payload.put("method", "getProducts");
        ArrayNode params = payload.putArray("params");
        params.add(JSON.valueToTree(markings));
        params.add(shelf);

        return connector.call(payload)
                .thenApply(GetProductsResponse::from)
                .thenApply(GetProductsResponse::products);
    }
}
