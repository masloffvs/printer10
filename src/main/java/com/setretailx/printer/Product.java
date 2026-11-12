package com.setretailx.printer;

/** The subset of a catalog product currently needed by lookup and the print queue. */
public record Product(
        String stCode,
        String name,
        String vendorCode,
        String barcode,
        String uom,
        String source,
        String price,
        String totalQuantity) {

    public Product {
        stCode = value(stCode);
        name = value(name).isEmpty() ? "Без названия" : value(name);
        vendorCode = value(vendorCode);
        barcode = value(barcode);
        uom = value(uom).isEmpty() ? "ST" : value(uom);
        source = value(source);
        price = value(price);
        totalQuantity = value(totalQuantity);
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }
}
