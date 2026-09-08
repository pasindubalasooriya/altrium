package com.altrium.web.review;

import com.altrium.review.CycleParticipant;
import com.altrium.review.FinalRating;
import com.altrium.review.HistoryService;
import com.altrium.review.ManagerReview;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Feature 16 - history and carry-over (scenario section 5 step 9).
 *
 * <p>No {@code @PreAuthorize}, as everywhere else. "A manager may call this" is not the rule.
 *
 * <p>Two routes, and the split is the same one the self-review and plan endpoints make: the
 * {@code /me} route takes no id, so the API cannot express asking for somebody else's history,
 * and the id route is point-checked before anything is read.
 */
@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryService history;

    public HistoryController(HistoryService history) {
        this.history = history;
    }

    /**
     * One cycle in somebody's history.
     *
     * <p><strong>Absent, not blank.</strong> {@code rating} and {@code managerFeedback} are
     * null when the caller may not see them <em>and</em> when nothing was ever recorded, and
     * the two cases are deliberately indistinguishable. A response that separated "withheld"
     * from "never set" would disclose that a rating exists, which is exactly what release
     * controls (P-4.4). The same reasoning that keeps a withheld peer count absent rather than
     * zero (P-3.3).
     *
     * @param quadrimester which of the financial year's three periods this cycle covered
     * @param releasedAt   when the rating was shared with the employee; null wherever
     *                     {@code rating} is null, since the two travel together
     */
    public record TimelineEntry(Long cycleId,
                                int financialYear,
                                int quadrimester,
                                String cycleStatus,
                                LocalDate startDate,
                                LocalDate endDate,
                                String rating,
                                Instant releasedAt,
                                String managerFeedback) {

        static TimelineEntry of(CycleParticipant participant,
                                Optional<FinalRating> rating,
                                Optional<ManagerReview> managerReview) {
            var cycle = participant.getCycle();

            // Only a submitted manager review is history. A draft is the manager still
            // thinking, and it is already withheld from the subject by the release gate; not
            // showing an unsubmitted one to HR either keeps the timeline a record of what
            // happened rather than of what is being typed.
            String feedback = managerReview
                    .filter(ManagerReview::isSubmitted)
                    .map(ManagerReview::getFeedback)
                    .orElse(null);

            return new TimelineEntry(
                    cycle.getId(),
                    cycle.getFinancialYear(),
                    cycle.getQuadrimesterNo(),
                    cycle.getStatus().name(),
                    cycle.getStartDate(),
                    cycle.getEndDate(),
                    rating.map(r -> r.getRating().name()).orElse(null),
                    rating.map(FinalRating::getReleasedAt).orElse(null),
                    feedback);
        }
    }

    /**
     * The caller's own history, newest cycle first.
     *
     * <p>Every cycle they took part in, carrying released outcomes only, so it answers "how
     * have I done over time" with the results they have actually been given.
     */
    @GetMapping("/me")
    @Operation(summary = "Your own cycle history, released outcomes only (scenario section 5.9)")
    public List<TimelineEntry> myHistory() {
        return history.myTimeline(TimelineEntry::of);
    }

    /**
     * Somebody's history, for their manager or for HR-in-scope.
     *
     * <p>Returns an empty list rather than a 404 for somebody who has never been in a cycle:
     * they have a history, nothing has happened in it. A 404 would separate "never reviewed"
     * from "not yours" (P-0.5).
     */
    @GetMapping("/{userId}")
    @Operation(summary = "An employee's cycle history, for their manager or HR-in-scope")
    public List<TimelineEntry> history(@PathVariable Long userId) {
        return history.timelineFor(userId, TimelineEntry::of);
    }
}
