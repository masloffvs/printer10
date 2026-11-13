package com.setretailx.printer.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class AppConfig {
    public static final Path APP_DIR = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    public static final Path DOT_ENV_PATH = APP_DIR.resolve(".env");
    public static final Path DB_PATH = APP_DIR.resolve("all_products.sqlite");
    public static final Path PRINT_HISTORY_PATH = printHistoryPath();
    public static final List<PrintFormat> PRINT_FORMATS = List.of(
            new PrintFormat("A", "Формат A", 9974664, "22766"),
            new PrintFormat("B", "Формат B", 9974666, "23841"),
            new PrintFormat("C", "Формат C", 9975059, "23574"));

    private AppConfig() { }

    private static final Map<String, Optional<String>> previousDotEnvProperties = new HashMap<>();

    /** Normalize canonical Latin keys and legacy Cyrillic aliases to A/B/C. */
    public static String normalizeFormatKey(String key) {
        if (key == null || key.isBlank()) return "";
        return switch (key.trim().toUpperCase(Locale.ROOT)) {
            case "A", "А" -> "A";
            case "B", "Б" -> "B";
            case "C", "С", "Ц" -> "C";
            default -> "";
        };
    }

    public static PrintFormat formatByKey(String key) {
        String normalized = normalizeFormatKey(key);
        return PRINT_FORMATS.stream().filter(format -> format.key().equals(normalized))
                .findFirst().orElse(null);
    }

    private static Path printHistoryPath() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(System.getProperty("user.home"), "Library", "Application Support",
                    "SetRetailX", "print_history.sqlite");
        }
        if (os.contains("win")) {
            return Path.of(System.getenv().getOrDefault("LOCALAPPDATA",
                    Path.of(System.getProperty("user.home"), "AppData", "Local").toString()),
                    "SetRetailX", "print_history.sqlite");
        }
        return Path.of(System.getenv().getOrDefault("XDG_DATA_HOME",
                        Path.of(System.getProperty("user.home"), ".local", "share").toString()),
                "SetRetailX", "print_history.sqlite");
    }

    public static synchronized String readDotEnv() throws IOException {
        if (Files.isRegularFile(DOT_ENV_PATH)) {
            return Files.readString(DOT_ENV_PATH, StandardCharsets.UTF_8);
        }
        return "# SetRetailX connection settings\n"
                + "SETRETAILX_RPC_URL=http://localhost:8090/SetXRMI/json-rpc/\n"
                + "SETRETAILX_USERNAME=\n"
                + "SETRETAILX_PASSWORD=\n"
                + "SETRETAILX_CONNECTION_RETRIES=5\n";
    }

    public static synchronized void saveDotEnv(String contents) throws IOException {
        Files.writeString(DOT_ENV_PATH, contents == null ? "" : contents, StandardCharsets.UTF_8);
        loadDotEnv();
    }

    public static synchronized void loadDotEnv() {
        for (Map.Entry<String, Optional<String>> entry : previousDotEnvProperties.entrySet()) {
            if (entry.getValue().isPresent()) System.setProperty(entry.getKey(), entry.getValue().get());
            else System.clearProperty(entry.getKey());
        }
        previousDotEnvProperties.clear();
        if (!Files.isRegularFile(DOT_ENV_PATH)) return;
        try {
            for (String line : Files.readAllLines(DOT_ENV_PATH, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int split = trimmed.indexOf('=');
                if (split <= 0) continue;
                String key = trimmed.substring(0, split).trim();
                String value = trimmed.substring(split + 1).trim();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                if (System.getenv(key) == null) {
                    previousDotEnvProperties.putIfAbsent(key, Optional.ofNullable(System.getProperty(key)));
                    System.setProperty(key, value);
                }
            }
        } catch (IOException error) {
            System.err.println("Не удалось прочитать .env: " + error.getMessage());
        }
    }

    public record PrintFormat(String key, String name, int printerId, String templateId) {
        @Override public String toString() { return name; }
    }
}
