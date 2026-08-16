package com.altrium.auth;

/**
 * Thrown whenever the authorization layer refuses an action. Always surfaces as **403**
 * (P-0.5), with a body that never distinguishes "does not exist" from "not permitted" —
 * telling the two apart would let a caller enumerate resources they cannot read.
 *
 * <p>Reserved for access decisions. A refusal caused by the state of a resource rather than
 * the caller's permission is a different thing: a duplicate peer submission returns 409,
 * because the peer does have permission and it is the record that forbids the write (P-3.5).
 */
public class AccessDeniedApiException extends RuntimeException {

    public AccessDeniedApiException(String message) {
        super(message);
    }
}
