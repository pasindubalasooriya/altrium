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

    private ProblemDetail denied() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle(DENIED);
        problem.setDetail(DENIED);
        return problem;
    }
}
