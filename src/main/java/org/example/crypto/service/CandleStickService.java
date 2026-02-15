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
        "https://api.gateio.ws/api/v4/futures/usdt/candlesticks?contract=%s&interval=%s&limit=2000";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();
    // key = "contract_interval", e.g. "XRP_USDT_1m"
    private final Map<String, List<CandleStick>> candleSticksMap = new ConcurrentHashMap<>();

    private String makeKey(String contract, String interval) {
        return contract + "_" + interval;
    }

    @PostConstruct
    public void init() {
        loadInitialCandles("XRP_USDT", "1m");
        loadInitialCandles("BTC_USDT", "1m");
    }

    public void loadInitialCandles(String contract, String interval) {
        try {
            String url = CANDLE_API_URL.formatted(contract, interval);
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

            String key = makeKey(contract, interval);
            List<CandleStick> list = candleSticksMap.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
            list.clear();
            list.addAll(initialCandles);
            log.info("Loaded {} candles for {} (interval={})", list.size(), contract, interval);
        } catch (Exception e) {
            log.error("Failed to load initial candles for {} (interval={})", contract, interval, e);
        }
    }

    public void updateCandle(String contract, String interval, CandleStick candle) {
        String key = makeKey(contract, interval);
        List<CandleStick> candleSticks = candleSticksMap.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());

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

    public void updateCandle(String contract, CandleStick candle) {
        updateCandle(contract, "1m", candle);
    }

    public void updateCandle(CandleStick candle) {
        updateCandle("XRP_USDT", "1m", candle);
    }

    public List<CandleStick> getCandles(String contract, String interval) {
        String key = makeKey(contract, interval);
        List<CandleStick> list = candleSticksMap.get(key);
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    public List<CandleStick> getCandles(String contract) {
        return getCandles(contract, "1m");
    }

    public List<CandleStick> getCandles() {
        return getCandles("XRP_USDT", "1m");
    }

    public CandleStick getLatestCandle(String contract) {
        return getLatestCandle(contract, "1m");
    }

    public CandleStick getLatestCandle(String contract, String interval) {
        String key = makeKey(contract, interval);
        List<CandleStick> list = candleSticksMap.get(key);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(list.size() - 1);
    }

    public CandleStick getLatestCandle() {
        return getLatestCandle("XRP_USDT", "1m");
    }
}
