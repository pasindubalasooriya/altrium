package com.altrium.auth;

import com.altrium.org.AppUser;
import com.altrium.org.Role;

/**
 * The person an artifact is about, reduced to the four facts any authorization decision
 * needs about them.
 *
 * <p>Deliberately not the {@code AppUser} entity. The decision must be able to state exactly
 * what it depended on, and a live entity invites a decision to quietly depend on something
 * else — a lazily-loaded association, a field that changes mid-transaction — that no test
 * then covers.
 *
 * @param id           the reviewee
 * @param departmentId the unit of HR scoping (P-2.1, P-2.3); null only for Leadership
 * @param managerId    the single reporting line that {@code isManagerOf} tests (P-1.1)
 * @param leadership   Leadership hold no review, rating or plan at all (P-1.5, P-7.2)
 * @param active       soft-deleted subjects keep their history and stay readable (P-0.7)
 */
public record ReviewSubject(
        Long id,
        Long departmentId,
        Long managerId,
        boolean leadership,
        boolean active) {

    public static ReviewSubject of(AppUser user) {
        return new ReviewSubject(
                user.getId(),
                user.getDepartment() == null ? null : user.getDepartment().getId(),
                user.getManager() == null ? null : user.getManager().getId(),
                user.getRoles().contains(Role.LEADERSHIP),
                user.isActive());
    }

    /** P-1.1: direct reports only. A skip-level manager is not a manager here. */
    public boolean isManagedBy(Long callerId) {
        return managerId != null && managerId.equals(callerId);
    }
}
