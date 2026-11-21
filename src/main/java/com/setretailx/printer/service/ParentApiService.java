package com.setretailx.printer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.model.Product;
import com.setretailx.printer.repository.AccountRepository;
import com.setretailx.printer.service.setretailx.dto.PrintGroupItemDto;

import java.sql.SQLException;
import java.util.List;

/** Versioned, permission-checked application API hosted exclusively by the parent node. */
public final class ParentApiService {
    public static final int API_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ParentAccessService access;
    private final PriceMonitoringTaskService tasks;

    public ParentApiService(ParentAccessService access, PriceMonitoringTaskService tasks) {
        this.access = java.util.Objects.requireNonNull(access);
        this.tasks = java.util.Objects.requireNonNull(tasks);
    }

    /** Dispatches a small JSON request. Authentication is handled here, not by peer trust. */
    public ObjectNode dispatch(JsonNode request) throws Exception {
        return dispatch(request, false);
    }

    /** Dispatches a request made directly by the parent application's local UI. */
    public ObjectNode dispatchLocal(JsonNode request) throws Exception {
        if (AppConfig.nodeRole() != AppConfig.NodeRole.PARENT)
            throw new SecurityException("Локальное разрешение доступно только на родительском ПК.");
        return dispatch(request, true);
    }

    private ObjectNode dispatch(JsonNode request, boolean localParentRequest) throws Exception {
        if (request == null || !request.isObject() || request.path("apiVersion").asInt(-1) != API_VERSION)
            throw new IllegalArgumentException("Неизвестная версия parent API.");
        String operation = request.path("operation").asText("");
        ObjectNode response = JSON.createObjectNode();
        response.put("apiVersion", API_VERSION);
        try {
        switch (operation) {
            case "auth.login" -> login(request, response);
            case "auth.logout" -> {
                access.logout(request.path("token").asText());
                response.put("ok", true);
            }
            case "sales.searchArticles" -> {
                require(request, AccountRepository.Permission.SALES_READ);
                Double from = optionalNumber(request.path("fromInclusive"));
                Double to = optionalNumber(request.path("toExclusive"));
                response.set("articles", JSON.valueToTree(tasks.searchSalesArticles(
                        request.path("query").asText(""), from, to,
                        bounded(request.path("limit").asInt(2000), 1, 5000))));
            }
            case "sales.history" -> {
                require(request, AccountRepository.Permission.SALES_READ);
                List<String> markings = strings(request.path("markings"));
                if (markings.isEmpty() || markings.size() > 5000)
                    throw new IllegalArgumentException("Необходимо указать от 1 до 5000 артикулов.");
                response.set("days", JSON.valueToTree(tasks.salesHistory(markings,
                        optionalNumber(request.path("fromInclusive")), optionalNumber(request.path("toExclusive")))));
            }
            case "sales.positions" -> {
                require(request, AccountRepository.Permission.SALES_READ);
                List<String> markings = strings(request.path("markings"));
                if (markings.isEmpty() || markings.size() > 5000)
                    throw new IllegalArgumentException("Необходимо указать от 1 до 5000 артикулов.");
                response.set("positions", JSON.valueToTree(tasks.salesPositions(markings,
                        optionalNumber(request.path("fromInclusive")), optionalNumber(request.path("toExclusive")),
                        bounded(request.path("limit").asInt(2000), 1, 20_000))));
            }
            case "sales.recentPositions" -> {
                require(request, AccountRepository.Permission.SALES_READ);
                response.set("positions", JSON.valueToTree(tasks.recentSalesPositions(
                        optionalNumber(request.path("fromInclusive")), optionalNumber(request.path("toExclusive")),
                        bounded(request.path("limit").asInt(2000), 1, 20_000))));
            }
            case "catalog.searchBarcode" -> {
                requireCatalogReadIfAuthenticated(request);
                String barcode = request.path("barcode").asText("").trim();
                if (barcode.isEmpty() || barcode.length() > 256)
                    throw new IllegalArgumentException("Штрихкод должен содержать от 1 до 256 символов.");
                java.util.Optional<Product> product = await(tasks.connector().searchOnlineProduct(barcode));
                if (product.isEmpty()) response.putNull("product");
                else response.set("product", JSON.valueToTree(product.get()));
            }
            case "catalog.getProducts" -> {
                requireCatalogReadIfAuthenticated(request);
                List<String> markings = strings(request.path("markings"));
                if (markings.isEmpty() || markings.size() > 100
                        || markings.stream().anyMatch(value -> value.length() > 256))
                    throw new IllegalArgumentException("Необходимо указать от 1 до 100 артикулов (до 256 символов каждый).");
                int shelf = bounded(request.path("shelf").asInt(12), 1, 100);
                response.set("products", JSON.valueToTree(await(tasks.connector().getProducts(markings, shelf))));
            }
            case "printing.printGroup" -> {
                if (!localParentRequest) require(request, AccountRepository.Permission.SHARED_DATA_WRITE);
                AppConfig.PrintFormat format = AppConfig.formatByKey(request.path("format").asText(""));
                if (format == null) throw new IllegalArgumentException("Неизвестный формат печати.");
                int amount = request.path("amount").asInt(1);
                if (amount < 1 || amount > 999)
                    throw new IllegalArgumentException("Количество ценников должно быть от 1 до 999.");
                JsonNode rawItems = request.path("items");
                if (!rawItems.isArray() || rawItems.isEmpty() || rawItems.size() > 500)
                    throw new IllegalArgumentException("Для печати требуется от 1 до 500 строк товаров.");
                List<PrintGroupItemDto> items = new java.util.ArrayList<>();
                long totalQuantity = 0;
                for (JsonNode item : rawItems) {
                    String stCode = boundedText(item.path("stCode").asText(""), 256);
                    String vendorCode = boundedText(item.path("vendorCode").asText(""), 256);
                    String uom = boundedText(item.path("uom").asText("ST"), 64);
                    int quantity = item.path("quantity").asInt(0);
                    if (quantity < 1 || quantity > 9999 || (stCode.isBlank() && vendorCode.isBlank()))
                        throw new IllegalArgumentException("Некорректный товар или количество для печати.");
                    totalQuantity += quantity;
                    if (totalQuantity > 10_000)
                        throw new IllegalArgumentException("За один запрос нельзя отправить более 10000 товаров.");
                    items.add(new PrintGroupItemDto(stCode, vendorCode, uom, quantity));
                }
                response.set("result", JSON.valueToTree(await(tasks.connector().printGroup(format, amount, items))));
            }
            default -> throw new IllegalArgumentException("Неизвестная операция parent API.");
        }
        } catch (RuntimeException error) {
            response.put("ok", false);
            response.put("error", error.getMessage() == null ? "Запрос отклонён." : error.getMessage());
        } catch (SQLException error) {
            response.put("ok", false);
            response.put("error", "Не удалось выполнить запрос к локальному хранилищу.");
        }
        return response;
    }

    private void login(JsonNode request, ObjectNode response) throws SQLException {
        String passwordText = request.path("password").asText("");
        char[] password = passwordText.toCharArray();
        try {
            ParentAccessService.LoginResult result = access.login(request.path("username").asText(), password);
            if (result == null) {
                response.put("ok", false);
                response.put("error", "Неверное имя пользователя или пароль.");
                return;
            }
            response.put("ok", true);
            response.put("token", result.token());
            response.put("expiresAt", result.expiresAt());
            response.put("username", result.account().username());
            response.put("admin", result.account().admin());
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private void require(JsonNode request, AccountRepository.Permission permission) throws SQLException {
        access.require(request.path("token").asText(), permission);
    }

    /** Catalog reads are public by default; a supplied session is still validated. */
    private void requireCatalogReadIfAuthenticated(JsonNode request) throws SQLException {
        String token = request.path("token").asText("");
        if (!token.isBlank()) access.require(token, AccountRepository.Permission.CATALOG_READ);
    }

    private static Double optionalNumber(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull() ? null : value.asDouble();
    }

    private static List<String> strings(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        return java.util.stream.StreamSupport.stream(value.spliterator(), false)
                .filter(JsonNode::isTextual).map(JsonNode::asText).map(String::trim)
                .filter(text -> !text.isEmpty()).distinct().toList();
    }

    private static int bounded(int value, int min, int max) { return Math.max(min, Math.min(value, max)); }

    private static String boundedText(String value, int maximum) {
        String result = value == null ? "" : value.trim();
        if (result.length() > maximum) throw new IllegalArgumentException("Слишком длинное поле запроса.");
        return result;
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) throws Exception {
        try { return future.get(45, java.util.concurrent.TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("SetRetailX отклонил запрос.", cause);
        } catch (java.util.concurrent.TimeoutException error) {
            throw new IllegalStateException("Истекло время ожидания ответа SetRetailX.", error);
        }
    }
}
