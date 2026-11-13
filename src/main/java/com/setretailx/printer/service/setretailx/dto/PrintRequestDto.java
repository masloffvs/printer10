package com.setretailx.printer.service.setretailx.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** JSON-RPC parameter matching SetRetailX's GroupingPrintService PrintRequestVO. */
public record PrintRequestDto(
        int shelf,
        List<Object> filters,
        List<GroupingNodeDto> nodes,
        List<String> basket,
        @JsonProperty("isDefault") boolean isDefault,
        @JsonProperty("isFormat") boolean isFormat,
        Map<String, Object> substitutions,
        List<PrintingParamsDto> params,
        long date,
        Integer numberOfCopies,
        @JsonProperty("isSeparateContext") boolean isSeparateContext) {

    public PrintRequestDto {
        filters = List.copyOf(filters);
        nodes = List.copyOf(nodes);
        basket = List.copyOf(basket);
        substitutions = Map.copyOf(substitutions);
        params = List.copyOf(params);
    }

    @JsonProperty("@class")
    public String className() { return "ru.crystals.setretailx.grouping.vo.PrintRequestVO"; }
}
