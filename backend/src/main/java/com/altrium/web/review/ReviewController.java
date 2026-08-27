package com.altrium.web.review;

import com.altrium.review.ReviewReadService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Feature 4 - reading permitted reviews.
 *
 * <p><strong>There is no {@code @PreAuthorize} on this class, and that is deliberate.</strong>
 * A role annotation can express "an HR user may call this"; it cannot express "an HR user
 * may call this for the departments they hold today, excluding their own unless explicitly
 * granted, and never for themselves". Putting a role gate here as well would produce two
 * places where access is decided, and the weaker one would eventually be the one somebody
 * relied on. Authorization lives entirely in {@code AuthorizationService}, reached through
 * {@link ReviewReadService}; the security filter chain has already established that the
 * caller is a provisioned, active employee.
 *
 * <p>Everything here is paged on the server. Nothing assumes the organisation is small
 * enough to return whole.
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewReadService reviews;

    public ReviewController(ReviewReadService reviews) {
        this.reviews = reviews;
    }

    /**
     * The reviews the caller may see in a cycle.
     *
     * <p>Scoped inside the SQL, so the {@code totalElements} of this page counts what the
     * caller may see - not what exists. A count computed before filtering would report the
     * size of the organisation to anybody who paged through it.
     */
    @GetMapping
    @Operation(summary = "Reviews the caller is permitted to see in a cycle, scoped in SQL")
    public Page<ReviewDtos.ReviewSummary> list(
            @RequestParam Long cycleId,
            @PageableDefault(size = 25, sort = "subject.fullName") Pageable pageable) {
        return reviews.listPermittedReviews(cycleId, pageable, ReviewDtos.ReviewSummary::of);
    }

    /**
     * One reviewee's record, containing exactly the sections the caller has grounds for.
     *
     * <p>Re-checked on the way out (P-0.4): an id lifted from another user's data is refused
     * here just as it is absent from the list above. The list and this endpoint must agree,
     * or the id becomes the way around the list.
     */
    @GetMapping("/{subjectId}")
    @Operation(summary = "One reviewee's record, assembled per section from the caller's grounds")
    public ReviewDtos.ReviewRecordView record(@PathVariable Long subjectId,
                                              @RequestParam Long cycleId) {
        // The mapper goes in, like the list above. Mapping out here touches the participant's
        // lazy cycle proxy on a closed session.
        return reviews.readRecord(cycleId, subjectId, ReviewDtos.ReviewRecordView::of);
    }

    /**
     * The cycles themselves - dates and status, no participants.
     *
     * <p>Unscoped because a cycle is not about anybody: its dates are the same fact for the
     * whole organisation, and the client needs them to ask any of the questions above.
     */
    @GetMapping("/cycles")
    @Operation(summary = "Review cycles: dates and status only, no participants")
    public List<ReviewDtos.CycleView> cycles() {
        return reviews.listCycles(ReviewDtos.CycleView::of);
    }
}
