package com.altrium.auth;

import java.util.Set;

/**
 * The departments an HR caller may act in, for this request only.
 *
 * @param departmentIds       departments this HR user may operate in, after the
 *                            own-department block and any explicit-grant override
 * @param ownDepartmentId     the caller's own department, or null
 * @param ownDepartmentLifted true when an explicit grant lifted the own-department block
 *                            (P-2.4) — the HR Head case
 */
public record HrScope(Set<Long> departmentIds, Long ownDepartmentId, boolean ownDepartmentLifted) {

    public static final HrScope NONE = new HrScope(Set.of(), null, false);

    /**
     * Whether this caller may act on a resource belonging to the given department.
     *
     * <p>Answers P-2.1, P-2.3 and P-2.4 together, because the set has already had the
     * own-department block and any override applied to it.
     *
     * <p>Says nothing about P-2.2. The own-review block is about <em>who the subject is</em>,
     * not which department the resource sits in, so no department-level answer can express
     * it. It is checked separately, and first.
     */
    public boolean covers(Long departmentId) {
        return departmentId != null && departmentIds.contains(departmentId);
    }

    public boolean isEmpty() {
        return departmentIds.isEmpty();
    }
}
