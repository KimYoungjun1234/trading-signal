package org.example.crypto.dto;

public record CandleStick(
    long time,      // Unix timestamp (seconds)
    double open,
    double high,
    double low,
    double close,
    long volume
) {}
