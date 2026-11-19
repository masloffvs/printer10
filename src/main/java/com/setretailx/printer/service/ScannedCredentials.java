package com.setretailx.printer.service;

/** Parses the exact scanner format without retaining or formatting the submitted secret. */
public final class ScannedCredentials {
    private ScannedCredentials() { }

    public static Credentials parse(String input) {
        if (input == null || input.length() > 4096 || !input.startsWith("username=")) {
            throw invalid();
        }
        int separator = input.indexOf("&password=", "username=".length());
        if (separator < 0 || separator != input.lastIndexOf("&password=")) throw invalid();
        String username = input.substring("username=".length(), separator);
        String password = input.substring(separator + "&password=".length());
        if (username.isBlank() || password.isEmpty() || username.indexOf('&') >= 0
                || password.indexOf('&') >= 0 || username.indexOf('=') >= 0 || password.indexOf('=') >= 0
                || username.indexOf('\n') >= 0 || username.indexOf('\r') >= 0 || password.indexOf('\n') >= 0
                || password.indexOf('\r') >= 0) {
            throw invalid();
        }
        return new Credentials(username, password.toCharArray());
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Неверный формат сканируемых данных. Ожидается username=XXX&password=YYY.");
    }

    public record Credentials(String username, char[] password) implements AutoCloseable {
        public Credentials {
            if (password == null) password = new char[0];
        }
        @Override public char[] password() { return password.clone(); }
        @Override public void close() { java.util.Arrays.fill(password, '\0'); }
    }
}
