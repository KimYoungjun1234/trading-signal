package org.example.crypto.service;

import org.example.crypto.dto.CandleStick;
import org.example.crypto.dto.EMACloudResult;
import org.example.crypto.dto.EMACloudResult.EMACloudPoint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * EMAx2 Trend Cloud Fill Indicator Service
 * Based on Pine Script "EMAx2 Trend Cloud Fill Indicator" by medvyn
 *
 * Fast EMA (50) and Slow EMA (200) with cloud fill
 */
@Service
public class EMACloudIndicatorService {

    private static final int FAST_LENGTH = 50;
    private static final int SLOW_LENGTH = 200;

    private final CandleStickService candleStickService;

    public EMACloudIndicatorService(CandleStickService candleStickService) {
        this.candleStickService = candleStickService;
    }

    public EMACloudResult calculate() {
        List<CandleStick> candles = candleStickService.getCandles();
        return calculate(candles);
    }

    public EMACloudResult calculate(List<CandleStick> candles) {
        if (candles == null || candles.size() < SLOW_LENGTH) {
            return new EMACloudResult(List.of(), FAST_LENGTH, SLOW_LENGTH);
        }

        int size = candles.size();

        // Get close prices
        double[] close = new double[size];
        for (int i = 0; i < size; i++) {
            close[i] = candles.get(i).close();
        }

        // Calculate EMAs
        double[] fastEMA = calculateEMA(close, FAST_LENGTH);
        double[] slowEMA = calculateEMA(close, SLOW_LENGTH);

        // Build result
        List<EMACloudPoint> points = new ArrayList<>();
        for (int i = SLOW_LENGTH - 1; i < size; i++) {
            boolean upTrend = fastEMA[i] > slowEMA[i];
            boolean downTrend = fastEMA[i] < slowEMA[i];

            points.add(new EMACloudPoint(
                candles.get(i).time(),
                fastEMA[i],
                slowEMA[i],
                upTrend,
                downTrend
            ));
        }

        return new EMACloudResult(points, FAST_LENGTH, SLOW_LENGTH);
    }

    /**
     * Calculate EMA (Exponential Moving Average)
     */
    private double[] calculateEMA(double[] data, int period) {
        int size = data.length;
        double[] ema = new double[size];
        double multiplier = 2.0 / (period + 1);

        ema[0] = data[0];
        for (int i = 1; i < size; i++) {
            ema[i] = (data[i] - ema[i - 1]) * multiplier + ema[i - 1];
        }

        return ema;
    }
}
