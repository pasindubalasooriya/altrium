package com.altrium.web.leadership;

import com.altrium.review.LeadershipMetricsService;
import com.altrium.review.Rating;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Leadership metrics (P-7.1) - the landing screen for the Leadership role.
 *
 * <p>One endpoint, totals only. {@code MeResponse} sends Leadership here after login, and this is
 * everything the role is given: how far the cycle has progressed by department, and how the
 * ratings fell across the organisation.
 *
 * <p><strong>Nothing in this response identifies a person</strong>, so no client can link from it
 * into a review, and no endpoint exists to link to. Scenario section 7 gives Leadership the shape
 * of performance across the organisation, not the people in it, and the way to keep that true is
 * for the drill-down never to exist rather than for it to exist and be refused.
 *
 * <p>Charts and the wider dashboard work are Sprint 2. This endpoint is here now so the policy is
 * enforced by something real rather than by an enum constant nothing implements.
 */
@RestController
@RequestMapping("/api/leadership")
public class LeadershipMetricsController {

    private final LeadershipMetricsService metrics;

    public LeadershipMetricsController(LeadershipMetricsService metrics) {
        this.metrics = metrics;
    }

    public record CycleStatusView(
            Long cycleId,
            String label,
            String status,
            LocalDate startDate,
            LocalDate endDate,
            Instant openedAt,
            Instant closedAt) {
    }

    /** One department's progress. Every field is a count. */
    public record DepartmentTotalsView(
            Long departmentId,
            String departmentName,
            long participants,
            long selfReviewsSubmitted,
            long managerReviewsSubmitted,
            long ratingsSet,
            long ratingsReleased) {
    }

    /**
     * @param ratingDistribution organisation-wide, keyed by the three scale values, zeroes
     *                           included. Never broken down by department: a department of one
     *                           would make the distribution an individual rating.
     */
    public record MetricsView(CycleStatusView cycle,
                              List<DepartmentTotalsView> departments,
                              Map<Rating, Long> ratingDistribution) {

        public static MetricsView of(LeadershipMetricsService.CycleMetrics metrics) {
            var cycle = metrics.cycle();
            return new MetricsView(
                    new CycleStatusView(
                            cycle.getId(),
                            cycle.label(),
                            cycle.getStatus().name(),
                            cycle.getStartDate(),
                            cycle.getEndDate(),
                            cycle.getOpenedAt(),
                            cycle.getClosedAt()),
                    metrics.departments().stream()
                            .map(d -> new DepartmentTotalsView(
                                    d.departmentId(),
                                    d.departmentName(),
                                    d.participants(),
                                    d.selfReviewsSubmitted(),
                                    d.managerReviewsSubmitted(),
                                    d.ratingsSet(),
                                    d.ratingsReleased()))
                            .toList(),
                    metrics.ratingDistribution());
        }
    }

    /**
     * No {@code @PreAuthorize}. The role gate lives in the capability's grounds like every other
     * decision in the system, so this endpoint is refused in the same place, and logged with the
     * same policy id, as everything else.
     */
    @GetMapping("/metrics")
    @Operation(summary = "Organisation-wide cycle totals for Leadership. Counts only, no drill-down")
    public MetricsView metrics(@RequestParam Long cycleId) {
        return MetricsView.of(metrics.forCycle(cycleId));
    }
}
