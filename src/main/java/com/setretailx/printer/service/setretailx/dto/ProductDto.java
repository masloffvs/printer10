package com.setretailx.printer.service.setretailx.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Product fields consumed by long-running price snapshots. Price entries remain raw JSON values. */
public record ProductDto(
        String marking,
        String fullName,
        String name,
        String barCode,
        String barcode,
        Map<String, JsonNode> pricesMap,
        Map<String, JsonNode> prices99Map) {

    public ProductDto {
        pricesMap = immutableJsonMap(pricesMap);
        prices99Map = immutableJsonMap(prices99Map);
    }

    public static ProductDto from(JsonNode product) {
        return new ProductDto(
                textOrNull(product, "marking"),
                textOrNull(product, "fullName"),
                textOrNull(product, "name"),
                textOrNull(product, "barCode"),
                textOrNull(product, "barcode"),
                jsonMap(product.get("pricesMap")),
                jsonMap(product.get("prices99Map")));
    }

    private static String textOrNull(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Map<String, JsonNode> jsonMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) return Map.of();
        Map<String, JsonNode> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().deepCopy()));
        return values;
    }

    private static Map<String, JsonNode> immutableJsonMap(Map<String, JsonNode> values) {
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key, value == null ? null : value.deepCopy()));
        return Collections.unmodifiableMap(copy);
    }
}
