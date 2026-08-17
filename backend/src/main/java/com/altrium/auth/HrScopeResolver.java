package com.altrium.auth;

import com.altrium.org.HrDepartmentGrant;
import com.altrium.org.HrDepartmentGrantRepository;
import com.altrium.org.Role;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.annotation.RequestScope;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out which departments the calling HR user may act in - freshly, on every request.
 *
 * <p><strong>Request-scoped by design, and this is the point of the class.</strong> P-2.5
 * requires that a grant change apply on the caller's very next request. Caching the answer at
 * login, or on the authentication, or in the JWT, would leave a revoked HR user operating in
 * a department they no longer hold until their token expired - which for a
 * conflict-of-interest control is the whole failure mode. The lookup is memoised for the
 * duration of one request and thrown away with it, so a single request stays internally
 * consistent without any decision outliving it.
 *
 * <p>Two of the three HR rules are applied here, in order:
 * <ol>
 *   <li><b>P-2.1</b> - only granted departments count.</li>
 *   <li><b>P-2.3</b> - the caller's own department is removed.</li>
 *   <li><b>P-2.4</b> - unless that grant carries the explicit flag, which puts it back.</li>
 * </ol>
 *
 * <p>The third rule, <b>P-2.2</b>, is deliberately absent. The own-review block concerns who
 * the <em>subject</em> of an artifact is, not which department it belongs to, so it cannot be
 * expressed as a department set and must be checked separately - and first. Folding it in
 * here would produce exactly the ordering bug P-0.6 warns about: an HR Head whose explicit
 * grant covers their own department would reach their own review.
 */
@Component
@RequestScope
public class HrScopeResolver {

    private final CurrentUserService currentUser;
    private final HrDepartmentGrantRepository grants;

    /** Memoised for this request only. Never populated at login, never reused across requests. */
    private HrScope resolved;

    public HrScopeResolver(CurrentUserService currentUser, HrDepartmentGrantRepository grants) {
        this.currentUser = currentUser;
        this.grants = grants;
    }

    @Transactional(readOnly = true)
    public HrScope resolve() {
        if (resolved != null) {
            return resolved;
        }
        resolved = compute();
        return resolved;
    }

    private HrScope compute() {
        CurrentUser caller = currentUser.find().orElse(null);

        // Only HR hold a department scope. A manager's authority comes from who reports to
        // them, and Leadership see aggregates only - neither is expressed in these grants.
        if (caller == null || !caller.hasRole(Role.HR)) {
            return HrScope.NONE;
        }

        List<HrDepartmentGrant> held = grants.findByHrUserId(caller.id());
        Long ownDepartment = caller.departmentId();

        Set<Long> effective = new LinkedHashSet<>();
        boolean ownDepartmentLifted = false;

        for (HrDepartmentGrant grant : held) {
            Long departmentId = grant.getDepartment().getId();
            boolean isOwnDepartment = departmentId.equals(ownDepartment);

            if (!isOwnDepartment) {
                effective.add(departmentId);
                continue;
            }

            // P-2.3 blocks the caller's own department; P-2.4 lifts that block only when the
            // Super Admin marked this specific grant explicit. A plain grant over your own
            // department buys nothing, which is what makes the control a segregation of
            // duties rather than a formality.
            if (grant.isExplicitGrant()) {
                effective.add(departmentId);
                ownDepartmentLifted = true;
            }
        }

        return new HrScope(Set.copyOf(effective), ownDepartment, ownDepartmentLifted);
    }
}
