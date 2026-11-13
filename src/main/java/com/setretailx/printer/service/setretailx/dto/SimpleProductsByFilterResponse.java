package com.setretailx.printer.service.setretailx.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.setretailx.printer.model.Product;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record SimpleProductsByFilterResponse(List<SimpleProductDto> products) {
    public SimpleProductsByFilterResponse {
        products = List.copyOf(products);
    }

    public static SimpleProductsByFilterResponse from(JsonNode response) {
        JsonNode result = response == null ? null : response.get("result");
        if (result == null || !result.isArray() || result.size() < 2 || !result.get(1).isArray()) {
            throw new IllegalArgumentException("Некорректный ответ сервера getSimpleProductsByFilter.");
        }
        List<SimpleProductDto> products = new ArrayList<>();
        for (JsonNode value : result.get(1)) {
            if (value != null && value.isObject()) products.add(SimpleProductDto.from(value));
        }
        return new SimpleProductsByFilterResponse(products);
    }

    /** Returns an exact barcode match when present, otherwise the first server result. */
    public Optional<SimpleProductDto> select(String barcode) {
        if (products.isEmpty()) return Optional.empty();
        String searched = barcode == null ? "" : barcode;
        for (SimpleProductDto product : products) {
            if (searched.equals(product.matchingBarcode())) return Optional.of(product);
        }
        return Optional.of(products.get(0));
    }

    /** Selects a result using search_online's barcode preference and maps it to Product. */
    public Optional<Product> toProduct(String barcode) {
        return select(barcode).map(product -> product.toProduct(barcode));
    }
}
