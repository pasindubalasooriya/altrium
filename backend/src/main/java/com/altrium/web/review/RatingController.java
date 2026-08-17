package com.altrium.web.review;

import com.altrium.review.FinalRating;
import com.altrium.review.Rating;
import com.altrium.review.RatingCalibration;
import com.altrium.review.RatingService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Features 12 to 14 - the final rating, calibration, and the employee's own view.
 *
 * <p>Four endpoints for one row, one per actor, which is the shape the policy has rather than a
 * decomposition for its own sake: the manager chooses, HR calibrates, somebody releases, the
 * employee reads. A single {@code PATCH /rating} taking whatever fields the caller sent would
 * have had to work out which of those four things was being attempted, and would have got it
 * wrong the first time somebody sent two.
 *
 * <p>Nothing here returns an average of the peer ratings, and no endpoint exists to ask for
 * one. The rating is chosen, never computed (P-4.1), and a suggested figure would become a
 * default that has to be argued away.
 */
@RestController
@RequestMapping("/api/reviews")
public class RatingController {

    private final RatingService ratings;

    public RatingController(RatingService ratings) {
        this.ratings = ratings;
    }

    // ---------------------------------------------------------------- wire shapes

    public record RatingRequest(@NotNull Rating rating) {
    }

    /** The note is the manager's explanation to future readers, not to the employee. */
    public record CalibrationRequest(@NotNull Rating rating, String note) {
    }

    public record RatingView(Long subjectId, Rating rating, Instant setAt, Instant releasedAt,
                             boolean released) {

        static RatingView of(FinalRating rating) {
            return new RatingView(
                    rating.getSubject().getId(), rating.getRating(),
                    rating.getSetAt(), rating.getReleasedAt(), rating.isReleased());
        }
    }

    /** Names who calibrated. An audit row that did not would answer none of the questions asked of it. */
    public record CalibrationView(Rating from, Rating to, String by, Instant at, String note) {

        static CalibrationView of(RatingCalibration calibration) {
            return new CalibrationView(
                    calibration.getRatingBefore(),
                    calibration.getRatingAfter(),
                    calibration.getCalibratedBy().getFullName(),
                    calibration.getCalibratedAt(),
                    calibration.getNote());
        }
    }

    /**
     * The employee's own view: the rating and the manager's feedback, together, and only once
     * released. Nothing else from the cycle appears in this shape, because nothing else is
     * theirs (P-4.4).
     */
    public record OwnRatingView(Long cycleId, Rating rating, Instant releasedAt,
                                String managerFeedback, boolean released) {
    }

    // ---------------------------------------------------------------- feature 12

    @PutMapping("/{subjectId}/rating")
    @Operation(summary = "Set the final rating for a direct report; chosen, never computed (P-4.1)")
    public RatingView setRating(@PathVariable Long subjectId,
                                @RequestParam Long cycleId,
                                @RequestBody RatingRequest request) {
        return ratings.setRating(cycleId, subjectId, request.rating(), RatingView::of);
    }

    // ---------------------------------------------------------------- feature 13

    /**
     * HR normalises the rating, recording what it was, what it became and who moved it.
     *
     * <p>403 for an HR user calibrating their own rating, however explicit their grant. That is
     * P-2.2 arriving through the ordinary evaluation order rather than a check written here.
     */
    @PutMapping("/{subjectId}/rating/calibration")
    @Operation(summary = "Calibrate a rating, appending an immutable audit row (P-4.3)")
    public CalibrationView calibrate(@PathVariable Long subjectId,
                                     @RequestParam Long cycleId,
                                     @RequestBody CalibrationRequest request) {
        return ratings.calibrate(
                cycleId, subjectId, request.rating(), request.note(), CalibrationView::of);
    }

    /** The trail. Readable by the manager and HR-in-scope, and never by the subject. */
    @GetMapping("/{subjectId}/rating/calibration")
    @Operation(summary = "The calibration history for a rating; no SELF grounds exist for it")
    public List<CalibrationView> calibrationHistory(@PathVariable Long subjectId,
                                                    @RequestParam Long cycleId) {
        return ratings.calibrationHistory(cycleId, subjectId, CalibrationView::of);
    }

    // ---------------------------------------------------------------- feature 14

    /** Opens the P-4.4 gate. Until this is called, the rating is not the employee's to read. */
    @PostMapping("/{subjectId}/rating/release")
    @Operation(summary = "Share the rating with the employee (P-4.4)")
    public RatingView release(@PathVariable Long subjectId, @RequestParam Long cycleId) {
        return ratings.release(cycleId, subjectId, RatingView::of);
    }

    /**
     * The employee's own rating and their manager's feedback.
     *
     * <p>Takes no subject id, like the self-review write: there is no id here to point at
     * somebody else, so the endpoint cannot be asked the question P-4.4 forbids.
     */
    @GetMapping("/my-rating")
    @Operation(summary = "Your own final rating and manager feedback, once released (P-4.4)")
    public OwnRatingView myRating(@RequestParam Long cycleId) {
        RatingService.OwnRating own = ratings.myRating(cycleId);
        return new OwnRatingView(
                own.cycleId(), own.rating(), own.releasedAt(),
                own.managerFeedback(), own.released());
    }
}
