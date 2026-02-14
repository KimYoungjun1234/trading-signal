package org.example.crypto.service;

import org.example.crypto.dto.CandleStick;
import org.example.crypto.dto.MAAnglesResult;
import org.example.crypto.dto.MAAnglesResult.MAAnglesPoint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * MA Angles Indicator Service
 * Based on Pine Script "ma angles - JD" by JD
 * Using CLOSE price as source (종가 기준)
 */
@Service
public class MAAnglesIndicatorService {

    private static final double THRESHOLD = 2.0;
    private static final double RAD2DEGREE = 180.0 / Math.PI;

    private final CandleStickService candleStickService;

    public MAAnglesIndicatorService(CandleStickService candleStickService) {
        this.candleStickService = candleStickService;
    }

    public MAAnglesResult calculate() {
        List<CandleStick> candles = candleStickService.getCandles();
        return calculate(candles);
    }

    public MAAnglesResult calculate(List<CandleStick> candles) {
        if (candles == null || candles.size() < 280) {
            return new MAAnglesResult(List.of(), THRESHOLD);
        }

        int size = candles.size();

        // 종가를 소스로 사용
        double[] src = new double[size];
        double[] high = new double[size];
        double[] low = new double[size];
        double[] close = new double[size];

        for (int i = 0; i < size; i++) {
            CandleStick c = candles.get(i);
            src[i] = c.close();  // 종가 기준
            high[i] = c.high();
            low[i] = c.low();
            close[i] = c.close();
        }

        // ATR(14) 계산
        double[] atr = calculateATR(high, low, close, 14);

        // EMA(close, 10) 스무딩 - 종가 기준 (JMA 대신 EMA 사용)
        double[] emaLine = calculateEMA(close, 10);

        // slope = angle(emaLine)
        double[] jmaSlope = new double[size];
        for (int i = 1; i < size; i++) {
            if (atr[i] > 0) {
                double diff = emaLine[i] - emaLine[i - 1];
                jmaSlope[i] = RAD2DEGREE * Math.atan(diff / atr[i]);
            }
        }

        // EMA 계산
        double[] ma27 = calculateEMA(src, 27);

        // 결과 생성
        List<MAAnglesPoint> points = new ArrayList<>();
        int startIdx = 50;  // JMA warmup 후부터

        for (int i = startIdx; i < size; i++) {
            boolean ma27Rising = ma27[i] > ma27[i - 1];
            boolean ma27Falling = ma27[i] < ma27[i - 1];

            points.add(new MAAnglesPoint(
                candles.get(i).time(),
                jmaSlope[i],
                0,
                0,
                0,
                0,
                ma27Rising,
                ma27Falling
            ));
        }

        return new MAAnglesResult(points, THRESHOLD);
    }

    /**
     * Jurik Moving Average (JMA)
     * Pine Script 원본과 동일하게 구현
     */
    private double[] calculateJMA(double[] src, int length, int phase, int power) {
        int size = src.length;
        double[] jma = new double[size];

        // Parameters
        double phaseRatio = phase < -100 ? 0.5 : phase > 100 ? 2.5 : phase / 100.0 + 1.5;
        double beta = 0.45 * (length - 1) / (0.45 * (length - 1) + 2);
        double alpha = Math.pow(beta, power);

        // State variables - Pine Script와 동일하게 0으로 초기화
        double e0 = 0;
        double e1 = 0;
        double e2 = 0;
        double jmaValue = 0;

        for (int i = 0; i < size; i++) {
            // Pine Script: e0 := (1 - alpha) * _src + alpha * nz(e0[1])
            e0 = (1 - alpha) * src[i] + alpha * e0;

            // Pine Script: e1 := (_src - e0) * (1 - beta) + beta * nz(e1[1])
            e1 = (src[i] - e0) * (1 - beta) + beta * e1;

            // Pine Script: e2 := (e0 + phaseRatio * e1 - nz(jma[1])) * pow(1 - alpha, 2) + pow(alpha, 2) * nz(e2[1])
            double e2New = (e0 + phaseRatio * e1 - jmaValue) * Math.pow(1 - alpha, 2) + Math.pow(alpha, 2) * e2;
            e2 = e2New;

            // Pine Script: jma := e2 + nz(jma[1])
            jmaValue = e2 + jmaValue;

            jma[i] = jmaValue;
        }

        return jma;
    }

    /**
     * ATR (Average True Range) with RMA smoothing
     */
    private double[] calculateATR(double[] high, double[] low, double[] close, int period) {
        int size = high.length;
        double[] tr = new double[size];
        double[] atr = new double[size];

        // True Range
        for (int i = 0; i < size; i++) {
            if (i == 0) {
                tr[i] = high[i] - low[i];
            } else {
                double hl = high[i] - low[i];
                double hc = Math.abs(high[i] - close[i - 1]);
                double lc = Math.abs(low[i] - close[i - 1]);
                tr[i] = Math.max(hl, Math.max(hc, lc));
            }
        }

        // RMA (Wilder's smoothing) - Pine Script atr() 함수와 동일
        double rma = 0;
        for (int i = 0; i < size; i++) {
            if (i == 0) {
                rma = tr[i];
            } else {
                rma = (rma * (period - 1) + tr[i]) / period;
            }
            atr[i] = rma;
        }

        return atr;
    }

    /**
     * EMA (Exponential Moving Average)
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
