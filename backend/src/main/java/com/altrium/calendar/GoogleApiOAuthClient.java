package com.altrium.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Google's OAuth 2.0 endpoints, over plain HTTP.
 *
 * <p>No Google client library, for the same reason the Resend sender has no SDK: this is three
 * endpoints and a handful of fields, and the library would bring a dependency tree, a
 * credential abstraction and an HTTP stack of its own to save very little.
 */
@Component
public class GoogleApiOAuthClient implements GoogleOAuthClient {

    /**
     * The narrowest scope that can do the job.
     *
     * <p>{@code calendar.events} writes events and reads free/busy on the person's own
     * calendar. Not {@code calendar}, which also gives away calendar settings, sharing rules
     * and the ability to delete calendars outright. {@code userinfo.email} is there only so
     * the person can be shown which account they connected.
     */
    static final String SCOPES = "https://www.googleapis.com/auth/calendar.events "
            + "https://www.googleapis.com/auth/userinfo.email";

    private final RestClient http;
    private final ObjectMapper json;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public GoogleApiOAuthClient(RestClient.Builder builder,
                                ObjectMapper json,
                                @Value("${altrium.google.client-id:}") String clientId,
                                @Value("${altrium.google.client-secret:}") String clientSecret,
                                @Value("${altrium.google.redirect-uri}") String redirectUri) {
        this.http = builder.build();
        this.json = json;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    @Override
    public String authorizationUrl(String state) {
        return UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPES)
                // Offline access is what produces a refresh token at all; without it Altrium
                // could book a meeting for the next hour and nothing after that.
                .queryParam("access_type", "offline")
                // Google issues a refresh token only on the first consent unless this is set.
                // Somebody reconnecting after a revoke would otherwise complete the flow
                // successfully and leave no usable credential behind.
                .queryParam("prompt", "consent")
                .queryParam("include_granted_scopes", "true")
                .queryParam("state", state)
                .build(true)
                .toUriString();
    }

    @Override
    public GoogleTokens exchange(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");

        JsonNode body = post(form);

        return new GoogleTokens(
                text(body, "refresh_token"),
                text(body, "access_token"),
                emailFrom(text(body, "id_token")),
                text(body, "scope"));
    }

    @Override
    public String accessToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("refresh_token", refreshToken);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("grant_type", "refresh_token");

        return text(post(form), "access_token");
    }

    private JsonNode post(MultiValueMap<String, String> form) {
        return http.post()
                .uri("https://oauth2.googleapis.com/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
    }

    /**
     * The Gmail address, read from the id token's payload without checking its signature.
     *
     * <p>That is safe here and only here. The token did not arrive from a browser: it came
     * back over TLS from Google's own token endpoint in direct response to a request carrying
     * the client secret, so the channel already establishes who issued it. Verifying the
     * signature would prove the same thing a second time. Anywhere the id token arrived by any
     * other route this reasoning would not hold.
     *
     * <p>Used for one thing: showing the person which account they connected. Nothing is
     * authorized on it.
     */
    private String emailFrom(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            return "unknown";
        }
        try {
            String[] parts = idToken.split("\\.");
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            String email = text(json.readTree(new String(payload, StandardCharsets.UTF_8)), "email");
            return email == null ? "unknown" : email;
        } catch (Exception e) {
            // A label, not a credential. A connection is not worth failing over one.
            return "unknown";
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return node.get(field).asText();
    }
}
