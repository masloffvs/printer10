package com.setretailx.printer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.EOFException;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Internal asynchronous HTTP/JSON-RPC transport used only by SetRetailXConnector. */
final class SetRetailXClient implements AutoCloseable {
    private static final String REQUEST_CLASS = "ru.crystals.commons.jsonrpc.domain.JsonRpcRequest";
    private static final String LOGIN_ENDPOINT = "java:app/SET-Users/SessionManagerBean!"
            + "ru.crystals.setretailx.users.ejb.interfaces.ISessionManagerLocal";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI rpcUri;
    private final String baseUrl;
    private final String configurationError;
    private final String username;
    private final String password;
    private final int connectionRetries;
    private final ExecutorService executor = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "setretailx-network");
        thread.setDaemon(true);
        return thread;
    });
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .cookieHandler(cookies)
            .executor(executor)
            .build();
    private final AtomicLong requestIds = new AtomicLong(1);
    private final Consumer<ExchangeEvent> eventHandler;
    private volatile String token;
    private volatile CompletableFuture<Void> loginInFlight;
    private volatile boolean closed;

    SetRetailXClient(Consumer<ExchangeEvent> eventHandler) {
        this(setting("SETRETAILX_RPC_URL").trim(), setting("SETRETAILX_USERNAME"),
                setting("SETRETAILX_PASSWORD"), configuredRetries(), eventHandler);
    }

    private static int configuredRetries() {
        String configured = setting("SETRETAILX_CONNECTION_RETRIES").trim();
        if (configured.isEmpty()) return 5;
        try {
            int retries = Integer.parseInt(configured);
            if (retries < 0) throw new NumberFormatException("negative retry count");
            return retries;
        } catch (NumberFormatException error) {
            return 5;
        }
    }

    private static String setting(String name) {
        String property = System.getProperty(name);
        return property != null ? property : System.getenv().getOrDefault(name, "");
    }

    SetRetailXClient(String rpcUrl, String username, String password, Consumer<ExchangeEvent> eventHandler) {
        this(rpcUrl, username, password, 5, eventHandler);
    }

    SetRetailXClient(String rpcUrl, String username, String password, int connectionRetries,
                     Consumer<ExchangeEvent> eventHandler) {
        URI parsedUri = null;
        String parsedBaseUrl = "";
        String configError = "";
        try {
            if (rpcUrl == null || rpcUrl.isBlank()) {
                configError = "Не задан SETRETAILX_RPC_URL.";
            } else {
                parsedUri = URI.create(rpcUrl);
                if (!("http".equalsIgnoreCase(parsedUri.getScheme()) || "https".equalsIgnoreCase(parsedUri.getScheme()))
                        || parsedUri.getHost() == null) {
                    configError = "SETRETAILX_RPC_URL должен быть HTTP- или HTTPS-адресом.";
                } else if (parsedUri.getUserInfo() != null) {
                    configError = "Укажите URL SetRetailX без логина и пароля в адресе.";
                } else {
                    parsedBaseUrl = origin(parsedUri);
                }
            }
        } catch (IllegalArgumentException error) {
            configError = "Некорректный URL SetRetailX.";
        }
        this.rpcUri = parsedUri;
        this.baseUrl = parsedBaseUrl;
        this.configurationError = configError;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.connectionRetries = Math.max(0, connectionRetries);
        this.eventHandler = eventHandler == null ? ignored -> { } : eventHandler;
    }

    /** Authenticates asynchronously; simultaneous callers share a single login operation. */
    public synchronized CompletableFuture<Void> connect() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Клиент SetRetailX уже закрыт."));
        if (token != null && !token.isBlank()) return CompletableFuture.completedFuture(null);
        if (loginInFlight != null) return loginInFlight;
        if (!configurationError.isBlank()) {
            emit("Ошибка настройки SetRetailX", configurationError, "error");
            return CompletableFuture.failedFuture(new IllegalStateException(configurationError));
        }
        if (username.isBlank() || password.isBlank()) {
            String message = "Задайте SETRETAILX_USERNAME и SETRETAILX_PASSWORD в окружении или .env.";
            emit("Ошибка настройки SetRetailX", message, "error");
            return CompletableFuture.failedFuture(new IllegalStateException(message));
        }

        ObjectNode payload = request("login", LOGIN_ENDPOINT);
        ArrayNode params = payload.putArray("params");
        params.add(username).add(password).add("MAIN");
        emit("Отправлен запрос: login", "URL: " + safeUrl() + "\nМетод: login");
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        loginInFlight = attempt;
        String credentials = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = requestBuilder(payload, Duration.ofSeconds(10))
                .header("Authorization", "Basic " + credentials)
                .header("Origin", baseUrl)
                .header("Referer", baseUrl + "/login?redirect=/products/search")
                .build();
        send(request, "login").thenAccept(response -> {
            JsonNode result = response.path("result");
            if (!response.path("error").isMissingNode() && !response.path("error").isNull()) {
                throw new CompletionException(new IOException("SetRetailX login: "
                        + safeBody(response.path("error").toString(), "login")));
            }
            String responseClass = response.path("@class").asText();
            if (!responseClass.contains("JsonRpcSuccessResponse")) {
                throw new CompletionException(new IOException("Сервер отклонил авторизацию."));
            }
            if (!result.isTextual() || result.asText().isBlank()) {
                throw new CompletionException(new IOException("Сервер не вернул токен авторизации."));
            }
            token = result.asText();
            java.net.HttpCookie sessionCookie = new java.net.HttpCookie("JSESSIONID", token + "." + rpcUri.getHost());
            sessionCookie.setVersion(0);
            sessionCookie.setDomain(rpcUri.getHost());
            sessionCookie.setPath("/");
            cookies.getCookieStore().add(rpcUri, sessionCookie);
            emit("Авторизация SetRetailX успешна", "Сессия создана.", "success");
        }).whenComplete((ignored, error) -> {
            if (error == null) {
                attempt.complete(null);
            } else {
                Throwable cause = unwrap(error);
                emit("Ошибка авторизации SetRetailX", redactSecrets(safeMessage(cause)), "error");
                attempt.completeExceptionally(cause);
            }
            synchronized (SetRetailXClient.this) {
                if (loginInFlight == attempt) loginInFlight = null;
            }
        });
        return attempt;
    }

    /** Sends arbitrary JSON-RPC payload after ensuring authentication, entirely asynchronously. */
    public CompletableFuture<JsonNode> call(ObjectNode payload) {
        if (payload == null) return CompletableFuture.failedFuture(new IllegalArgumentException("JSON-RPC запрос не задан."));
        ObjectNode requestPayload = payload.deepCopy();
        if ("login".equals(requestPayload.path("method").asText())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Используйте connect() для авторизации."));
        }
        return connect().thenCompose(ignored -> {
            String method = requestPayload.path("method").asText("JSON-RPC");
            emit("Отправлен запрос: " + method, "URL: " + safeUrl() + "\n" + safeRequestBody(requestPayload));
            HttpRequest request = requestBuilder(requestPayload, Duration.ofSeconds(15))
                    .header("Referer", refererFor(method))
                    .build();
            return send(request, method);
        });
    }

    /** Builds a JSON-RPC request and sends it asynchronously. */
    public CompletableFuture<JsonNode> call(String endpoint, String method, java.util.List<?> params) {
        ObjectNode payload = request(method, endpoint);
        ArrayNode requestParams = payload.putArray("params");
        if (params != null) params.forEach(value -> requestParams.add(JSON.valueToTree(value)));
        return call(payload);
    }

    private CompletableFuture<JsonNode> send(HttpRequest request, String method) {
        return send(request, method, 0);
    }

    private CompletableFuture<JsonNode> send(HttpRequest request, String method, int retryNumber) {
        return sendOnce(request, method).handle((response, error) -> {
            if (error == null) return CompletableFuture.completedFuture(response);
            Throwable cause = unwrap(error);
            if (retryNumber >= connectionRetries || !isClosedConnection(cause) || closed) {
                return CompletableFuture.<JsonNode>failedFuture(cause);
            }
            int nextRetry = retryNumber + 1;
            emit("Повтор запроса SetRetailX: " + method,
                    "Соединение было закрыто. Повтор " + nextRetry + " из " + connectionRetries + ".", "warning");
            return CompletableFuture.runAsync(() -> { },
                    CompletableFuture.delayedExecutor(200L * nextRetry, TimeUnit.MILLISECONDS, executor))
                    .thenCompose(ignored -> send(request, method, nextRetry));
        }).thenCompose(future -> future);
    }

    private CompletableFuture<JsonNode> sendOnce(HttpRequest request, String method) {
        Instant started = Instant.now();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    String body = response.body();
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        emit("Ответ SetRetailX: HTTP " + response.statusCode(),
                                "Метод: " + method + "\nВремя ответа: " + elapsed(started) + " с\n" + safeBody(body, method), "error");
                        if (looksLikeClosedConnection(body)) {
                            throw new CompletionException(new ClosedConnectionException(
                                    "SetRetailX закрыл соединение во время запроса."));
                        }
                        throw new CompletionException(new IOException("SetRetailX вернул HTTP " + response.statusCode() + "."));
                    }
                    try {
                        JsonNode parsed = JSON.readTree(body);
                        if (parsed == null || !parsed.isObject()) throw new IOException("Ожидался JSON-объект.");
                        if (!parsed.path("error").isMissingNode() && !parsed.path("error").isNull()) {
                            emit("Ошибка SetRetailX: " + method, safeBody(parsed.path("error").toString(), method), "error");
                            if (looksLikeClosedConnection(parsed.path("error").toString())) {
                                throw new ClosedConnectionException("SetRetailX закрыл соединение во время запроса.");
                            }
                            throw new IOException("SetRetailX " + method + ": " + safeBody(parsed.path("error").toString(), method));
                        }
                        emit("Ответ SetRetailX: HTTP " + response.statusCode(),
                                "Метод: " + method + "\nВремя ответа: " + elapsed(started) + " с\n" + safeBody(body, method), "success");
                        return parsed;
                    } catch (IOException error) {
                        throw new CompletionException(error);
                    }
                }).whenComplete((result, error) -> {
                    if (error != null && unwrap(error) instanceof java.net.http.HttpTimeoutException) {
                        emit("Таймаут запроса: " + method, "Запрос превысил допустимое время ожидания.", "error");
                    } else if (error != null && !(unwrap(error) instanceof IOException
                            && safeMessage(unwrap(error)).startsWith("SetRetailX "))) {
                        emit("Ошибка запроса: " + method, redactSecrets(safeMessage(unwrap(error))), "error");
                    }
                });
    }

    private static boolean isClosedConnection(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ClosedConnectionException) return true;
            if (cause instanceof ClosedChannelException || cause instanceof EOFException) return true;
            if (cause instanceof SocketException) {
                String message = safeMessage(cause).toLowerCase(java.util.Locale.ROOT);
                if (message.contains("reset") || message.contains("closed") || message.contains("broken pipe")
                        || message.contains("aborted") || message.contains("end of stream")) return true;
            }
            if (cause instanceof IOException) {
                String message = safeMessage(cause).toLowerCase(java.util.Locale.ROOT);
                if (looksLikeClosedConnection(message)) return true;
            }
        }
        return false;
    }

    private static boolean looksLikeClosedConnection(String text) {
        if (text == null) return false;
        String message = text.toLowerCase(java.util.Locale.ROOT);
        return message.contains("connection reset") || message.contains("connection closed")
                || message.contains("connection is closed") || message.contains("closed connection")
                || message.contains("connection was closed") || message.contains("closed channel")
                || message.contains("channel is closed") || message.contains("broken pipe")
                || message.contains("unexpected end of file") || message.contains("header parser received no bytes")
                || message.contains("end of stream") || message.contains("socket is closed");
    }

    private static final class ClosedConnectionException extends IOException {
        private ClosedConnectionException(String message) { super(message); }
    }

    private HttpRequest.Builder requestBuilder(ObjectNode payload, Duration timeout) {
        try {
            String body = JSON.writeValueAsString(payload);
            return HttpRequest.newBuilder(rpcUri).timeout(timeout)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "ru,en;q=0.9")
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header("User-Agent", "SetRetailXPrinter/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalArgumentException("Не удалось сформировать JSON-RPC запрос.", error);
        }
    }

    private ObjectNode request(String method, String endpoint) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("@class", REQUEST_CLASS);
        payload.put("id", requestIds.getAndIncrement());
        payload.put("version", "1.0.0");
        payload.put("endpoint", endpoint);
        payload.put("method", method);
        payload.putArray("params");
        return payload;
    }

    private String safeBody(String body, String method) {
        if ("login".equals(method)) return "[ответ авторизации скрыт]";
        try {
            JsonNode parsed = redactSensitiveFields(JSON.readTree(body));
            if (parsed != null && parsed.isObject() && parsed.has("result") && token != null
                    && "login".equals(parsed.path("method").asText())) {
                ((ObjectNode) parsed).put("result", "[скрыто: токен авторизации]");
            }
            return redactSecrets(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(parsed));
        } catch (Exception ignored) {
            return redactSecrets(body == null ? "" : body);
        }
    }

    private String safeRequestBody(ObjectNode payload) {
        ObjectNode safe = (ObjectNode) redactSensitiveFields(payload.deepCopy());
        if ("getSimpleProductsByFilter".equals(safe.path("method").asText())
                && safe.path("params").isArray() && !safe.path("params").isEmpty()) {
            ((ArrayNode) safe.path("params")).set(0, JSON.getNodeFactory().textNode("[скрыто: токен авторизации]"));
        }
        try {
            return redactSecrets(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(safe));
        } catch (IOException ignored) {
            return "Метод: " + safe.path("method").asText("JSON-RPC");
        }
    }

    private JsonNode redactSensitiveFields(JsonNode node) {
        if (node == null) return null;
        if (node.isObject()) {
            ObjectNode object = ((ObjectNode) node).deepCopy();
            object.fieldNames().forEachRemaining(name -> {
                String normalized = name.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("token") || normalized.contains("password")
                        || normalized.contains("authorization") || normalized.equals("cookie")) {
                    object.put(name, "[скрыто]");
                } else {
                    object.set(name, redactSensitiveFields(object.get(name)));
                }
            });
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            node.forEach(value -> array.add(redactSensitiveFields(value)));
            return array;
        }
        return node.deepCopy();
    }

    private String redactSecrets(String text) {
        String safe = text;
        if (rpcUri != null && configurationError.isBlank()) {
            safe = safe.replace(rpcUri.toString(), safeUrl());
            String rawQuery = rpcUri.getRawQuery();
            String rawFragment = rpcUri.getRawFragment();
            if (rawQuery != null) safe = safe.replace(rawQuery, "[скрыто]");
            if (rawFragment != null) safe = safe.replace(rawFragment, "[скрыто]");
        }
        if (!password.isEmpty()) safe = safe.replace(password, "[скрыто]");
        if (token != null && !token.isEmpty()) safe = safe.replace(token, "[скрыто]");
        return safe;
    }

    private String safeUrl() {
        return origin(rpcUri) + (rpcUri.getRawPath() == null ? "" : rpcUri.getRawPath());
    }

    private String refererFor(String method) {
        String page = "getObjectsIds".equals(method) || "getProducts".equals(method)
                ? "/price-tags/not-printed" : "/price-tags/custom-printing";
        return baseUrl + page;
    }

    private static String origin(URI uri) {
        int port = uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + (port < 0 ? "" : ":" + port);
    }

    private void emit(String title, String details) { emit(title, details, "info"); }

    private void emit(String title, String details, String tone) {
        try {
            eventHandler.accept(new ExchangeEvent(Instant.now(), title, details, tone));
        } catch (RuntimeException ignored) {
            // Diagnostics must never change network operation results.
        }
    }

    private static String elapsed(Instant started) {
        return String.format(java.util.Locale.ROOT, "%.2f", Duration.between(started, Instant.now()).toMillis() / 1000.0);
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override public void close() {
        closed = true;
        executor.shutdownNow();
    }

}
