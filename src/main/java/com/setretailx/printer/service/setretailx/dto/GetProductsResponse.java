package com.setretailx.printer.service.setretailx.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public record GetProductsResponse(List<ProductDto> products) {
    public GetProductsResponse {
        products = List.copyOf(products);
    }

    public static GetProductsResponse from(JsonNode response) {
        JsonNode result = response == null ? null : response.get("result");
        if (result == null || !result.isArray()) {
            throw new IllegalArgumentException("SetRetailX вернул некорректный ответ getProducts.");
        }
        JsonNode values = result.size() >= 2 && result.get(1).isArray() ? result.get(1) : result;
        List<ProductDto> products = new ArrayList<>();
        for (JsonNode product : values) {
            if (!product.isObject()) {
                throw new IllegalArgumentException("SetRetailX вернул некорректный список товаров.");
            }
            products.add(ProductDto.from(product));
        }
        return new GetProductsResponse(products);
    }
}
