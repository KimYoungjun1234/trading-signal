package org.example.crypto.dto;

import java.util.List;

public record MAAnglesResult(
    List<MAAnglesPoint> data,
    double threshold
) {
    public record MAAnglesPoint(
        long time,
        double jmaSlope,
        double jmaFastSlope,
        double ma27Slope,
        double ma83Slope,
        double ma278Slope,
        boolean ma27Rising,
        boolean ma27Falling
    ) {}
}
