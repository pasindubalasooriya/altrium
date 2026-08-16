package com.altrium.config;

import com.altrium.auth.AccessDeniedApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns authorization failures into responses that leak nothing.
 *
 * <p>Every denial is 403 with the same opaque body (P-0.5). The reason is logged server-side
 * and never returned: a caller who can tell "no such review" from "not your review" can map
 * out what exists by probing identifiers.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String DENIED = "Access denied";

    @ExceptionHandler(AccessDeniedApiException.class)
    public ProblemDetail onAltriumDenial(AccessDeniedApiException ex) {
        log.info("Access denied: {}", ex.getMessage());
        return denied();
    }

    /** Denials raised by Spring Security itself, e.g. a failed {@code @PreAuthorize}. */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail onSpringDenial(AccessDeniedException ex) {
        log.info("Access denied by security layer: {}", ex.getMessage());
        return denied();
    }

    /**
     * 409, not 403: the caller has permission, but the request conflicts with what is
     * already recorded. Answering 403 would hide a data problem behind a security-shaped
     * response and mislead a legitimate caller about their own permissions.
     */
    @ExceptionHandler(ConflictApiException.class)
    public ProblemDetail onConflict(ConflictApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Conflict");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * 400. The message is safe to return: it describes the org structure the caller is
     * already editing, not anything confidential.
     */
    @ExceptionHandler(ValidationApiException.class)
    public ProblemDetail onValidation(ValidationApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid request");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * A resource the caller may legitimately address but which does not exist.
     *
     * <p>Note this is only ever reached for resources the caller is already entitled to see.
     * Anything scoped away by the authorization layer must 403 instead, never 404, or the
     * difference between the two becomes a way to enumerate what exists (P-0.5).
     */
    @ExceptionHandler(NotFoundApiException.class)
    public ProblemDetail onNotFound(NotFoundApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    private ProblemDetail denied() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle(DENIED);
        problem.setDetail(DENIED);
        return problem;
    }
}
