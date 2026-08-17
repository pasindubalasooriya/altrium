package com.altrium.auth;

import com.altrium.org.AppUser;
import com.altrium.org.AppUserRepository;
import com.altrium.org.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a validated Asgardeo JWT into an Altrium principal.
 *
 * <p>Asgardeo answers "who is this?" - it authenticates, and its signature is what makes the
 * {@code sub} claim trustworthy. It does not answer "what may they do here?". That is decided
 * against the database, for two reasons:
 *
 * <ol>
 *   <li>A JWT keeps the claims it was minted with until it expires. Deriving authorities from
 *       the token would mean a revoked role stayed live for the rest of the token's lifetime -
 *       exactly the staleness P-2.5 forbids for HR grants. Resolving per request means a role
 *       change applies on the caller's very next request.</li>
 *   <li>The authorization layer must also reason about <em>other</em> users' roles (P-1.5,
 *       P-7.2), which the caller's token cannot describe.</li>
 * </ol>
 *
 * <p>Roles arriving in the token are still read, but only to log drift against the database.
 * They never grant anything.
 */
@Component
public class AltriumJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log =
            LoggerFactory.getLogger(AltriumJwtAuthenticationConverter.class);

    /**
     * Asgardeo advertises both claims: {@code roles} for tenant-level roles and
     * {@code application_roles} for roles scoped to this application. Which one appears
     * depends on how the application's user attributes are configured, so read both rather
     * than depending on a console setting staying put.
     */
    private static final List<String> ROLE_CLAIMS = List.of("roles", "application_roles");

    /**
     * Asgardeo prefixes internally-managed roles, e.g. {@code Internal/everyone} or
     * {@code Application/altrium-hr}. Only the trailing segment is meaningful to us.
     */
    private static final String ROLE_SEPARATOR = "/";

    private final AppUserRepository users;

    public AltriumJwtAuthenticationConverter(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String subject = jwt.getSubject();

        Optional<AppUser> found = users.findByAsgardeoSubjectWithRoles(subject);

        if (found.isEmpty()) {
            // Authenticated by Asgardeo, but unknown to Altrium. No authorities, so every
            // endpoint denies with 403 rather than leaking that the account is unprovisioned.
            log.warn("Token subject {} authenticated but has no app_user row", subject);
            return new AltriumAuthenticationToken(jwt, List.of(), null);
        }

        AppUser user = found.get();

        if (!user.isActive()) {
            // Soft-deleted (P-0.7). The row survives for history; the person cannot act.
            log.warn("Token subject {} resolves to a deactivated user {}", subject, user.getId());
            return new AltriumAuthenticationToken(jwt, List.of(), toCurrentUser(user));
        }

        logRoleDrift(subject, user.getRoles(), rolesFromToken(jwt));

        return new AltriumAuthenticationToken(jwt, authoritiesOf(user), toCurrentUser(user));
    }

    private CurrentUser toCurrentUser(AppUser user) {
        return new CurrentUser(
                user.getId(),
                user.getAsgardeoSubject(),
                user.getEmail(),
                user.getFullName(),
                user.getDepartment() == null ? null : user.getDepartment().getId(),
                user.getManager() == null ? null : user.getManager().getId(),
                user.isActive(),
                Set.copyOf(user.getRoles()));
    }

    /**
     * Every provisioned, active principal is an Employee in addition to any other role
     * (P-0.1). Granting it here rather than relying on the row existing makes the rule
     * structural - a seeding slip cannot leave someone without it.
     */
    private Collection<GrantedAuthority> authoritiesOf(AppUser user) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority(Role.EMPLOYEE.authority()));
        user.getRoles().forEach(role -> authorities.add(new SimpleGrantedAuthority(role.authority())));
        return authorities;
    }

    private Set<Role> rolesFromToken(Jwt jwt) {
        Set<Role> parsed = EnumSet.noneOf(Role.class);
        for (String claimName : ROLE_CLAIMS) {
            if (jwt.getClaim(claimName) instanceof Collection<?> raw) {
                raw.forEach(value -> parseRole(value).ifPresent(parsed::add));
            }
        }
        return parsed;
    }

    private Optional<Role> parseRole(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        String name = value.toString();
        int separator = name.lastIndexOf(ROLE_SEPARATOR);
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        try {
            return Optional.of(Role.valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
        } catch (IllegalArgumentException ignored) {
            // Asgardeo ships built-in roles such as Internal/everyone that mean nothing here.
            return Optional.empty();
        }
    }

    /**
     * The database governs, but a persistent mismatch means provisioning has drifted and
     * someone will eventually be surprised by it. Log rather than fail: refusing the request
     * would lock people out over a bookkeeping difference.
     */
    private void logRoleDrift(String subject, Set<Role> database, Set<Role> token) {
        if (!token.isEmpty() && !token.equals(database)) {
            log.warn("Role drift for subject {}: Asgardeo says {}, database says {}. "
                    + "Database governs; reconcile provisioning.", subject, token, database);
        }
    }
}
