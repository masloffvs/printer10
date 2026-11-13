package com.setretailx.printer.service.setretailx.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** Typed result of confirming a SetRetailX print job with execute/START. */
public record ExecutePrintResponseDto(boolean started) {
    public static ExecutePrintResponseDto from(JsonNode response) {
        JsonNode error = response == null ? null : response.get("error");
        return new ExecutePrintResponseDto(error == null || error.isNull());
    }
}
