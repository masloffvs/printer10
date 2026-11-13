package com.setretailx.printer.service.setretailx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.setretailx.printer.config.AppConfig;
import com.setretailx.printer.service.SetRetailXConnector;
import com.setretailx.printer.service.setretailx.dto.ExecutePrintResponseDto;
import com.setretailx.printer.service.setretailx.dto.GroupingNodeDto;
import com.setretailx.printer.service.setretailx.dto.PrintGroupItemDto;
import com.setretailx.printer.service.setretailx.dto.PrintGroupResponseDto;
import com.setretailx.printer.service.setretailx.dto.PrintJobResponseDto;
import com.setretailx.printer.service.setretailx.dto.PrintRequestDto;
import com.setretailx.printer.service.setretailx.dto.PrintingParamsDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Creates a print job and confirms it with the SetRetailX print executor. */
public final class PrintGroupMethod {
    private static final String ENDPOINT = "java:app/SET-PrintPriceTags/GroupingPrintServiceBean!"
            + "ru.crystals.setretailx.grouping.api.GroupingPrintService";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Prints a group using the supplied configured format. Each item's quantity controls how many
     * basket-code occurrences are sent; amountToPrint is the number of labels per product.
     */
    public CompletableFuture<PrintGroupResponseDto> execute(
            SetRetailXConnector connector,
            AppConfig.PrintFormat format,
            int amountToPrint,
            List<PrintGroupItemDto> items) {
        if (connector == null) throw new IllegalArgumentException("SetRetailXConnector не задан.");
        if (amountToPrint < 1 || amountToPrint > 999) {
            throw new IllegalArgumentException("Количество ценников на товар должно быть от 1 до 999.");
        }
        if (format == null) throw new IllegalArgumentException("Формат печати не задан.");
        if (items == null) throw new IllegalArgumentException("Список товаров для печати не задан.");

        List<String> basket = new ArrayList<>();
        for (PrintGroupItemDto item : items) {
            if (item == null) throw new IllegalArgumentException("Товар для печати не задан.");
            for (int i = 0; i < item.quantity(); i++) basket.add(item.basketCode());
        }
        if (basket.isEmpty()) return CompletableFuture.completedFuture(PrintGroupResponseDto.notStarted());

        PrintRequestDto printRequest = new PrintRequestDto(
                99,
                List.of(),
                List.of(new GroupingNodeDto()),
                basket,
                false,
                false,
                Map.of(),
                List.of(new PrintingParamsDto(format.templateId(), amountToPrint, format.printerId())),
                System.currentTimeMillis(),
                null,
                false);

        ObjectNode payload = JSON.createObjectNode();
        payload.put("@class", "ru.crystals.commons.jsonrpc.domain.JsonRpcRequest");
        payload.put("id", 100);
        payload.put("version", "1.0.0");
        payload.put("endpoint", ENDPOINT);
        payload.put("method", "print");
        ArrayNode params = payload.putArray("params");
        params.add(JSON.valueToTree(printRequest));

        return connector.call(payload)
                .thenApply(PrintJobResponseDto::from)
                .thenCompose(job -> new ExecutePrintMethod().execute(connector, job.taskUuid())
                        .thenApply((ExecutePrintResponseDto response) ->
                                new PrintGroupResponseDto(response.started(), job.taskUuid())));
    }
}
