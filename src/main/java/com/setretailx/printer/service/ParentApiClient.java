package com.setretailx.printer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.model.Product;
import com.setretailx.printer.service.setretailx.dto.PrintGroupItemDto;
import com.setretailx.printer.service.setretailx.dto.PrintGroupResponseDto;
import com.setretailx.printer.service.setretailx.dto.ProductDto;

import java.util.List;
import java.util.concurrent.CompletionException;

/** Client facade routing the same permission-checked API locally or through the trusted parent link. */
public final class ParentApiClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ParentApiService local;
    private final PeerMeshService peers;
    private volatile String token = "";

    public ParentApiClient(ParentApiService local, PeerMeshService peers) {
        this.local = local;
        this.peers = java.util.Objects.requireNonNull(peers);
    }

    public synchronized void login(String username, char[] password) throws Exception {
        ObjectNode request = request("auth.login");
        request.put("username", username == null ? "" : username);
        try {
            request.put("password", password == null ? "" : new String(password));
            JsonNode response = call(request);
            token = response.path("token").asText("");
            if (token.isBlank()) throw apiError(response);
        } finally {
            if (password != null) java.util.Arrays.fill(password, '\0');
            request.put("password", "");
        }
    }

    public synchronized void logout() throws Exception {
        if (token.isBlank()) return;
        ObjectNode request = request("auth.logout");
        request.put("token", token);
        call(request);
        token = "";
    }

    public boolean isLoggedIn() { return !token.isBlank(); }

    public List<PriceMonitoringTaskService.SalesArticle> searchArticles(String query, Double from, Double to, int limit)
            throws Exception {
        ObjectNode request = authenticated("sales.searchArticles");
        request.put("query", query == null ? "" : query);
        number(request, "fromInclusive", from);
        number(request, "toExclusive", to);
        request.put("limit", limit);
        return values(call(request).path("articles"), PriceMonitoringTaskService.SalesArticle.class);
    }

    public List<PriceMonitoringTaskService.SalesDay> salesHistory(List<String> markings, Double from, Double to)
            throws Exception {
        ObjectNode request = authenticated("sales.history");
        strings(request.putArray("markings"), markings);
        number(request, "fromInclusive", from);
        number(request, "toExclusive", to);
        return values(call(request).path("days"), PriceMonitoringTaskService.SalesDay.class);
    }

    public List<PriceMonitoringTaskService.SalesPosition> salesPositions(List<String> markings, Double from,
                                                                         Double to, int limit) throws Exception {
        ObjectNode request = authenticated("sales.positions");
        strings(request.putArray("markings"), markings);
        number(request, "fromInclusive", from);
        number(request, "toExclusive", to);
        request.put("limit", limit);
        return values(call(request).path("positions"), PriceMonitoringTaskService.SalesPosition.class);
    }

    public List<PriceMonitoringTaskService.SalesPosition> recentSalesPositions(Double from, Double to,
                                                                                int limit) throws Exception {
        ObjectNode request = authenticated("sales.recentPositions");
        number(request, "fromInclusive", from);
        number(request, "toExclusive", to);
        request.put("limit", limit);
        return values(call(request).path("positions"), PriceMonitoringTaskService.SalesPosition.class);
    }

    public java.util.Optional<Product> searchOnlineProduct(String barcode) throws Exception {
        ObjectNode request = request("catalog.searchBarcode");
        addTokenIfLoggedIn(request);
        request.put("barcode", barcode == null ? "" : barcode);
        JsonNode product = call(request).path("product");
        return product.isObject() ? java.util.Optional.of(JSON.treeToValue(product, Product.class))
                : java.util.Optional.empty();
    }

    public List<ProductDto> getProducts(List<String> markings) throws Exception {
        return getProducts(markings, 12);
    }

    public List<ProductDto> getProducts(List<String> markings, int shelf) throws Exception {
        ObjectNode request = request("catalog.getProducts");
        addTokenIfLoggedIn(request);
        strings(request.putArray("markings"), markings);
        request.put("shelf", shelf);
        return values(call(request).path("products"), ProductDto.class);
    }

    public PrintGroupResponseDto printGroup(AppConfig.PrintFormat format, int amountToPrint,
                                            List<PrintGroupItemDto> items) throws Exception {
        ObjectNode request = request("printing.printGroup");
        if (peers.nodeRole() != AppConfig.NodeRole.PARENT) {
            if (token.isBlank()) throw new ParentAccessService.AccessDeniedException("Сначала войдите в систему.");
            request.put("token", token);
        }
        request.put("format", format == null ? "" : format.key());
        request.put("amount", amountToPrint);
        request.set("items", JSON.valueToTree(items == null ? List.of() : items));
        JsonNode response;
        if (peers.nodeRole() == AppConfig.NodeRole.PARENT) {
            if (local == null) throw new IllegalStateException("Локальный parent API не настроен.");
            response = local.dispatchLocal(request);
            if (!response.path("ok").asBoolean(true)) throw apiError(response);
        } else {
            response = call(request);
        }
        JsonNode result = response.path("result");
        return JSON.treeToValue(result, PrintGroupResponseDto.class);
    }

    private ObjectNode authenticated(String operation) {
        if (token.isBlank()) throw new ParentAccessService.AccessDeniedException("Сначала войдите в систему.");
        ObjectNode request = request(operation);
        request.put("token", token);
        return request;
    }

    private void addTokenIfLoggedIn(ObjectNode request) {
        if (!token.isBlank()) request.put("token", token);
    }

    private static ObjectNode request(String operation) {
        ObjectNode request = JSON.createObjectNode();
        request.put("apiVersion", ParentApiService.API_VERSION);
        request.put("operation", operation);
        return request;
    }

    private JsonNode call(ObjectNode request) throws Exception {
        JsonNode response;
        if (peers.nodeRole() == com.setretailx.printer.config.AppConfig.NodeRole.PARENT) {
            if (local == null) throw new IllegalStateException("Локальный parent API не настроен.");
            response = local.dispatch(request);
        } else {
            PeerMeshService.Peer parent = peers.peers().get(peers.parentNodeId());
            if (parent == null || !parent.online())
                throw new IllegalStateException("Родительский ПК недоступен. Сначала подключитесь к нему в разделе «Пиры».");
            try { response = peers.apiCall(parent, request).join(); }
            catch (CompletionException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                if (cause instanceof Exception exception) throw exception;
                throw error;
            }
        }
        if (!response.path("ok").asBoolean(true)) throw apiError(response);
        return response;
    }

    private static Exception apiError(JsonNode response) {
        String message = response.path("error").asText("Запрос parent API не выполнен.");
        return new IllegalStateException(message);
    }

    private static void number(ObjectNode request, String field, Double value) {
        if (value != null) request.put(field, value);
        else request.putNull(field);
    }

    private static void strings(ArrayNode target, List<String> values) {
        for (String value : values) target.add(value);
    }

    private static <T> List<T> values(JsonNode array, Class<T> type) {
        if (!array.isArray()) return List.of();
        return JSON.convertValue(array, JSON.getTypeFactory().constructCollectionType(List.class, type));
    }
}
