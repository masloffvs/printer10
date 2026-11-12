package com.setretailx.printer.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves common catalog image fields and downloads image bytes with bounded size. */
public final class ProductImageService {
    private static final long MAX_IMAGE_BYTES = 12L * 1024 * 1024;
    private static final Pattern URL = Pattern.compile("https?://[^\\s\\\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_VALUE = Pattern.compile(
            "(?i)[\\\"']?(?:url|src|path|href|original|large)[\\\"']?\\s*[:=]\\s*[\\\"']([^\\\"']+)[\\\"']");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ProductImageService() { }

    public static String findImageUrl(Map<String, String> details) {
        List<String> candidateValues = new ArrayList<>();
        details.forEach((key, value) -> {
            if (SetOfImageFields.contains(normalizeKey(key)) && value != null && !value.isBlank()) {
                candidateValues.add(value.trim());
            }
        });
        for (String value : candidateValues) {
            String candidate = imageValue(value);
            if (!candidate.isBlank()) return normalize(candidate);
        }
        return "";
    }

    private static String imageValue(String value) {
        String text = value.trim();
        if (text.startsWith("[") || text.startsWith("{")) {
            Matcher matcher = URL_VALUE.matcher(text);
            if (matcher.find()) return matcher.group(1);
            matcher = URL.matcher(text);
            if (matcher.find()) return matcher.group().replaceAll("[,;)}\\]]+$", "");
        }
        if (text.startsWith("//") || text.matches("(?i)^https?://.*")) return text;
        Matcher matcher = URL.matcher(text);
        if (matcher.find()) return matcher.group().replaceAll("[,;)}\\]]+$", "");
        matcher = URL_VALUE.matcher(text);
        if (matcher.find()) return matcher.group(1);
        if (text.startsWith("/") || text.startsWith("./")
                || text.matches("(?i).+\\.(?:png|jpe?g|gif|webp)(?:[?#].*)?$")) return text;
        return "";
    }

    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-zа-я0-9]", "");
    }

    public static byte[] download(String imageUrl) throws IOException, InterruptedException {
        URI uri;
        try {
            uri = URI.create(normalize(imageUrl));
        } catch (IllegalArgumentException error) {
            throw new IOException("Некорректная ссылка на фотографию товара.", error);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IOException("Ссылка на фотографию должна использовать HTTP или HTTPS.");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "image/*")
                .header("User-Agent", "SetRetailXPrinter/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream stream = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Сервер фотографии ответил HTTP " + response.statusCode() + ".");
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.isBlank() && !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                throw new IOException("Сервер вернул не файл изображения.");
            }
            ByteArrayOutputStream image = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_IMAGE_BYTES) throw new IOException("Фотография товара больше 12 МБ.");
                image.write(buffer, 0, read);
            }
            return image.toByteArray();
        }
    }

    private static String normalize(String value) {
        String url = value == null ? "" : value.trim();
        if (url.startsWith("//")) return "https:" + url;
        if (url.matches("(?i)^https?://.*")) return url;
        if (!url.startsWith("/") && !url.startsWith("./")
                && !url.matches("(?i).+\\.(?:png|jpe?g|gif|webp)(?:[?#].*)?$")) return "";
        String rpcUrl = System.getProperty("SETRETAILX_RPC_URL", System.getenv().getOrDefault("SETRETAILX_RPC_URL", ""));
        try {
            URI rpc = URI.create(rpcUrl);
            if (rpc.getScheme() != null && rpc.getHost() != null) {
                String path = url.startsWith("/") ? url : "/" + url;
                return new URI(rpc.getScheme(), null, rpc.getHost(), rpc.getPort(), path, null, null).toString();
            }
        } catch (Exception ignored) {
            // Relative image paths need a configured SetRetailX host.
        }
        return url;
    }

    private static final class SetOfImageFields {
        private static boolean contains(String key) {
            return switch (key) {
                case "mainimage", "mainimageurl", "image", "imageurl", "photourl", "photo", "pictures", "images" -> true;
                default -> false;
            };
        }
    }
}
