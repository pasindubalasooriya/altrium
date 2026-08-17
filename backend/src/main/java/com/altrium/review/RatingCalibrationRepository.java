package com.altrium.review;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
