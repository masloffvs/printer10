package com.setretailx.printer.service;

import java.time.Instant;

/** A safe-to-display diagnostic event from the SetRetailX connector. */
public record ExchangeEvent(Instant timestamp, String title, String details, String tone) { }
