package com.altrium.auth;

/**
 * The outcome of one authorization decision, with its justification.
 *
 * <p>The service returns this rather than a bare boolean so that a permit can be audited -
 * "permitted as HR_IN_SCOPE under P-4.3" is checkable against the policy list, "true" is not
 * - and so a denial reason can be logged without ever being returned to the caller.
 *
 * <p>{@code reason} must never reach a response body. Telling a caller "not your report"
 * rather than "no such review" lets them map out what exists by probing identifiers (P-0.5).
 *
 * @param policy  the policy id that governed the decision, e.g. {@code P-2.2}
 * @param grounds why it was permitted; null on a denial
 * @param reason  server-side explanation, for logs only
 */
public record AuthorizationDecision(boolean permitted, Grounds grounds, String policy, String reason) {

    static AuthorizationDecision permit(Grounds grounds, String policy) {
        return new AuthorizationDecision(true, grounds, policy, "permitted as " + grounds);
    }

    static AuthorizationDecision deny(String policy, String reason) {
        return new AuthorizationDecision(false, null, policy, reason);
    }

    public boolean denied() {
        return !permitted;
    }
}
