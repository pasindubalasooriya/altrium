package com.altrium.auth;

import java.util.Set;

/**
 * Which reviewees a caller may see in a <em>collection</em>, expressed as data that becomes a
 * SQL {@code WHERE} clause.
 *
 * <p>This exists because the point decision in {@link AuthorizationService} cannot be applied
 * to a list. Fetching a page and then discarding rows in Java looks equivalent and is not:
 * the total-elements count reports what the query matched, not what survived the filter, so a
 * manager paging through "their" reviews would be told how many reviews exist for everyone
 * (P-0.3). The count has to be wrong for the leak to be closed, which means the filter has to
 * be in the query.
 *
 * <p>The three sources of visibility are unioned, matching {@link Grounds}:
 * <ul>
 *   <li>{@code includeSelf} - the caller's own row, where the capability allows it</li>
 *   <li>{@code directReportIds} - the manager's report ids, carried in the {@code WHERE}
 *       clause exactly as required, never derived from a role name</li>
 *   <li>{@code hrDepartmentIds} - the departments resolved for this request, after the
 *       own-department block and any explicit-grant override</li>
 * </ul>
 *
 * <p><strong>{@code callerId} is what makes P-2.2 a SQL predicate.</strong> The HR branch
 * always excludes the caller's own row, so an HR user's own review cannot appear in a list
 * they are entitled to read - including in its count. Without that term, an HR Head whose
 * explicit grant covers their own department would find themselves in their own monitoring
 * list, which is the same ordering bug P-0.6 exists to prevent, arriving through the back
 * door of a collection endpoint.
 *
 * @param callerId        the caller, always present - it is both a grant term and the P-2.2
 *                        exclusion term
 * @param includeSelf     whether the caller's own row is visible for this capability
 * @param directReportIds ids of the caller's direct reports (P-1.1); empty for non-managers
 * @param hrDepartmentIds departments in HR scope this request (P-2.1); empty for non-HR
 */
public record SubjectScope(
        Long callerId,
        boolean includeSelf,
        Set<Long> directReportIds,
        Set<Long> hrDepartmentIds) {

    public SubjectScope {
        directReportIds = Set.copyOf(directReportIds);
        hrDepartmentIds = Set.copyOf(hrDepartmentIds);
    }

    /** A scope covering nobody. Yields a query that matches no rows, never an unfiltered one. */
    public static SubjectScope none(Long callerId) {
        return new SubjectScope(callerId, false, Set.of(), Set.of());
    }

    public boolean isEmpty() {
        return !includeSelf && directReportIds.isEmpty() && hrDepartmentIds.isEmpty();
    }
}
