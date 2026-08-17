package com.altrium.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Specification-only by design: a self-review is always read through the caller's scope
 * (P-0.3), never fetched by cycle and filtered afterwards.
 */
public interface SelfReviewRepository
        extends JpaRepository<SelfReview, Long>, JpaSpecificationExecutor<SelfReview> {

    /**
     * The write path's lookup: the one self-review a subject has in a cycle, or none yet.
     *
     * <p>Safe to have as a plain finder in a way the peer equivalent is not, because it takes
     * the subject and returns only their own row. There is nothing here to discover.
     */
    java.util.Optional<SelfReview> findByCycleIdAndSubjectId(Long cycleId, Long subjectId);
}
