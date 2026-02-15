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
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final CandleStickService candleStickService;
    private final SMIIndicatorService smiIndicatorService;
    private final MAAnglesIndicatorService maAnglesIndicatorService;
    private final EMACloudIndicatorService emaCloudIndicatorService;
    private final GateIoWebSocketClient webSocketClient;
    private final SlackNotificationService slackService;
    private final TelegramNotificationService telegramService;

    // 이미 알림을 보낸 신호의 시간을 기록 (중복 방지)
    private final Set<String> notifiedSignals = ConcurrentHashMap.newKeySet();

    // 구간 내 최초 시그널만 발생하도록 추적 (contract → 시그널 발생한 봉의 time)
    private final Map<String, Long> oversoldSignalTime = new ConcurrentHashMap<>();
    private final Map<String, Long> overboughtSignalTime = new ConcurrentHashMap<>();
    // 시그널 해제 알림 중복 방지
    private final Set<String> cancelledSignals = ConcurrentHashMap.newKeySet();

    public SignalDetectionService(CandleStickService candleStickService,
                                  SMIIndicatorService smiIndicatorService,
                                  MAAnglesIndicatorService maAnglesIndicatorService,
                                  EMACloudIndicatorService emaCloudIndicatorService,
                                  GateIoWebSocketClient webSocketClient,
                                  SlackNotificationService slackService,
                                  TelegramNotificationService telegramService) {
        this.candleStickService = candleStickService;
        this.smiIndicatorService = smiIndicatorService;
        this.maAnglesIndicatorService = maAnglesIndicatorService;
        this.emaCloudIndicatorService = emaCloudIndicatorService;
        this.webSocketClient = webSocketClient;
        this.slackService = slackService;
        this.telegramService = telegramService;
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

    @Scheduled(cron = "0 0/10 * * * *") // 매 시 00, 10, 20, 30, 40, 50분에 헬스체크
    public void healthCheck() {
        String timeStr = TIME_FMT.format(Instant.now());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(":white_check_mark: *헬스체크* (%s)\n", timeStr));

        for (String contract : new String[]{"XRP_USDT"}) {
            String coinName = contract.replace("_USDT", "");
            List<CandleStick> candles = candleStickService.getCandles(contract);

            if (candles.isEmpty()) {
                sb.append(String.format("> *%s*: 데이터 없음\n", coinName));
                continue;
            }

            // 최신 캔들에서 현재가
            CandleStick latest = candles.get(candles.size() - 1);
            double currentPrice = latest.close();

            // 24시간 전 캔들 대비 등락률
            int idx24h = Math.max(0, candles.size() - 1440);
            double price24hAgo = candles.get(idx24h).open();
            double changePct = (currentPrice - price24hAgo) / price24hAgo * 100;
            String sign = changePct >= 0 ? "+" : "";

            // 24h 고가/저가
            double maxH = Double.MIN_VALUE;
            double minL = Double.MAX_VALUE;
            for (int ci = idx24h; ci < candles.size(); ci++) {
                maxH = Math.max(maxH, candles.get(ci).high());
                minL = Math.min(minL, candles.get(ci).low());
            }

            String fmt = "BTC".equals(coinName) ? "%.1f" : "%.4f";
            sb.append(String.format("> *%s*: " + fmt + " USDT (%s%.2f%%)\n", coinName, currentPrice, sign, changePct));
            sb.append(String.format(">   24h H: " + fmt + " / L: " + fmt + "\n", maxH, minL));
        }

        String msg = sb.toString();
        telegramService.send(msg);
        slackService.send(msg);
        log.info("Health check sent");
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

        // 최신 봉의 SMI로 구간 이탈 감지 → 시그널 플래그 리셋
        CandleStick latestCandle = candles.get(candles.size() - 1);
        var latestSmi = smiByTime.get(latestCandle.time());
        if (latestSmi != null) {
            if (latestSmi.smi() > -40) {
                oversoldSignalTime.remove(contract);
                cancelledSignals.removeIf(k -> k.startsWith(contract + "_OVERSOLD_"));
            }
            if (latestSmi.smi() < 40) {
                overboughtSignalTime.remove(contract);
                cancelledSignals.removeIf(k -> k.startsWith(contract + "_OVERBOUGHT_"));
            }
        }

        // 시그널 해제 체크: 이전에 시그널이 발생했던 봉이 더 이상 유효하지 않으면 해제 알림
        String coinName = contract.replace("_USDT", "");
        String price = webSocketClient.getCurrentPrice(contract);

        Long oversoldTime = oversoldSignalTime.get(contract);
        if (oversoldTime != null) {
            int signalIdx = -1;
            for (int i = 0; i < candles.size(); i++) {
                if (candles.get(i).time() == oversoldTime) { signalIdx = i; break; }
            }
            if (signalIdx > 0 && !scanOversoldZone(smiByTime, candles, signalIdx)) {
                String cancelKey = contract + "_OVERSOLD_" + oversoldTime;
                if (cancelledSignals.add(cancelKey)) {
                    String timeStr = TIME_FMT.format(Instant.ofEpochSecond(oversoldTime));
                    var smiAt = smiByTime.get(oversoldTime);
                    String msg = String.format(
                            ":x: *[시그널 해제] [%s] 롱포지션 해제*\n" +
                            "> 시간: %s\n" +
                            "> 현재가: %s USDT\n" +
                            "> SMI: %.1f (조건 미충족)",
                            coinName, timeStr, price,
                            smiAt != null ? smiAt.smi() : 0.0);
                    telegramService.send(msg);
                    slackService.send(msg);
                    log.info("LONG signal cancelled for {} at {}", contract, timeStr);
                }
                oversoldSignalTime.remove(contract);
            }
        }

        Long overboughtTime = overboughtSignalTime.get(contract);
        if (overboughtTime != null) {
            int signalIdx = -1;
            for (int i = 0; i < candles.size(); i++) {
                if (candles.get(i).time() == overboughtTime) { signalIdx = i; break; }
            }
            if (signalIdx > 0 && !scanOverboughtZone(smiByTime, candles, signalIdx)) {
                String cancelKey = contract + "_OVERBOUGHT_" + overboughtTime;
                if (cancelledSignals.add(cancelKey)) {
                    String timeStr = TIME_FMT.format(Instant.ofEpochSecond(overboughtTime));
                    var smiAt = smiByTime.get(overboughtTime);
                    String msg = String.format(
                            ":x: *[시그널 해제] [%s] 숏포지션 해제*\n" +
                            "> 시간: %s\n" +
                            "> 현재가: %s USDT\n" +
                            "> SMI: %.1f (조건 미충족)",
                            coinName, timeStr, price,
                            smiAt != null ? smiAt.smi() : 0.0);
                    telegramService.send(msg);
                    slackService.send(msg);
                    log.info("SHORT signal cancelled for {} at {}", contract, timeStr);
                }
                overboughtSignalTime.remove(contract);
            }
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

            String timeStr = TIME_FMT.format(Instant.ofEpochSecond(cur.time()));

            // [전략1] 롱포지션 — 비활성화
            /*
            if (ema.upTrend()) {
                boolean smiCrossUp = smiCur.smi() > smiCur.signal() && smiPrev.smi() <= smiPrev.signal();
                boolean nearOversold = smiPrev.smi() >= -60 && smiPrev.smi() <= -25;
                boolean maGreen = ma.jmaSlope() >= 0;

                if (smiCrossUp && nearOversold && maGreen) {
                    String key = contract + "_LONG_" + cur.time();
                    if (notifiedSignals.add(key)) {
                        String msg = String.format(
                                ":chart_with_upwards_trend: *[전략1] [%s] 롱포지션 신호*\n" +
                                "> 시간: %s\n" +
                                "> 현재가: %s USDT\n" +
                                "> SMI: %.1f (시그널: %.1f)\n" +
                                "> MA Angles: slope %.2f",
                                coinName, timeStr, price,
                                smiCur.smi(), smiCur.signal(),
                                ma.jmaSlope());
                        slackService.send(msg);
                    }
                }
            }

            // [전략1] 숏포지션
            if (ema.downTrend()) {
                boolean smiCrossDown = smiCur.smi() < smiCur.signal() && smiPrev.smi() >= smiPrev.signal();
                boolean nearOverbought = smiPrev.smi() >= 25 && smiPrev.smi() <= 60;
                boolean maRed = ma.jmaSlope() < 0;

                if (smiCrossDown && nearOverbought && maRed) {
                    String key = contract + "_SHORT_" + cur.time();
                    if (notifiedSignals.add(key)) {
                        String msg = String.format(
                                ":chart_with_downwards_trend: *[전략1] [%s] 숏포지션 신호*\n" +
                                "> 시간: %s\n" +
                                "> 현재가: %s USDT\n" +
                                "> SMI: %.1f (시그널: %.1f)\n" +
                                "> MA Angles: slope %.2f",
                                coinName, timeStr, price,
                                smiCur.smi(), smiCur.signal(),
                                ma.jmaSlope());
                        slackService.send(msg);
                    }
                }
            }
            */

            // 롱 신호: 구간 내 최초 시그널만 발생, 전략2 우선
            if (!oversoldSignalTime.containsKey(contract) && scanOversoldZone(smiByTime, candles, i)) {
                boolean s2Long = ema.upTrend() && ma.jmaSlope() >= 0;
                if (s2Long) {
                    oversoldSignalTime.put(contract, cur.time());
                    String key = contract + "_S2_LONG_" + cur.time();
                    if (notifiedSignals.add(key)) {
                        log.info("[전략2] LONG signal detected: key={}, smi={}", key, smiCur.smi());
                        String msg = String.format(
                                ":chart_with_upwards_trend: *[전략] [%s] 롱포지션 신호*\n" +
                                "> 시간: %s\n" +
                                "> 현재가: %s USDT\n" +
                                "> SMI: %.1f (과매도 구간 반등)\n" +
                                "> MA Angles: slope %.2f",
                                coinName, timeStr, price,
                                smiCur.smi(), ma.jmaSlope());
                        telegramService.send(msg);
                        slackService.send(msg);
                    }
                } else if (ma.jmaSlope() >= 0) {
                    oversoldSignalTime.put(contract, cur.time());
                    String key = contract + "_S3_LONG_" + cur.time();
                    if (notifiedSignals.add(key)) {
                        log.info("[전략3] LONG signal detected: key={}, smi={}", key, smiCur.smi());
                        String msg = String.format(
                                ":chart_with_upwards_trend: *[전략] [%s] 롱포지션 신호*\n" +
                                "> 시간: %s\n" +
                                "> 현재가: %s USDT\n" +
                                "> SMI: %.1f (과매도 구간 반등)\n" +
                                "> MA Angles: slope %.2f",
                                coinName, timeStr, price,
                                smiCur.smi(), ma.jmaSlope());
                        telegramService.send(msg);
                        slackService.send(msg);
                    }
                }
            }

            // 숏 신호: 구간 내 최초 시그널만 발생, 전략2 우선
            if (!overboughtSignalTime.containsKey(contract) && scanOverboughtZone(smiByTime, candles, i)) {
                boolean s2Short = ema.downTrend() && ma.jmaSlope() < 0;
                if (s2Short) {
                    overboughtSignalTime.put(contract, cur.time());
                    String key = contract + "_S2_SHORT_" + cur.time();
                    if (notifiedSignals.add(key)) {
                        log.info("[전략2] SHORT signal detected: key={}, smi={}", key, smiCur.smi());
                        String msg = String.format(
                                ":chart_with_downwards_trend: *[전략] [%s] 숏포지션 신호*\n" +
                                "> 시간: %s\n" +
                                "> 현재가: %s USDT\n" +
                                "> SMI: %.1f (과매수 구간 반전)\n" +
                                "> MA Angles: slope %.2f",
                                coinName, timeStr, price,
                                smiCur.smi(), ma.jmaSlope());
                        telegramService.send(msg);
                        slackService.send(msg);
                    }
                } else if (ma.jmaSlope() < 0) {
                    overboughtSignalTime.put(contract, cur.time());
                    String key = contract + "_S3_SHORT_" + cur.time();
                    if (notifiedSignals.add(key)) {
                        log.info("[전략3] SHORT signal detected: key={}, smi={}", key, smiCur.smi());
                        String msg = String.format(
                                ":chart_with_downwards_trend: *[전략] [%s] 숏포지션 신호*\n" +
                                "> 시간: %s\n" +
                                "> 현재가: %s USDT\n" +
                                "> SMI: %.1f (과매수 구간 반전)\n" +
                                "> MA Angles: slope %.2f",
                                coinName, timeStr, price,
                                smiCur.smi(), ma.jmaSlope());
                        telegramService.send(msg);
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

    /**
     * 현재 봉에서 과거로 역추적하여 SMI <= -40 구간을 스캔.
     * 구간에 2봉 이상 머물렀고, 현재 SMI가 구간 내 최저점보다 높으면 true.
     */
    private boolean scanOversoldZone(Map<Long, SMIResult.SMIPoint> smiByTime,
                                     List<CandleStick> candles, int currentIdx) {
        var smiCur = smiByTime.get(candles.get(currentIdx).time());
        if (smiCur == null) return false;

        // 현재 봉도 -40 이하 구간 안에 있어야 함
        if (smiCur.smi() > -40) return false;

        int dwellCount = 1; // 현재 봉 포함
        double minSmi = smiCur.smi();

        // 현재 봉 바로 직전부터 역추적
        for (int j = currentIdx - 1; j >= 0; j--) {
            var smiPoint = smiByTime.get(candles.get(j).time());
            if (smiPoint == null) break;

            if (smiPoint.smi() <= -40) {
                dwellCount++;
                minSmi = Math.min(minSmi, smiPoint.smi());
            } else {
                break;
            }
        }

        // 4봉 이상 머물렀고, 현재 SMI가 구간 내 최저점보다 높으면 반등 시작
        return dwellCount >= 4 && smiCur.smi() > minSmi;
    }

    /**
     * 현재 봉에서 과거로 역추적하여 SMI >= +40 구간을 스캔.
     * 구간에 2봉 이상 머물렀고, 현재 SMI가 구간 내 최고점보다 낮으면 true.
     */
    private boolean scanOverboughtZone(Map<Long, SMIResult.SMIPoint> smiByTime,
                                       List<CandleStick> candles, int currentIdx) {
        var smiCur = smiByTime.get(candles.get(currentIdx).time());
        if (smiCur == null) return false;

        // 현재 봉도 +40 이상 구간 안에 있어야 함
        if (smiCur.smi() < 40) return false;

        int dwellCount = 1; // 현재 봉 포함
        double maxSmi = smiCur.smi();

        for (int j = currentIdx - 1; j >= 0; j--) {
            var smiPoint = smiByTime.get(candles.get(j).time());
            if (smiPoint == null) break;

            if (smiPoint.smi() >= 40) {
                dwellCount++;
                maxSmi = Math.max(maxSmi, smiPoint.smi());
            } else {
                break;
            }
        }

        // 4봉 이상 머물렀고, 현재 SMI가 구간 내 최고점보다 낮으면 반전 시작
        return dwellCount >= 4 && smiCur.smi() < maxSmi;
    }
}
