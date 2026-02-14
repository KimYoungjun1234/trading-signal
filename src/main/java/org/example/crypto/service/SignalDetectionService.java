package org.example.crypto.service;

import org.example.crypto.dto.CandleStick;
import org.example.crypto.dto.EMACloudResult;
import org.example.crypto.dto.MAAnglesResult;
import org.example.crypto.dto.SMIResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SignalDetectionService {

    private static final Logger log = LoggerFactory.getLogger(SignalDetectionService.class);
    private static final int VOL_MA_LEN = 20;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final CandleStickService candleStickService;
    private final SMIIndicatorService smiIndicatorService;
    private final MAAnglesIndicatorService maAnglesIndicatorService;
    private final EMACloudIndicatorService emaCloudIndicatorService;
    private final GateIoWebSocketClient webSocketClient;
    private final SlackNotificationService slackService;

    // 이미 알림을 보낸 신호의 시간을 기록 (중복 방지)
    private final Set<String> notifiedSignals = ConcurrentHashMap.newKeySet();

    public SignalDetectionService(CandleStickService candleStickService,
                                  SMIIndicatorService smiIndicatorService,
                                  MAAnglesIndicatorService maAnglesIndicatorService,
                                  EMACloudIndicatorService emaCloudIndicatorService,
                                  GateIoWebSocketClient webSocketClient,
                                  SlackNotificationService slackService) {
        this.candleStickService = candleStickService;
        this.smiIndicatorService = smiIndicatorService;
        this.maAnglesIndicatorService = maAnglesIndicatorService;
        this.emaCloudIndicatorService = emaCloudIndicatorService;
        this.webSocketClient = webSocketClient;
        this.slackService = slackService;
    }

    @Scheduled(fixedRate = 10000) // 10초마다 체크
    public void checkSignals() {
        for (String contract : new String[]{"XRP_USDT"}) {
            try {
                detectAndNotify(contract);
            } catch (Exception e) {
                log.error("Signal detection failed for {}", contract, e);
            }
        }
    }

    private void detectAndNotify(String contract) {
        List<CandleStick> candles = candleStickService.getCandles(contract);
        if (candles.size() < 300) return;

        SMIResult smiResult = smiIndicatorService.calculate(candles);
        EMACloudResult emaResult = emaCloudIndicatorService.calculate(candles);
        MAAnglesResult maResult = maAnglesIndicatorService.calculate(candles);

        if (smiResult.data().isEmpty() || emaResult.data().isEmpty() || maResult.data().isEmpty()) return;

        // 인디케이터를 time 기준 Map으로 변환
        Map<Long, EMACloudResult.EMACloudPoint> emaByTime = new HashMap<>();
        emaResult.data().forEach(d -> emaByTime.put(d.time(), d));

        Map<Long, MAAnglesResult.MAAnglesPoint> maByTime = new HashMap<>();
        maResult.data().forEach(d -> maByTime.put(d.time(), d));

        Map<Long, SMIResult.SMIPoint> smiByTime = new HashMap<>();
        smiResult.data().forEach(d -> smiByTime.put(d.time(), d));

        // 거래량 이동평균
        double[] volumeMA = new double[candles.size()];
        for (int i = VOL_MA_LEN - 1; i < candles.size(); i++) {
            double sum = 0;
            for (int j = 0; j < VOL_MA_LEN; j++) sum += candles.get(i - j).volume();
            volumeMA[i] = sum / VOL_MA_LEN;
        }

        // 최근 5봉만 체크 (과거 신호는 프론트에서만 표시)
        int startIdx = Math.max(1, candles.size() - 5);
        for (int i = startIdx; i < candles.size(); i++) {
            CandleStick cur = candles.get(i);
            CandleStick prev = candles.get(i - 1);

            var ema = emaByTime.get(cur.time());
            var ma = maByTime.get(cur.time());
            var smiCur = smiByTime.get(cur.time());
            var smiPrev = smiByTime.get(prev.time());
            if (ema == null || ma == null || smiCur == null || smiPrev == null) continue;

            boolean volOk = i >= VOL_MA_LEN && prev.volume() > volumeMA[i - 1];
            if (!volOk) continue;

            String coinName = contract.replace("_USDT", "");
            String price = webSocketClient.getCurrentPrice(contract);
            String timeStr = TIME_FMT.format(Instant.ofEpochSecond(cur.time()));

            // 롱포지션
            if (ema.upTrend()) {
                boolean smiCrossUp = smiCur.smi() > smiCur.signal() && smiPrev.smi() <= smiPrev.signal();
                boolean nearOversold = smiPrev.smi() >= -55 && smiPrev.smi() <= -25;
                boolean maDarkGreen = ma.jmaSlope() >= 0 && ma.ma27Rising();

                if (smiCrossUp && nearOversold && maDarkGreen) {
                    String key = contract + "_LONG_" + cur.time();
                    if (notifiedSignals.add(key)) {
                        String msg = String.format(
                                ":chart_with_upwards_trend: *[%s] 롱포지션 신호*\n" +
                                "> 시간: %s\n" +
                                "> 현재가: %s USDT\n" +
                                "> SMI: %.1f (시그널: %.1f)\n" +
                                "> MA Angles: 진한초록 (slope: %.2f)",
                                coinName, timeStr, price,
                                smiCur.smi(), smiCur.signal(),
                                ma.jmaSlope());
                        slackService.send(msg);
                    }
                }
            }

            // 숏포지션
            if (ema.downTrend()) {
                boolean smiCrossDown = smiCur.smi() < smiCur.signal() && smiPrev.smi() >= smiPrev.signal();
                boolean nearOverbought = smiPrev.smi() >= 25 && smiPrev.smi() <= 55;
                boolean maDarkRed = ma.jmaSlope() < 0 && ma.ma27Falling();

                if (smiCrossDown && nearOverbought && maDarkRed) {
                    String key = contract + "_SHORT_" + cur.time();
                    if (notifiedSignals.add(key)) {
                        String msg = String.format(
                                ":chart_with_downwards_trend: *[%s] 숏포지션 신호*\n" +
                                "> 시간: %s\n" +
                                "> 현재가: %s USDT\n" +
                                "> SMI: %.1f (시그널: %.1f)\n" +
                                "> MA Angles: 진한빨강 (slope: %.2f)",
                                coinName, timeStr, price,
                                smiCur.smi(), smiCur.signal(),
                                ma.jmaSlope());
                        slackService.send(msg);
                    }
                }
            }
        }

        // 오래된 알림 기록 정리 (1000개 초과 시)
        if (notifiedSignals.size() > 1000) {
            notifiedSignals.clear();
        }
    }
}
