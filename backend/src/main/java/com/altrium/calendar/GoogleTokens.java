package com.altrium.calendar;

/**
 * What Google returns when a person finishes the consent screen.
 *
 * @param refreshToken  the long-lived credential, and the only part that is stored. Null when
 *                      Google declines to issue one, which it does on a repeat consent unless
 *                      asked for offline access with {@code prompt=consent}
 * @param accessToken   an hour-long token, used immediately and never stored
 * @param googleEmail   the Gmail account actually consented with, which need not be the
 *                      person's Altrium address
 * @param grantedScopes what was granted, which may be less than what was asked for
 */
public record GoogleTokens(String refreshToken, String accessToken, String googleEmail, String grantedScopes) {
}
