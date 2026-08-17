package com.altrium.web;

import com.altrium.auth.CurrentUser;
import com.altrium.org.Role;

import java.util.List;
import java.util.Set;

/**
 * What the caller is told about themselves after login.
 *
 * <p>Carries identity and roles only. No review, rating or plan content appears here - the
 * landing decision must not become a side channel for data the caller may not read.
 *
 * @param landing where the frontend should send this user after login
 */
public record MeResponse(
        Long id,
        String email,
        String fullName,
        Long departmentId,
        Long managerId,
        List<String> roles,
        String landing) {

    /**
     * Role-based landing (feature 1).
     *
     * <p>Ordered most-privileged first, because roles are additive (P-0.1) and a manager who
     * is also HR would otherwise land wherever the enum happened to fall. This decides only
     * the default screen; it grants nothing, and every screen it points at re-checks access
     * on its own.
     */
    private static final List<Role> LANDING_PRECEDENCE = List.of(
            Role.SUPER_ADMIN,
            Role.LEADERSHIP,
            Role.HR,
            Role.MANAGER,
            Role.EMPLOYEE);

    public static MeResponse from(CurrentUser user) {
        return new MeResponse(
                user.id(),
                user.email(),
                user.fullName(),
                user.departmentId(),
                user.managerId(),
                user.roles().stream().map(Enum::name).sorted().toList(),
                landingFor(user.roles()));
    }

    private static String landingFor(Set<Role> roles) {
        for (Role role : LANDING_PRECEDENCE) {
            if (roles.contains(role)) {
                return switch (role) {
                    case SUPER_ADMIN -> "/admin/users";
                    case LEADERSHIP -> "/leadership/metrics";
                    case HR -> "/hr/cycles";
                    case MANAGER -> "/manager/team";
                    case EMPLOYEE -> "/my/reviews";
                };
            }
        }
        // Every provisioned caller is an Employee (P-0.1), so this is unreachable in practice.
        return "/my/reviews";
    }
}
