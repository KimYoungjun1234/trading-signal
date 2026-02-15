package org.example.crypto.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class TelegramNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

    @Value("${telegram.bot-token:}")
    private String botToken;

    @Value("${telegram.chat-id:}")
    private String chatId;

    private final RestTemplate restTemplate = new RestTemplate();

    public void send(String message) {
        if (botToken == null || botToken.isBlank() || chatId == null || chatId.isBlank()) {
            log.warn("Telegram not configured. Message: {}", message);
            return;
        }

        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";

            String payload = """
                {"chat_id": "%s", "text": "%s", "parse_mode": "Markdown"}"""
                .formatted(chatId, message.replace("\"", "\\\""));

            var headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            var request = new org.springframework.http.HttpEntity<>(payload, headers);

            restTemplate.postForEntity(url, request, String.class);
            log.info("Telegram notification sent");
        } catch (Exception e) {
            log.error("Failed to send Telegram notification", e);
        }
    }
}
