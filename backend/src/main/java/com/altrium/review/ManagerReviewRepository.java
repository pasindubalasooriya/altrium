package com.altrium.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ManagerReviewRepository
        extends JpaRepository<ManagerReview, Long>, JpaSpecificationExecutor<ManagerReview> {

    /** The write path's lookup, reached only after a {@code DIRECT_MANAGER} decision (P-3.7). */
    java.util.Optional<ManagerReview> findByCycleIdAndSubjectId(Long cycleId, Long subjectId);
}
