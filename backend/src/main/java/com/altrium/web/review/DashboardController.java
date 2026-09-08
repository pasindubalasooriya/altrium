package com.altrium.web.review;

import com.altrium.review.DashboardService;
import com.altrium.review.Rating;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Feature 18 - the manager's and HR's dashboards (scenario section 13).
 *
 * <p>No {@code @PreAuthorize}, as everywhere else. There is no role gate on this endpoint at
 * all, and it does not need one: the scope decides what the totals count, and a caller with no
 * grounds gets zeroes rather than a denial.
 *
 * <p><strong>Nothing in this response names a person</strong>, which is the same protection
 * P-7.4 gives the Leadership metrics: there is no id, no name and no handle here, so a client
 * has nothing to build a drill-down link from. The manager and HR both have real drill-down
 * screens - the review list and the calibration screen - and those are reached by their own
 * endpoints, which check on their own.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboards;

    public DashboardController(DashboardService dashboards) {
        this.dashboards = dashboards;
    }

    /**
     * A cycle's totals for whoever is asking.
     *
     * @param scopeIsEmpty true when the caller has no grounds over anybody in this cycle, so
     *   the zeroes below mean "nothing to show you" rather than "nobody has done anything".
     *   Reported rather than left to be inferred: a manager whose whole team has yet to start
     *   and an employee who has no dashboard at all would otherwise send identical responses,
     *   and only one of them should produce an empty screen with an explanation.
     * @param ratingDistribution ratings that were actually given, keyed by value. A rating
     *   nobody received is <strong>absent rather than zero</strong>, so the response does not
     *   decide how many bars a chart has.
     */
    public record DashboardView(Long cycleId,
                                String cycleLabel,
                                String cycleStatus,
                                boolean scopeIsEmpty,
                                long participants,
                                long selfReviewsSubmitted,
                                long managerReviewsSubmitted,
                                long peerReviewsSubmitted,
                                long ratingsSet,
                                long ratingsReleased,
                                Map<String, Long> ratingDistribution) {
    }

    @GetMapping
    @Operation(summary = "Team or department totals for a cycle, scoped to the caller (section 13)")
    public DashboardView dashboard(@RequestParam Long cycleId) {
        return dashboards.forCycle(cycleId, (cycle, scopeIsEmpty, participants, selfReviews,
                                             managerReviews, peerReviews, ratingsSet,
                                             ratingsReleased, byRating) -> {
            Map<String, Long> distribution = new LinkedHashMap<>();
            for (Map.Entry<Rating, Long> entry : byRating.entrySet()) {
                distribution.put(entry.getKey().name(), entry.getValue());
            }

            return new DashboardView(
                    cycle.getId(),
                    cycle.label(),
                    cycle.getStatus().name(),
                    scopeIsEmpty,
                    participants,
                    selfReviews,
                    managerReviews,
                    peerReviews,
                    ratingsSet,
                    ratingsReleased,
                    distribution);
        });
    }
}
