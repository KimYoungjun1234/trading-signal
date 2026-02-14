package org.example.crypto.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.example.crypto.dto.CandleStick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class CandleStickService {

    private static final Logger log = LoggerFactory.getLogger(CandleStickService.class);
    private static final String CANDLE_API_URL =
        "https://api.gateio.ws/api/v4/futures/usdt/candlesticks?contract=%s&interval=1m&limit=2000";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();
    private final Map<String, List<CandleStick>> candleSticksMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadInitialCandles("XRP_USDT");
        loadInitialCandles("BTC_USDT");
    }

    public void loadInitialCandles(String contract) {
        try {
            String url = CANDLE_API_URL.formatted(contract);
            log.info("Fetching candles from: {}", url);
            String response = restTemplate.getForObject(url, String.class);
            JsonNode candles = objectMapper.readTree(response);

            List<CandleStick> initialCandles = new ArrayList<>();
            for (JsonNode candle : candles) {
                long time = candle.path("t").asLong();
                double open = candle.path("o").asDouble();
                double high = candle.path("h").asDouble();
                double low = candle.path("l").asDouble();
                double close = candle.path("c").asDouble();
                long volume = candle.path("v").asLong();

                initialCandles.add(new CandleStick(time, open, high, low, close, volume));
            }

            Collections.sort(initialCandles, (a, b) -> Long.compare(a.time(), b.time()));

            List<CandleStick> list = candleSticksMap.computeIfAbsent(contract, k -> new CopyOnWriteArrayList<>());
            list.clear();
            list.addAll(initialCandles);
            log.info("Loaded {} candles for {}", list.size(), contract);
        } catch (Exception e) {
            log.error("Failed to load initial candles for {}", contract, e);
        }
    }

    public void updateCandle(String contract, CandleStick candle) {
        List<CandleStick> candleSticks = candleSticksMap.computeIfAbsent(contract, k -> new CopyOnWriteArrayList<>());

        if (candleSticks.isEmpty()) {
            candleSticks.add(candle);
            return;
        }

        CandleStick lastCandle = candleSticks.get(candleSticks.size() - 1);

        if (candle.time() == lastCandle.time()) {
            candleSticks.set(candleSticks.size() - 1, candle);
        } else if (candle.time() > lastCandle.time()) {
            candleSticks.add(candle);
            while (candleSticks.size() > 2000) {
                candleSticks.remove(0);
            }
        }
    }

    /**
     * 기존 XRP용 호환 메서드
     */
    public void updateCandle(CandleStick candle) {
        updateCandle("XRP_USDT", candle);
    }

    public List<CandleStick> getCandles(String contract) {
        List<CandleStick> list = candleSticksMap.get(contract);
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    /**
     * 기존 XRP용 호환 메서드
     */
    public List<CandleStick> getCandles() {
        return getCandles("XRP_USDT");
    }

    public CandleStick getLatestCandle(String contract) {
        List<CandleStick> list = candleSticksMap.get(contract);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(list.size() - 1);
    }

    public CandleStick getLatestCandle() {
        return getLatestCandle("XRP_USDT");
    }
}
