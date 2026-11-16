package com.setretailx.printer.model;

/** One price-change row included in a printed change report. */
public record PriceChangePrintEntry(String article, String product, String barcode, String priceType,
                                    String oldPrice, String newPrice, String delta) {
    public PriceChangePrintEntry {
        article = value(article);
        product = value(product);
        barcode = value(barcode);
        priceType = value(priceType);
        oldPrice = value(oldPrice);
        newPrice = value(newPrice);
        delta = value(delta);
    }

    private static String value(String text) { return text == null ? "" : text; }
}
