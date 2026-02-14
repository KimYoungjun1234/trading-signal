package org.example.crypto.controller;

import org.example.crypto.service.GateIoWebSocketClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/crypto")
public class CryptoPriceStreamController {

    private final GateIoWebSocketClient webSocketClient;

    public CryptoPriceStreamController(GateIoWebSocketClient webSocketClient) {
        this.webSocketClient = webSocketClient;
    }

    @GetMapping(value = "/xrp/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamXrpPrice() {
        return createPriceStream("XRP_USDT");
    }

    @GetMapping(value = "/btc/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamBtcPrice() {
        return createPriceStream("BTC_USDT");
    }

    private SseEmitter createPriceStream(String contract) {
        SseEmitter emitter = new SseEmitter(0L);

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> {
            try {
                String data = """
                    {"pair":"%s","price":"%s","unit":"USDT","lastUpdate":"%s","changePercent":"%s"}"""
                    .formatted(
                        contract,
                        webSocketClient.getCurrentPrice(contract),
                        webSocketClient.getLastUpdateTime(contract),
                        webSocketClient.getChangePercentage(contract)
                    );
                emitter.send(SseEmitter.event().data(data));
            } catch (IOException e) {
                emitter.complete();
                executor.shutdown();
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        emitter.onCompletion(executor::shutdown);
        emitter.onTimeout(executor::shutdown);

        return emitter;
    }
}
