package com.altrium.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The single way to ask "who is calling?".
 *
 * <p>Reads the {@link CurrentUser} the JWT converter already resolved, so this costs nothing
 * - no repeated database lookup per authorization decision, and no risk of two decisions in
 * one request disagreeing about who the caller is.
 */
@Service
public class CurrentUserService {

    public Optional<CurrentUser> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AltriumAuthenticationToken token) {
            return Optional.ofNullable(token.getCurrentUser());
        }
        return Optional.empty();
    }

    /**
     * @throws AccessDeniedApiException when the caller is not a provisioned Altrium user,
     *         which surfaces as 403 rather than revealing whether the account exists (P-0.5)
     */
    public CurrentUser require() {
        return find().orElseThrow(() ->
                new AccessDeniedApiException("Caller is not a provisioned Altrium user"));
    }
}
