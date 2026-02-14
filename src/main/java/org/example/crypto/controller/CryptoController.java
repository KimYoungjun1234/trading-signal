package org.example.crypto.controller;

import org.example.crypto.dto.CandleStick;
import org.example.crypto.dto.EMACloudResult;
import org.example.crypto.dto.MAAnglesResult;
import org.example.crypto.dto.SMIResult;
import org.example.crypto.service.CandleStickService;
import org.example.crypto.service.EMACloudIndicatorService;
import org.example.crypto.service.GateIoWebSocketClient;
import org.example.crypto.service.MAAnglesIndicatorService;
import org.example.crypto.service.SMIIndicatorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/crypto")
public class CryptoController {

    private final GateIoWebSocketClient webSocketClient;
    private final CandleStickService candleStickService;
    private final SMIIndicatorService smiIndicatorService;
    private final MAAnglesIndicatorService maAnglesIndicatorService;
    private final EMACloudIndicatorService emaCloudIndicatorService;

    public CryptoController(GateIoWebSocketClient webSocketClient,
                           CandleStickService candleStickService,
                           SMIIndicatorService smiIndicatorService,
                           MAAnglesIndicatorService maAnglesIndicatorService,
                           EMACloudIndicatorService emaCloudIndicatorService) {
        this.webSocketClient = webSocketClient;
        this.candleStickService = candleStickService;
        this.smiIndicatorService = smiIndicatorService;
        this.maAnglesIndicatorService = maAnglesIndicatorService;
        this.emaCloudIndicatorService = emaCloudIndicatorService;
    }

    // === XRP Endpoints ===

    @GetMapping("/xrp/price")
    public Map<String, String> getXrpPrice() {
        return Map.of(
            "pair", "XRP_USDT",
            "price", webSocketClient.getCurrentPrice("XRP_USDT"),
            "unit", "USDT",
            "lastUpdate", webSocketClient.getLastUpdateTime("XRP_USDT")
        );
    }

    @GetMapping("/xrp/candles")
    public List<CandleStick> getXrpCandles() {
        return candleStickService.getCandles("XRP_USDT");
    }

    @GetMapping("/xrp/smi")
    public SMIResult getXrpSMI() {
        return smiIndicatorService.calculate(candleStickService.getCandles("XRP_USDT"));
    }

    @GetMapping("/xrp/ma-angles")
    public MAAnglesResult getXrpMAAngles() {
        return maAnglesIndicatorService.calculate(candleStickService.getCandles("XRP_USDT"));
    }

    @GetMapping("/xrp/ema-cloud")
    public EMACloudResult getXrpEMACloud() {
        return emaCloudIndicatorService.calculate(candleStickService.getCandles("XRP_USDT"));
    }

    // === BTC Endpoints ===

    @GetMapping("/btc/price")
    public Map<String, String> getBtcPrice() {
        return Map.of(
            "pair", "BTC_USDT",
            "price", webSocketClient.getCurrentPrice("BTC_USDT"),
            "unit", "USDT",
            "lastUpdate", webSocketClient.getLastUpdateTime("BTC_USDT")
        );
    }

    @GetMapping("/btc/candles")
    public List<CandleStick> getBtcCandles() {
        return candleStickService.getCandles("BTC_USDT");
    }

    @GetMapping("/btc/smi")
    public SMIResult getBtcSMI() {
        return smiIndicatorService.calculate(candleStickService.getCandles("BTC_USDT"));
    }

    @GetMapping("/btc/ma-angles")
    public MAAnglesResult getBtcMAAngles() {
        return maAnglesIndicatorService.calculate(candleStickService.getCandles("BTC_USDT"));
    }

    @GetMapping("/btc/ema-cloud")
    public EMACloudResult getBtcEMACloud() {
        return emaCloudIndicatorService.calculate(candleStickService.getCandles("BTC_USDT"));
    }
}
