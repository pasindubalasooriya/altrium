package com.altrium.review;

import com.altrium.auth.AuthorizationService;
import com.altrium.auth.Capability;
import com.altrium.auth.SubjectScope;
import com.altrium.config.NotFoundApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feature 6 - HR monitoring a cycle across the departments they have been granted (P-6.3).
 *
 * <p>What HR need from this is operational: has the cycle fired, and who is behind. So it
 * returns counts and nothing else. That is not a simplification - it is the reason monitoring
 * can be a separate endpoint at all. A monitoring view that listed names and statuses would be
 * the review list again, with a second implementation of the scoping rules, and the two would
 * eventually disagree. Anybody who needs the individual rows already has {@code /api/reviews},
 * which is scoped by the same {@link SubjectScope}.
 *
 * <p>Both readings of "scoped" apply, in the query:
 * <ul>
 *   <li><b>P-2.1, P-2.3, P-2.4</b> - only granted departments, with the caller's own excluded
 *       unless that grant is explicit. This arrives already resolved, per request (P-2.5).</li>
 *   <li><b>P-2.2</b> - the caller's own participant row is excluded from every total. An HR
 *       Head oversees their department without their own case being part of what they oversee,
 *       and without a small department's counts becoming a channel for their own status.</li>
 * </ul>
 */
@Service
public class CycleMonitoringService {

    private final AuthorizationService authorization;
    private final CycleMonitoringRepository monitoring;
    private final ReviewCycleRepository cycles;

    public CycleMonitoringService(AuthorizationService authorization,
                                  CycleMonitoringRepository monitoring,
                                  ReviewCycleRepository cycles) {
        this.authorization = authorization;
        this.monitoring = monitoring;
        this.cycles = cycles;
    }

    /**
     * Every department this caller may monitor, for one cycle.
     *
     * <p>A caller with no granted departments gets an empty report rather than a 403, which is
     * the same shape the scoped review list takes for someone who may see nothing. Nothing is
     * disclosed either way, and the two endpoints answering the same question differently would
     * be its own kind of confusion. The targeted variant below is the one that refuses, because
     * there the caller has named a department and is entitled to a straight answer about it.
     */
    @Transactional(readOnly = true)
    public CycleReport report(Long cycleId) {
        SubjectScope scope = authorization.subjectScopeFor(Capability.MONITOR_CYCLE);
        return build(cycleId, scope.hrDepartmentIds(), scope.callerId());
    }

    /**
     * One named department (P-6.3).
     *
     * <p>403 when it is not granted, or when it is the caller's own and no explicit grant lifts
     * the block. The decision is the ordinary one - {@link AuthorizationService} answers it, and
     * this method does not re-derive it from the department set.
     */
    @Transactional(readOnly = true)
    public CycleReport report(Long cycleId, Long departmentId) {
        authorization.requireForDepartment(Capability.MONITOR_CYCLE, departmentId);
        SubjectScope scope = authorization.subjectScopeFor(Capability.MONITOR_CYCLE);
        return build(cycleId, Set.of(departmentId), scope.callerId());
    }

    private CycleReport build(Long cycleId, Set<Long> departmentIds, Long callerId) {
        ReviewCycle cycle = cycles.findById(cycleId)
                .orElseThrow(() -> new NotFoundApiException("No such cycle"));

        if (departmentIds.isEmpty()) {
            // No query at all. An IN clause over an empty set is a SQL error in some dialects
            // and a full table scan in others, and neither is a good way to express "nothing".
            return new CycleReport(cycle, List.of());
        }

        Map<Long, Long> assignments = totals(
                monitoring.peerAssignmentsByDepartment(cycleId, departmentIds, callerId));
        Map<Long, Long> peerReviews = totals(
                monitoring.peerReviewsByDepartment(cycleId, departmentIds, callerId));

        List<DepartmentReport> departments =
                monitoring.progressByDepartment(cycleId, departmentIds, callerId).stream()
                        .map(row -> new DepartmentReport(
                                row.getDepartmentId(),
                                row.getDepartmentName(),
                                row.getParticipants(),
                                row.getSelfReviewsSubmitted(),
                                row.getManagerReviewsSubmitted(),
                                assignments.getOrDefault(row.getDepartmentId(), 0L),
                                peerReviews.getOrDefault(row.getDepartmentId(), 0L),
                                row.getRatingsSet(),
                                row.getRatingsReleased()))
                        .toList();

        return new CycleReport(cycle, departments);
    }

    /**
     * Merges the peer totals onto the main rows.
     *
     * <p>This is not the filtering-in-Java the constraints forbid. Every one of the three
     * queries was scoped in its own {@code WHERE} clause and returned only permitted
     * departments; what happens here is stitching three permitted results together, and no row
     * can enter the report that a query did not already allow.
     */
    private static Map<Long, Long> totals(List<CycleMonitoringRepository.DepartmentTotal> rows) {
        Map<Long, Long> byDepartment = new HashMap<>();
        for (CycleMonitoringRepository.DepartmentTotal row : rows) {
            byDepartment.put(row.getDepartmentId(), row.getTotal());
        }
        return byDepartment;
    }

    /**
     * @param departments empty when the caller holds no grants, or when the cycle has not
     *                    opened and therefore has no participants yet - which is exactly the
     *                    signal HR need to see that a cycle failed to fire
     */
    public record CycleReport(ReviewCycle cycle, List<DepartmentReport> departments) {
    }

    /**
     * One department's progress. Counts only - no names, no ratings, no feedback.
     *
     * @param participants how many people in this department are under review in this cycle,
     *                     excluding the caller (P-2.2)
     */
    public record DepartmentReport(
            Long departmentId,
            String departmentName,
            long participants,
            long selfReviewsSubmitted,
            long managerReviewsSubmitted,
            long peerAssignmentsMade,
            long peerReviewsSubmitted,
            long ratingsSet,
            long ratingsReleased) {
    }
}
