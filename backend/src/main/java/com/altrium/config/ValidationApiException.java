package com.altrium.config;

/**
 * A request that is malformed against the organisation's rules rather than its data — most
 * importantly a reporting line that would create a loop (P-1.4). Surfaces as **400**.
 *
 * <p>Unlike a denial, the message is returned to the caller. Nothing here is confidential:
 * telling a Super Admin that an assignment would make someone their own manager reveals only
 * the structure they are already editing.
 */
public class ValidationApiException extends RuntimeException {

    public ValidationApiException(String message) {
        super(message);
    }
}
