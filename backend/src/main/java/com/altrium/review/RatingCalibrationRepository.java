package com.altrium.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Append-only (P-4.3). Reads are always in the context of a rating the caller has already
 * been authorized for, so the audit trail carries no scope of its own.
 */
public interface RatingCalibrationRepository extends JpaRepository<RatingCalibration, Long> {

    List<RatingCalibration> findByFinalRatingIdOrderByCalibratedAtAsc(Long finalRatingId);
}
