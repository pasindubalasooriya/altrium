package com.altrium.config;

/**
 * A request the caller is entitled to make, refused because it conflicts with what is
 * already recorded - a duplicate email, a second peer submission (P-3.5). Surfaces as
 * **409**.
 *
 * <p>Deliberately not 403. The blanket "denials return 403" rule governs *access* decisions;
 * answering 403 here would tell a legitimate caller they lack permission they actually have,
 * and would hide a real data problem behind a security-shaped response.
 */
public class ConflictApiException extends RuntimeException {

    public ConflictApiException(String message) {
        super(message);
    }
}
