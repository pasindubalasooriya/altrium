package com.altrium.calendar;

/**
 * The OAuth half of the Google integration: consent, exchange, refresh.
 *
 * <p>An interface so the tests can drive the whole flow without a network call. Every test in
 * {@code MeetingTest} runs against a stub, which is what lets the denial tests prove the
 * authorization rules rather than proving that Google was reachable.
 */
public interface GoogleOAuthClient {

    /** Where to send the browser, carrying the state that will identify the person on return. */
    String authorizationUrl(String state);

    /** Trades the one-time code from the callback for tokens. */
    GoogleTokens exchange(String code);

    /** A fresh access token from a stored refresh token. */
    String accessToken(String refreshToken);
}
