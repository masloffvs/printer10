package com.setretailx.printer.service.setretailx.dto;

/** Outcome of creating and starting a SetRetailX print job. */
public record PrintGroupResponseDto(boolean started, String taskUuid) {
    public static PrintGroupResponseDto notStarted() {
        return new PrintGroupResponseDto(false, null);
    }
}
