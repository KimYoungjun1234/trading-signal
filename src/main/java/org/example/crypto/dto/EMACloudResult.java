package org.example.crypto.dto;

import java.util.List;

public record EMACloudResult(
    List<EMACloudPoint> data,
    int fastLength,
    int slowLength
) {
    public record EMACloudPoint(
        long time,
        double fastEMA,
        double slowEMA,
        boolean upTrend,
        boolean downTrend
    ) {}
}
