package org.example.crypto.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class GateIoWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(GateIoWebSocketClient.class);
    private static final String GATE_IO_WS_URL = "wss://fx-ws.gateio.ws/v4/ws/usdt";
    private static final String XRP_CONTRACT = "XRP_USDT";
    private static final String BTC_CONTRACT = "BTC_USDT";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CandleStickService candleStickService;
    private WebSocketClient webSocketClient;
    private ScheduledExecutorService pingScheduler;

    private final Map<String, String> currentPrices = new ConcurrentHashMap<>();
    private final Map<String, String> lastUpdateTimes = new ConcurrentHashMap<>();
    private final Map<String, String> changePercentages = new ConcurrentHashMap<>();

    public GateIoWebSocketClient(CandleStickService candleStickService) {
        this.candleStickService = candleStickService;
        currentPrices.put(XRP_CONTRACT, "0");
        currentPrices.put(BTC_CONTRACT, "0");
        lastUpdateTimes.put(XRP_CONTRACT, "");
        lastUpdateTimes.put(BTC_CONTRACT, "");
        changePercentages.put(XRP_CONTRACT, "0");
        changePercentages.put(BTC_CONTRACT, "0");
    }

    @PostConstruct
    public void init() {
        connect();
    }

    public void connect() {
        try {
            webSocketClient = new WebSocketClient(new URI(GATE_IO_WS_URL)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    log.info("Gate.io WebSocket connected");
                    subscribeToTicker();
                    startPingScheduler();
                }

                @Override
                public void onMessage(String message) {
                    handleMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    log.warn("WebSocket closed: {} - {}", code, reason);
                    stopPingScheduler();
                    reconnect();
                }

                @Override
                public void onError(Exception ex) {
                    log.error("WebSocket error", ex);
                }
            };

            webSocketClient.connect();
        } catch (Exception e) {
            log.error("Failed to create WebSocket client", e);
        }
    }

    private void subscribeToTicker() {
        // Subscribe to tickers for both contracts
        for (String contract : new String[]{XRP_CONTRACT, BTC_CONTRACT}) {
            String tickerMessage = """
                {
                    "time": %d,
                    "channel": "futures.tickers",
                    "event": "subscribe",
                    "payload": ["%s"]
                }
                """.formatted(System.currentTimeMillis() / 1000, contract);
            webSocketClient.send(tickerMessage);
            log.info("Subscribed to futures ticker {}", contract);

            String candleMessage = """
                {
                    "time": %d,
                    "channel": "futures.candlesticks",
                    "event": "subscribe",
                    "payload": ["1m", "%s"]
                }
                """.formatted(System.currentTimeMillis() / 1000, contract);
            webSocketClient.send(candleMessage);
            log.info("Subscribed to futures candlesticks {}", contract);
        }
    }

    private void handleMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String channel = root.path("channel").asText();
            String event = root.path("event").asText();

            if ("futures.tickers".equals(channel) && "update".equals(event)) {
                JsonNode results = root.path("result");
                for (JsonNode result : results) {
                    String contract = result.path("contract").asText();
                    String last = result.path("last").asText();
                    String changePercentage = result.path("change_percentage").asText();

                    if (currentPrices.containsKey(contract)) {
                        currentPrices.put(contract, last);
                        lastUpdateTimes.put(contract, java.time.LocalDateTime.now().toString());
                        changePercentages.put(contract, changePercentage);
                        log.info("{} Futures Price: {} USDT ({}%)", contract, last, changePercentage);
                    }
                }
            } else if ("futures.candlesticks".equals(channel) && "update".equals(event)) {
                JsonNode results = root.path("result");
                for (JsonNode result : results) {
                    String n = result.path("n").asText(); // contract name in candlestick format: "1m_XRP_USDT"
                    long time = result.path("t").asLong();
                    long volume = result.path("v").asLong();
                    double close = result.path("c").asDouble();
                    double high = result.path("h").asDouble();
                    double low = result.path("l").asDouble();
                    double open = result.path("o").asDouble();

                    // Determine contract from the "n" field (format: "1m_XRP_USDT")
                    String contract = null;
                    if (n.contains(XRP_CONTRACT)) {
                        contract = XRP_CONTRACT;
                    } else if (n.contains(BTC_CONTRACT)) {
                        contract = BTC_CONTRACT;
                    }

                    if (contract != null) {
                        var candle = new org.example.crypto.dto.CandleStick(time, open, high, low, close, volume);
                        candleStickService.updateCandle(contract, candle);
                        log.debug("{} Candle updated: {} O:{} H:{} L:{} C:{}", contract, time, open, high, low, close);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse message: {}", message);
        }
    }

    private void startPingScheduler() {
        pingScheduler = Executors.newSingleThreadScheduledExecutor();
        pingScheduler.scheduleAtFixedRate(() -> {
            if (webSocketClient != null && webSocketClient.isOpen()) {
                String pingMessage = """
                    {
                        "time": %d,
                        "channel": "futures.ping"
                    }
                    """.formatted(System.currentTimeMillis() / 1000);
                webSocketClient.send(pingMessage);
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    private void stopPingScheduler() {
        if (pingScheduler != null && !pingScheduler.isShutdown()) {
            pingScheduler.shutdown();
        }
    }

    private void reconnect() {
        try {
            Thread.sleep(5000);
            log.info("Attempting to reconnect...");
            connect();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        stopPingScheduler();
        if (webSocketClient != null) {
            webSocketClient.close();
        }
    }

    public String getCurrentPrice() {
        return getCurrentPrice(XRP_CONTRACT);
    }

    public String getCurrentPrice(String contract) {
        return currentPrices.getOrDefault(contract, "0");
    }

    public String getLastUpdateTime() {
        return getLastUpdateTime(XRP_CONTRACT);
    }

    public String getLastUpdateTime(String contract) {
        return lastUpdateTimes.getOrDefault(contract, "");
    }

    public String getChangePercentage(String contract) {
        return changePercentages.getOrDefault(contract, "0");
    }

    public String getContract() {
        return XRP_CONTRACT;
    }

    public String getContract(String contract) {
        return contract;
    }
}
