package com.setretailx.printer.service.setretailx.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PrintModeDto(int shelf) {
    @JsonProperty("@class")
    public String className() { return "ru.crystals.setretailx.grouping.vo.PrintModeVO"; }
}
