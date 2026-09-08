package com.altrium.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Google Calendar v3, over plain HTTP.
 *
 * <p>Two endpoints, both on the connected person's own primary calendar. There is no
 * impersonation and no service account: Altrium acts as the person who consented and can
 * reach nothing they could not reach themselves.
 */
@Component
public class GoogleApiCalendarClient implements CalendarClient {

    private static final String BASE = "https://www.googleapis.com/calendar/v3";

    private final RestClient http;

    public GoogleApiCalendarClient(RestClient.Builder builder) {
        this.http = builder.build();
    }

    @Override
    public List<TimeSlot> busy(String accessToken, TimeSlot window) {
        Map<String, Object> request = Map.of(
                "timeMin", window.start().toString(),
                "timeMax", window.end().toString(),
                "items", List.of(Map.of("id", "primary")));

        JsonNode body = http.post()
                .uri(BASE + "/freeBusy")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);

        List<TimeSlot> busy = new ArrayList<>();
        if (body == null) {
            return busy;
        }
        JsonNode periods = body.path("calendars").path("primary").path("busy");
        for (JsonNode period : periods) {
            busy.add(new TimeSlot(
                    Instant.parse(period.path("start").asText()),
                    Instant.parse(period.path("end").asText())));
        }
        return busy;
    }

    @Override
    public CreatedEvent create(String accessToken, EventRequest event) {
        Map<String, Object> payload = Map.of(
                "summary", event.title(),
                "description", event.description(),
                "start", Map.of("dateTime", event.when().start().toString()),
                "end", Map.of("dateTime", event.when().end().toString()),
                "attendees", List.of(Map.of("email", event.attendeeEmail())));

        JsonNode body = http.post()
                // sendUpdates=all is what actually invites the other party. Without a shared
                // Workspace directory the email is the invitation, so omitting this would
                // create an event nobody outside the organiser's calendar ever hears about.
                .uri(BASE + "/calendars/primary/events?sendUpdates=all")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (body == null || !body.hasNonNull("id")) {
            throw new IllegalStateException("Google Calendar returned no event id");
        }
        return new CreatedEvent(body.get("id").asText(),
                body.hasNonNull("htmlLink") ? body.get("htmlLink").asText() : null);
    }
}
