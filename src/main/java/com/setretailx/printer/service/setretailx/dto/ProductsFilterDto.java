package com.setretailx.printer.service.setretailx.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProductsFilterDto(String good, int productStatusCode) {
    @JsonProperty("@class")
    public String className() { return "ru.crystals.setretailx.products.common.ProductsFilter"; }
}
