package com.setretailx.printer.service.setretailx.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.setretailx.printer.model.Product;

public record SimpleProductDto(
        String identifier,
        String name,
        String vendorCode,
        String uom,
        String barcode,
        String barCode,
        String rawJson) {

    public SimpleProductDto(String identifier, String name, String vendorCode, String uom,
                            String barcode, String barCode) {
        this(identifier, name, vendorCode, uom, barcode, barCode, "");
    }

    public static SimpleProductDto from(JsonNode value) {
        return new SimpleProductDto(
                text(value, "identifier"),
                text(value, "name"),
                text(value, "vendorCode"),
                text(value, "uom"),
                text(value, "barcode"),
                text(value, "barCode"),
                value == null ? "" : value.toPrettyString());
    }

    /** Maps the fields needed for online product lookup into the application's Product model. */
    public Product toProduct(String searchedBarcode) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Товар найден онлайн, но сервер не вернул identifier для "
                    + searchedBarcode + ".");
        }
        String resultBarcode = nonBlank(barcode) ? barcode : searchedBarcode;
        return new Product(identifier, nonBlank(name) ? name : "Без названия", value(vendorCode),
                value(resultBarcode), nonBlank(uom) ? uom : "ST", "online", null, "", null, rawJson);
    }

    public String matchingBarcode() {
        return nonBlank(barcode) ? barcode : value(barCode);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    private static String value(String value) { return value == null ? "" : value; }
}
