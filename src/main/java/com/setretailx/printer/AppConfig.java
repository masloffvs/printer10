package com.setretailx.printer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class AppConfig {
    static final Path APP_DIR = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    static final Path DB_PATH = APP_DIR.resolve("all_products.sqlite");
    static final List<PrintFormat> PRINT_FORMATS = List.of(
            new PrintFormat("А", "Формат А", 9974664, "22766"),
            new PrintFormat("Б", "Формат Б", 9974666, "23841"),
            new PrintFormat("Ц", "Формат Ц", 9975059, "23574"));

    private AppConfig() { }

    static void loadDotEnv() {
        Path env = APP_DIR.resolve(".env");
        if (!Files.isRegularFile(env)) return;
        try {
            for (String line : Files.readAllLines(env)) {
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
                if (System.getenv(key) == null) System.setProperty(key, value);
            }
        } catch (IOException error) {
            System.err.println("Не удалось прочитать .env: " + error.getMessage());
        }
    }

    record PrintFormat(String key, String name, int printerId, String templateId) {
        @Override public String toString() { return name; }
    }
}
