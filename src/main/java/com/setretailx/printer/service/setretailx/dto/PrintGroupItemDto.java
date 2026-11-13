package com.setretailx.printer.service.setretailx.dto;

import com.setretailx.printer.model.Product;

/** A queued product and its quantity in a SetRetailX print basket. */
public record PrintGroupItemDto(String stCode, String vendorCode, String uom, int quantity) {
    public PrintGroupItemDto {
        stCode = stCode == null ? "" : stCode;
        vendorCode = vendorCode == null ? "" : vendorCode;
        uom = uom == null || uom.isBlank() ? "ST" : uom;
    }

    public static PrintGroupItemDto from(Product product, int quantity) {
        if (product == null) throw new IllegalArgumentException("Товар для печати не задан.");
        return new PrintGroupItemDto(product.stCode(), product.vendorCode(), product.uom(), quantity);
    }

    /** SetRetailX uses vendor code plus UOM when available, otherwise the product identifier. */
    public String basketCode() {
        return vendorCode.isEmpty() ? stCode : vendorCode + "_" + uom;
    }
}
