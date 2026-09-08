package com.altrium.web.calendar;

import com.altrium.calendar.GoogleConnectionService;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.Map;

/**
 * Connecting a personal Google account (scenario section 12, section 14).
 *
 * <p>Three of these four endpoints are ordinary authenticated calls about the caller's own
 * account. The fourth, {@link #callback}, is the exception in the whole API and is explained
 * where it sits.
 */
@RestController
@RequestMapping("/api/integrations/google")
public class GoogleIntegrationController {

    private static final Logger log = LoggerFactory.getLogger(GoogleIntegrationController.class);

    private final GoogleConnectionService connections;
    private final String appUrl;

    public GoogleIntegrationController(GoogleConnectionService connections,
                                       @Value("${altrium.app-url:http://localhost:5173}") String appUrl) {
        this.connections = connections;
        this.appUrl = appUrl;
    }

    @GetMapping("/status")
    @Operation(summary = "Whether the caller has connected their Google Calendar")
    public GoogleConnectionService.Status status() {
        return connections.status();
    }

    /**
     * Starts the consent flow.
     *
     * <p>Returns the URL rather than redirecting, so the browser navigates from the frontend
     * and comes back to a page that knows what it was doing. A 302 from an XHR would be
     * followed by the fetch layer and consent would never be seen.
     */
    @PostMapping("/authorize")
    @Operation(summary = "Begin connecting the caller's own Google Calendar")
    public Map<String, String> authorize() {
        return Map.of("authorizationUrl", connections.beginConnect());
    }

    /**
     * Google's redirect back, and <strong>the one endpoint in Altrium that carries no bearer
     * token</strong>.
     *
     * <p>It cannot carry one. This is a top-level browser navigation initiated by Google, not
     * a call the frontend makes, so there is no Authorization header to attach and no session
     * to fall back on. What identifies the person instead is the {@code state} parameter, which
     * was minted against their user id when they clicked Connect and is single-use and
     * short-lived. See {@code OAuthStateStore}, where that reasoning is set out in full, and
     * {@code SecurityConfig}, where this path is the sole authenticated-endpoint exception.
     *
     * <p>It ends in a redirect back into the frontend rather than a JSON body, because a person
     * is looking at it. The outcome travels as a query parameter and carries no detail beyond
     * connected or not: this URL ends up in browser history.
     */
    @GetMapping("/callback")
    @Operation(summary = "Google OAuth redirect target; authenticated by the state parameter, not a token")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        String outcome;
        try {
            if (error != null) {
                // The person declined, or Google refused. Not an Altrium failure, so it is not
                // logged as one and they are simply returned to where they started.
                outcome = "declined";
            } else {
                connections.completeConnect(state, code);
                outcome = "connected";
            }
        } catch (RuntimeException e) {
            // Whatever went wrong, the person gets a page rather than a stack trace, and the
            // reason stays in the server log. The message could name the account or the state.
            log.warn("Google Calendar connection failed", e);
            outcome = "failed";
        }

        URI back = UriComponentsBuilder.fromUriString(appUrl)
                .path("/settings/calendar")
                .query("google=" + URLEncoder.encode(outcome, StandardCharsets.UTF_8))
                .build(true)
                .toUri();

        return ResponseEntity.status(HttpStatus.FOUND).location(back).build();
    }

    /**
     * Forgets the stored token for the caller.
     *
     * <p>Takes no user id, because there is no such thing as disconnecting somebody else.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Disconnect the caller's own Google Calendar")
    public void disconnect() {
        connections.disconnect();
    }
}
