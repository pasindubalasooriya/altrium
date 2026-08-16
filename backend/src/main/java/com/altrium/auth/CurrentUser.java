package com.altrium.auth;

import com.altrium.org.Role;

import java.util.Set;

/**
 * The resolved caller for the current request.
 *
 * <p>Deliberately a flat, immutable snapshot rather than the {@code AppUser} entity: it is
 * read on every authorization decision, and passing a detached entity around would invite
 * lazy-loading surprises inside the security filter chain.
 *
 * <p>HR department grants are <em>not</em> held here. They are resolved separately, per
 * request, because P-2.5 requires a grant change to apply on the caller's very next request.
 *
 * @param id            the {@code app_user} primary key
 * @param subject       the Asgardeo {@code sub} claim
 * @param departmentId  null for Leadership, who sit above the department structure
 * @param managerId     null only at the top of the reporting chain
 */
public record CurrentUser(
        Long id,
        String subject,
        String email,
        String fullName,
        Long departmentId,
        Long managerId,
        boolean active,
        Set<Role> roles) {

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    /** True when this caller is the subject of the artifact in question. */
    public boolean is(Long userId) {
        return id.equals(userId);
    }
}
