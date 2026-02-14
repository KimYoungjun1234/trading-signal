package org.example.crypto.dto;

import java.util.List;

public record SMIResult(
    List<SMIPoint> data,
    double overbought,
    double oversold
) {
    public record SMIPoint(
        long time,
        double smi,
        double signal
    ) {}
}
