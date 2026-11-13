package com.setretailx.printer.service.setretailx.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record GroupingObjectsFilterDto(List<SearchArgumentDto> filters, List<GroupingNodeDto> tree) {
    @JsonProperty("@class")
    public String className() { return "ru.crystals.setretailx.grouping.vo.GroupingObjectsFilterVO"; }
}
