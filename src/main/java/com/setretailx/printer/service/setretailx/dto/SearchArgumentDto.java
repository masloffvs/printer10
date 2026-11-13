package com.setretailx.printer.service.setretailx.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SearchArgumentDto(String type) {
    @JsonProperty("@class")
    public String className() { return "ru.crystals.operday.vo.SearchArgumentVO"; }
}
