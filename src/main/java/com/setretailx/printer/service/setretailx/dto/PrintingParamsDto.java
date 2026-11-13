package com.setretailx.printer.service.setretailx.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PrintingParamsDto(String templateId, int amountToPrint, int printerId) {
    @JsonProperty("@class")
    public String className() { return "ru.crystals.setretailx.printers.PrintingParamsVO"; }
}
