package com.altrium.config;

/**
 * A resource that genuinely does not exist. Surfaces as **404**.
 *
 * <p>Use with care. This is only correct for resources the caller is already entitled to
 * address — Super Admin org management, where the whole organisation is in scope. Anything
 * the authorization layer scopes away must return **403**, never 404, or the difference
 * between the two lets a caller enumerate what exists by probing identifiers (P-0.5).
 */
public class NotFoundApiException extends RuntimeException {

    public NotFoundApiException(String message) {
        super(message);
    }
}
