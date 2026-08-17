package com.altrium.review;

import com.altrium.auth.AuthorizationService;
import com.altrium.auth.Capability;
import com.altrium.config.NotFoundApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Leadership metrics - totals for a cycle, and nothing that identifies anybody (P-7.1).
 *
 * <p>{@link Capability#READ_AGGREGATE_METRICS} is a {@code GLOBAL} capability whose only ground
 * is {@link com.altrium.auth.Grounds#LEADERSHIP}, so the decision is a single call and there is
 * no subject or department to supply. A manager, an HR user and the Super Admin are all refused
 * here, whatever else they can read elsewhere.
 *
 * <p><strong>There is deliberately no drill-down.</strong> No id of any person appears in what
 * this returns, so there is nothing for a client to link to and no endpoint to link it to. That
 * is P-7.1 enforced by the shape of the response rather than by a link somebody remembered not to
 * render - Leadership are given the state of the cycle, not a way into it.
 */
@Service
public class LeadershipMetricsService {

    private final AuthorizationService authorization;
    private final LeadershipMetricsRepository metrics;
    private final ReviewCycleRepository cycles;

    public LeadershipMetricsService(AuthorizationService authorization,
                                    LeadershipMetricsRepository metrics,
                                    ReviewCycleRepository cycles) {
        this.authorization = authorization;
        this.metrics = metrics;
        this.cycles = cycles;
    }

    @Transactional(readOnly = true)
    public CycleMetrics forCycle(Long cycleId) {
        authorization.requireGlobal(Capability.READ_AGGREGATE_METRICS);

        ReviewCycle cycle = cycles.findById(cycleId)
                .orElseThrow(() -> new NotFoundApiException("No such cycle"));

        List<DepartmentTotals> departments = metrics.progressByDepartment(cycleId).stream()
                .map(row -> new DepartmentTotals(
                        row.getDepartmentId(),
                        row.getDepartmentName(),
                        row.getParticipants(),
                        row.getSelfReviewsSubmitted(),
                        row.getManagerReviewsSubmitted(),
                        row.getRatingsSet(),
                        row.getRatingsReleased()))
                .toList();

        return new CycleMetrics(cycle, departments, distribution(cycleId));
    }

    /**
     * Every rating value appears, including the ones nobody received.
     *
     * <p>A distribution that omitted its zeroes would leave the client to decide whether a
     * missing value meant "none" or "not permitted to know", and a chart would silently change
     * shape between cycles. Both are the client guessing where it should be told.
     */
    private Map<Rating, Long> distribution(Long cycleId) {
        Map<Rating, Long> byRating = new EnumMap<>(Rating.class);
        for (Rating rating : Rating.values()) {
            byRating.put(rating, 0L);
        }
        for (LeadershipMetricsRepository.RatingTotal row : metrics.ratingDistribution(cycleId)) {
            byRating.put(row.getRating(), row.getTotal());
        }
        return byRating;
    }

    /**
     * @param departments empty when the cycle has not opened and so has no participants, which is
     *                    the same signal HR monitoring gives: the cycle did not fire
     * @param ratingDistribution organisation-wide only - see the repository for why it is never
     *                           broken down by department
     */
    public record CycleMetrics(ReviewCycle cycle,
                               List<DepartmentTotals> departments,
                               Map<Rating, Long> ratingDistribution) {
    }

    /** One department's progress. Counts only - no names, no ratings, no feedback. */
    public record DepartmentTotals(
            Long departmentId,
            String departmentName,
            long participants,
            long selfReviewsSubmitted,
            long managerReviewsSubmitted,
            long ratingsSet,
            long ratingsReleased) {
    }
}
