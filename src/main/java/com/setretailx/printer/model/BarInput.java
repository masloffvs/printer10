package com.setretailx.printer.model;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts a product barcode from plain input, scanner payloads, or GS1/Честный знак data. */
public final class BarInput {
    private static final Pattern BARCODE_FIELD = Pattern.compile(
            "(?:barcode|баркод)\\s*=\\s*([^&?\\r\\n\\u001d]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GTIN_AI = Pattern.compile("(?:^|\\u001d)01(\\d{14})");
    private static final Pattern PARENTHESIZED_GTIN_AI = Pattern.compile("\\(01\\)(\\d{14})");
    private static final Pattern PLAIN_DIGITS = Pattern.compile("\\d{8,14}");

    private BarInput() { }

    /** Returns the best barcode candidate, or empty for ordinary text with no barcode. */
    public static Optional<String> barcode(String input) {
        if (input == null || input.isBlank()) return Optional.empty();
        String text = input.trim();

        // GS1 scanners may prepend an AIM symbology identifier (for example, ]C1).
        String gs1 = text.replaceFirst("^\\]\\w{2}", "");
        Matcher gtin = GTIN_AI.matcher(gs1);
        if (gtin.find()) return Optional.of(gtin.group(1));
        gtin = PARENTHESIZED_GTIN_AI.matcher(gs1);
        if (gtin.find()) return Optional.of(gtin.group(1));

        // AI 00 is an SSCC for a logistic unit, not a sellable product barcode.
        if (gs1.matches("^00\\d{18}.*")) return Optional.empty();

        String normalizedKeys = keyboardLayoutToLatin(gs1);
        Matcher field = BARCODE_FIELD.matcher(normalizedKeys);
        if (field.find()) {
            String value = field.group(1).trim();
            Matcher digits = Pattern.compile("\\d{8,14}").matcher(value);
            if (digits.find()) return Optional.of(digits.group());
        }

        String plain = gs1.trim();
        if (PLAIN_DIGITS.matcher(plain).matches()) return Optional.of(plain);

        // Never mistake an SSCC/logistic identifier after AI 00 for a product GTIN.
        if (text.startsWith("]C1") || text.indexOf('\u001d') >= 0) return Optional.empty();

        // Some scanners wrap a GTIN in other text without preserving GS1 separators.
        Matcher digits = Pattern.compile("(?<!\\d)(\\d{8,14})(?!\\d)").matcher(gs1);
        if (digits.find()) return Optional.of(digits.group(1));
        return Optional.empty();
    }

    /** Converts keys typed with the Russian layout active back to their Latin key labels. */
    static String keyboardLayoutToLatin(String value) {
        String russian = "ёйцукенгшщзхъфывапролджэячсмитьбюЁЙЦУКЕНГШЩЗХЪФЫВАПРОЛДЖЭЯЧСМИТЬБЮ";
        String latin =   "`qwertyuiop[]asdfghjkl;'zxcvbnm,.`QWERTYUIOP{}ASDFGHJKL:\"ZXCVBNM<>";
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            int index = russian.indexOf(ch);
            result.append(index >= 0 && index < latin.length() ? latin.charAt(index) : ch);
        }
        return result.toString().toLowerCase(Locale.ROOT);
    }
}
