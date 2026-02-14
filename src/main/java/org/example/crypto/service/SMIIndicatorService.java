package org.example.crypto.service;

import org.example.crypto.dto.CandleStick;
import org.example.crypto.dto.SMIResult;
import org.example.crypto.dto.SMIResult.SMIPoint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Stochastic Momentum Index (SMI) Indicator Service
 * Based on Pine Script implementation by Surjith S M
 *
 * Parameters:
 * - a (Percent K Length): 10
 * - b (Percent D Length): 3
 * - c (EMA Signal Length): 10
 * - smoothPeriod: 5
 * - overbought: 40
 * - oversold: -40
 */
@Service
public class SMIIndicatorService {

    private static final int PERCENT_K_LENGTH = 10;    // a
    private static final int PERCENT_D_LENGTH = 3;     // b
    private static final int EMA_SIGNAL_LENGTH = 10;   // c
    private static final int SMOOTH_PERIOD = 5;
    private static final double OVERBOUGHT = 40.0;
    private static final double OVERSOLD = -40.0;

    private final CandleStickService candleStickService;

    public SMIIndicatorService(CandleStickService candleStickService) {
        this.candleStickService = candleStickService;
    }

    public SMIResult calculate() {
        List<CandleStick> candles = candleStickService.getCandles();
        return calculate(candles);
    }

    public SMIResult calculate(List<CandleStick> candles) {
        if (candles == null || candles.size() < PERCENT_K_LENGTH) {
            return new SMIResult(List.of(), OVERBOUGHT, OVERSOLD);
        }

        int size = candles.size();

        // Step 1: Calculate rdiff and diff for each bar
        List<Double> rdiffArr = new ArrayList<>();
        List<Double> diffArr = new ArrayList<>();
        List<Long> timeArr = new ArrayList<>();

        for (int i = PERCENT_K_LENGTH - 1; i < size; i++) {
            double hh = Double.MIN_VALUE;
            double ll = Double.MAX_VALUE;

            for (int j = 0; j < PERCENT_K_LENGTH; j++) {
                CandleStick c = candles.get(i - j);
                hh = Math.max(hh, c.high());
                ll = Math.min(ll, c.low());
            }

            double diff = hh - ll;
            double rdiff = candles.get(i).close() - (hh + ll) / 2;

            diffArr.add(diff);
            rdiffArr.add(rdiff);
            timeArr.add(candles.get(i).time());
        }

        // Step 2: EMA of rdiff and diff (period = b)
        double[] avgrel = calculateEMA(rdiffArr, PERCENT_D_LENGTH);
        double[] avgdiff = calculateEMA(diffArr, PERCENT_D_LENGTH);

        // Step 3: Calculate raw SMI
        List<Double> smiRaw = new ArrayList<>();
        for (int i = 0; i < avgrel.length; i++) {
            double smi = avgdiff[i] != 0 ? (avgrel[i] / (avgdiff[i] / 2) * 100) : 0;
            smiRaw.add(smi);
        }

        // Step 4: SMA smoothing of SMI (period = smoothPeriod)
        double[] smiSmoothed = calculateSMA(smiRaw, SMOOTH_PERIOD);

        // Step 5: EMA signal line (period = c)
        List<Double> smiSmoothedList = new ArrayList<>();
        for (double v : smiSmoothed) {
            smiSmoothedList.add(v);
        }
        double[] emaSignal = calculateEMA(smiSmoothedList, EMA_SIGNAL_LENGTH);

        // Build result
        List<SMIPoint> points = new ArrayList<>();
        for (int i = 0; i < smiSmoothed.length; i++) {
            long time = timeArr.get(i);
            double smiVal = smiSmoothed[i];
            double signalVal = emaSignal[i];

            points.add(new SMIPoint(time, smiVal, signalVal));
        }

        return new SMIResult(points, OVERBOUGHT, OVERSOLD);
    }

    /**
     * Calculate EMA (Exponential Moving Average)
     */
    private double[] calculateEMA(List<Double> data, int period) {
        double[] result = new double[data.size()];
        double multiplier = 2.0 / (period + 1);

        result[0] = data.get(0);
        for (int i = 1; i < data.size(); i++) {
            result[i] = (data.get(i) - result[i - 1]) * multiplier + result[i - 1];
        }

        return result;
    }

    /**
     * Calculate SMA (Simple Moving Average)
     */
    private double[] calculateSMA(List<Double> data, int period) {
        double[] result = new double[data.size()];

        for (int i = 0; i < data.size(); i++) {
            if (i < period - 1) {
                result[i] = data.get(i); // 초기값
            } else {
                double sum = 0;
                for (int j = 0; j < period; j++) {
                    sum += data.get(i - j);
                }
                result[i] = sum / period;
            }
        }

        return result;
    }
}
