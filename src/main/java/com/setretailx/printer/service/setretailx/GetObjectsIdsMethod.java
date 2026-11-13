package com.setretailx.printer.service.setretailx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.setretailx.printer.service.SetRetailXConnector;
import com.setretailx.printer.service.setretailx.dto.GetObjectsIdsResponse;
import com.setretailx.printer.service.setretailx.dto.GroupingObjectsFilterDto;
import com.setretailx.printer.service.setretailx.dto.GroupingNodeDto;
import com.setretailx.printer.service.setretailx.dto.PrintModeDto;
import com.setretailx.printer.service.setretailx.dto.SearchArgumentDto;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** SetRetailX getObjectsIds JSON-RPC operation used to verify the session. */
public final class GetObjectsIdsMethod {
    private static final String ENDPOINT = "java:app/SET-PrintPriceTags/GroupingTreeServiceBean!"
            + "ru.crystals.setretailx.grouping.api.GroupingTreeService";
    private static final ObjectMapper JSON = new ObjectMapper();

    public CompletableFuture<List<String>> execute(SetRetailXConnector connector) {
        return execute(connector, 12);
    }

    public CompletableFuture<List<String>> execute(SetRetailXConnector connector, int shelf) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("@class", "ru.crystals.commons.jsonrpc.domain.JsonRpcRequest");
        payload.put("id", 953);
        payload.put("version", "1.0.0");
        payload.put("endpoint", ENDPOINT);
        payload.put("method", "getObjectsIds");
        ArrayNode params = payload.putArray("params");
        params.add(JSON.valueToTree(new PrintModeDto(shelf)));
        params.add(JSON.valueToTree(new GroupingObjectsFilterDto(
                List.of(new SearchArgumentDto("NearlyPriceChanging")), List.of(new GroupingNodeDto()))));

        return connector.call(payload).thenApply(GetObjectsIdsResponse::from).thenApply(GetObjectsIdsResponse::objectIds);
    }
}
