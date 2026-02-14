package org.example.crypto.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class SlackNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SlackNotificationService.class);

    @Value("${slack.webhook-url}")
    private String webhookUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public void send(String message) {
        if (webhookUrl == null || webhookUrl.contains("YOUR/WEBHOOK/URL")) {
            log.warn("Slack webhook URL not configured. Message: {}", message);
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String payload = """
                {"text": "%s"}""".formatted(message.replace("\"", "\\\""));

            HttpEntity<String> request = new HttpEntity<>(payload, headers);
            restTemplate.postForEntity(webhookUrl, request, String.class);
            log.info("Slack notification sent: {}", message);
        } catch (Exception e) {
            log.error("Failed to send Slack notification", e);
        }
    }
}
