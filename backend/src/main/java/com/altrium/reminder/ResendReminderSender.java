package com.altrium.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Sends through Resend (scenario section 13, tech stack section 8).
 *
 * <p>Active only when {@code altrium.reminders.api-key} has a value, so a machine without the
 * key falls back to {@link LoggingReminderSender} rather than failing at startup. Configuration
 * that is absent is a normal state here, not an error.
 *
 * <p>Plain HTTP against the REST API rather than the official SDK. One endpoint, four fields,
 * and no dependency to keep current; the SDK would earn its place if this ever needed
 * attachments, batching or webhooks.
 */
@Component
@ConditionalOnProperty(name = "altrium.reminders.api-key")
public class ResendReminderSender implements ReminderSender {

    private static final Logger log = LoggerFactory.getLogger(ResendReminderSender.class);

    private final RestClient http;
    private final String from;

    public ResendReminderSender(RestClient.Builder builder,
                                @Value("${altrium.reminders.api-key}") String apiKey,
                                @Value("${altrium.reminders.from:Altrium <onboarding@resend.dev>}") String from) {
        this.http = builder
                .baseUrl("https://api.resend.com")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String body) {
        // Text only, no HTML. A reminder is one sentence and a link; an HTML part would be two
        // versions of the same message to keep in step for no gain.
        Map<String, Object> payload = Map.of(
                "from", from,
                "to", new String[]{to},
                "subject", subject,
                "text", body);

        // Any failure propagates. The caller records nothing when it does, so the reminder is
        // retried on the next sweep rather than being lost or, worse, logged as sent.
        http.post()
                .uri("/emails")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();

        log.debug("Reminder sent to {}", to);
    }
}
