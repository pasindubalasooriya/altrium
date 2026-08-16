package com.altrium.org;

/**
 * Roles are additive (P-0.1): every principal is an {@link #EMPLOYEE} in addition to any
 * other role they hold. Nothing here is mutually exclusive.
 *
 * <p>The string values must match the {@code ck_user_role_value} check constraint in V1.
 */
public enum Role {

    EMPLOYEE,
    MANAGER,
    HR,

    /** The C-suite. Never a reviewee, holds no PDP or PIP (P-7.2), aggregate metrics only (P-7.1). */
    LEADERSHIP,

    /** Users, org structure, grants and cycle configuration — but no review content (P-9.4). */
    SUPER_ADMIN;

    /** Spring Security authority name, e.g. {@code ROLE_HR}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
