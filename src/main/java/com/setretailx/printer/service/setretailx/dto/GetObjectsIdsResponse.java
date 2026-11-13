package com.setretailx.printer.service.setretailx.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public record GetObjectsIdsResponse(List<String> objectIds) {
    public GetObjectsIdsResponse {
        objectIds = List.copyOf(objectIds);
    }

    public static GetObjectsIdsResponse from(JsonNode response) {
        JsonNode result = response == null ? null : response.get("result");
        if (result == null || !result.isArray()) {
            throw new IllegalArgumentException("SetRetailX вернул некорректный ответ getObjectsIds.");
        }
        JsonNode values = result.size() >= 2 && result.get(1).isArray() ? result.get(1) : result;
        List<String> ids = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isNull() && !value.asText().isBlank()) ids.add(value.asText());
        }
        return new GetObjectsIdsResponse(ids);
    }
}
