package com.altrium.testsupport;

import com.altrium.auth.AltriumJwtAuthenticationConverter;
import com.altrium.org.AppUser;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Puts a caller into the security context so a service-level authorization test can run
 * without an HTTP endpoint.
 *
 * <p>The principal is built by the <em>real</em> {@link AltriumJwtAuthenticationConverter},
 * not hand-assembled. A test that constructed its own {@code CurrentUser} would prove the
 * service is consistent with the test's idea of a caller rather than with the one production
 * actually produces — and the converter is where roles come from the database rather than the
 * token, which several of these tests depend on.
 *
 * <p>Each call also starts a <strong>fresh request scope</strong>. That is not incidental
 * bookkeeping: {@code HrScopeResolver} memoises per request, so switching callers — or
 * re-acting as the same caller after a grant changes — must discard it. Tests that assert
 * P-2.5 rely on this being genuinely per-request.
 */
@Component
public class Acting {

    private final AltriumJwtAuthenticationConverter converter;

    public Acting(AltriumJwtAuthenticationConverter converter) {
        this.converter = converter;
    }

    /** Begins a new request, acting as the given person. */
    public void as(AppUser user) {
        asSubject(user.getAsgardeoSubject());
    }

    /** A validated token whose subject is not provisioned in Altrium. */
    public void asUnprovisioned() {
        asSubject("asgardeo-sub-nobody-" + System.nanoTime());
    }

    /**
     * Starts a new request without changing who is acting, so a memoised per-request lookup
     * has to be redone. This is what "applies on the caller's very next request" means when
     * there is no re-login (P-2.5).
     */
    public void nextRequestAs(AppUser user) {
        as(user);
    }

    public void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void asSubject(String subject) {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));

        Jwt jwt = Jwt.withTokenValue("stub-" + subject)
                .header("alg", "none")
                .subject(subject)
                .build();

        SecurityContextHolder.getContext().setAuthentication(converter.convert(jwt));
    }
}
