package com.setretailx.printer.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Normalized payload emitted by the shared scanner input. */
public record ScannedProbe(
        String barcode,
        String price1,
        String price2,
        String price3,
        String price4,
        String stcode,
        String russianMark) {

    private static final Pattern FIELD = Pattern.compile(
            "(?:barcode|price[1-4]|stcode|russian_mark)=", Pattern.CASE_INSENSITIVE);

    public ScannedProbe {
        barcode = clean(barcode);
        price1 = clean(price1);
        price2 = clean(price2);
        price3 = clean(price3);
        price4 = clean(price4);
        stcode = clean(stcode);
        russianMark = clean(russianMark);
    }

    public static ScannedProbe fromText(String input) {
        String text = input == null ? "" : input.trim();
        String extractedBarcode = BarInput.barcode(text).orElse("");
        text = text.replaceAll("\\s*[\\r\\n?]+\\s*", "&");
        Matcher firstField = FIELD.matcher(text);
        if (firstField.find()) {
            text = text.substring(firstField.start());
        }

        Map<String, String> fields = new LinkedHashMap<>();
        for (String name : new String[]{"barcode", "price1", "price2", "price3", "price4", "stcode", "russian_mark"}) {
            fields.put(name, "");
        }
        for (String part : text.split("&", -1)) {
            int equals = part.indexOf('=');
            if (equals < 0) continue;
            String keyText = BarInput.keyboardLayoutToLatin(decodeSafely(part.substring(0, equals)))
                    .trim().toLowerCase(Locale.ROOT);
            Matcher recognizedKey = Pattern.compile("(?:barcode|price[1-4]|stcode|russian_mark)$").matcher(keyText);
            if (!recognizedKey.find()) continue;
            String key = recognizedKey.group();
            String value = decodeSafely(part.substring(equals + 1)).trim();
            if (key.startsWith("price")) value = BarInput.keyboardLayoutToLatin(value);
            if (fields.containsKey(key)) fields.put(key, value);
        }
        if (fields.values().stream().allMatch(String::isEmpty)
                && !text.isEmpty() && !text.contains("=") && !text.contains("&")) {
            fields.put("barcode", extractedBarcode.isEmpty()
                    ? (text.startsWith("]C1") ? "" : text) : extractedBarcode);
        }
        if (fields.get("barcode").isEmpty()) fields.put("barcode", extractedBarcode);
        else fields.put("barcode", BarInput.barcode(fields.get("barcode")).orElse(""));
        return new ScannedProbe(fields.get("barcode"), fields.get("price1"), fields.get("price2"),
                fields.get("price3"), fields.get("price4"), fields.get("stcode"), fields.get("russian_mark"));
    }

    public boolean hasPriceData() {
        return !(price1.isEmpty() && price2.isEmpty() && price3.isEmpty() && price4.isEmpty());
    }

    public boolean hasData() {
        return !(barcode.isEmpty() && price1.isEmpty() && price2.isEmpty() && price3.isEmpty()
                && price4.isEmpty() && stcode.isEmpty() && russianMark.isEmpty());
    }

    public Map<Integer, BigDecimal> pricesAsDecimals() {
        Map<Integer, BigDecimal> prices = new LinkedHashMap<>();
        String[] values = {price1, price2, price3, price4};
        for (int i = 0; i < values.length; i++) {
            if (values[i].isEmpty()) continue;
            try {
                BigDecimal price = new BigDecimal(values[i].replace(',', '.'));
                if (price.signum() < 0) throw new NumberFormatException();
                prices.put(i + 1, price);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Некорректная цена price" + (i + 1) + ": " + values[i]);
            }
        }
        return prices;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String decodeSafely(String value) {
        // URLDecoder handles the scanner's application/x-www-form-urlencoded payload format.
        try {
            return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // GS1 serial payloads may contain literal percent signs, not URL escapes.
            return value;
        }
    }
}
