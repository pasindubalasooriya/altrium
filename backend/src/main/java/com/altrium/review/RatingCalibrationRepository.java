package com.altrium.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Append-only (P-4.3). Reads are always in the context of a rating the caller has already
 * been authorized for, so the audit trail carries no scope of its own.
 */
public interface RatingCalibrationRepository extends JpaRepository<RatingCalibration, Long> {

    List<RatingCalibration> findByFinalRatingIdOrderByCalibratedAtAsc(Long finalRatingId);

    /**
     * Whether HR has adjusted this rating.
     *
     * <p>Gates the manager's ability to change their own figure afterwards. Without it,
     * calibration would be advisory: HR normalises upward, the manager sets it back, and the
     * audit row records a change that no longer holds.
     */
    boolean existsByFinalRatingId(Long finalRatingId);

    /** Which of these ratings have been signed off, in one query rather than one each. */
    @Query("select distinct c.finalRating.id from RatingCalibration c"
            + " where c.finalRating.id in :ratingIds")
    List<Long> signedOffRatingIds(@Param("ratingIds") java.util.Collection<Long> ratingIds);
}
