package com.setretailx.printer.service.setretailx.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** Parsed UUID returned by GroupingPrintService.print. */
public record PrintJobResponseDto(String taskUuid) {
    public static PrintJobResponseDto from(JsonNode response) {
        JsonNode result = response == null ? null : response.get("result");
        if (result == null || !result.isArray() || result.size() < 2 || !result.get(1).isArray()) {
            throw new IllegalArgumentException("SetRetailX вернул некорректный ответ при создании задания печати.");
        }
        JsonNode uuids = result.get(1);
        if (uuids.isEmpty() || uuids.get(0).isNull() || uuids.get(0).asText().isBlank()) {
            throw new IllegalArgumentException("SetRetailX не вернул UUID задания печати.");
        }
        return new PrintJobResponseDto(uuids.get(0).asText());
    }
}
