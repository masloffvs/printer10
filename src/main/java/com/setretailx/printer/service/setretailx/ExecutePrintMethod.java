package com.setretailx.printer.service.setretailx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.setretailx.printer.service.SetRetailXConnector;
import com.setretailx.printer.service.setretailx.dto.ExecutePrintResponseDto;

import java.util.concurrent.CompletableFuture;

/** Confirms a queued SetRetailX print job by issuing execute with START. */
public final class ExecutePrintMethod {
    private static final String ENDPOINT = "java:app/SET-PrintPriceTags/PrintingBoxManagerBean!"
            + "ru.crystals.setretailx.printing.protocol.manager.PrintingBoxManager";
    private static final ObjectMapper JSON = new ObjectMapper();

    public CompletableFuture<ExecutePrintResponseDto> execute(SetRetailXConnector connector, String taskUuid) {
        if (connector == null) throw new IllegalArgumentException("SetRetailXConnector не задан.");
        if (taskUuid == null || taskUuid.isBlank()) throw new IllegalArgumentException("UUID задания печати не задан.");

        ObjectNode payload = JSON.createObjectNode();
        payload.put("@class", "ru.crystals.commons.jsonrpc.domain.JsonRpcRequest");
        payload.put("id", 63);
        payload.put("version", "1.0.0");
        payload.put("endpoint", ENDPOINT);
        payload.put("method", "execute");
        ArrayNode params = payload.putArray("params");
        params.add(taskUuid);
        params.add("START");

        return connector.call(payload).thenApply(ExecutePrintResponseDto::from);
    }
}
