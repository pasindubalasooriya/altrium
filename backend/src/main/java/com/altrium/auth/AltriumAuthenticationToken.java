package com.altrium.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;

/**
 * A validated Asgardeo JWT plus the Altrium user it resolves to.
 *
 * <p>Carrying the resolved {@link CurrentUser} on the authentication itself means the
 * identity lookup happens exactly once per request, in the converter, rather than once per
 * authorization decision.
 *
 * <p>{@code currentUser} is null when the token is valid but the caller is not provisioned
 * in Altrium, or has been deactivated. Such a token carries no authorities, so every
 * endpoint denies it with 403.
 */
public class AltriumAuthenticationToken extends JwtAuthenticationToken {

    private final transient CurrentUser currentUser;

    public AltriumAuthenticationToken(Jwt jwt,
                                      Collection<? extends GrantedAuthority> authorities,
                                      CurrentUser currentUser) {
        super(jwt, authorities, jwt.getSubject());
        this.currentUser = currentUser;
    }

    public CurrentUser getCurrentUser() {
        return currentUser;
    }
}
