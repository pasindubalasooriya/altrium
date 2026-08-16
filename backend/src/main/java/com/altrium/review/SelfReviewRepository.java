package com.altrium.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Specification-only by design: a self-review is always read through the caller's scope
 * (P-0.3), never fetched by cycle and filtered afterwards.
 */
public interface SelfReviewRepository
        extends JpaRepository<SelfReview, Long>, JpaSpecificationExecutor<SelfReview> {
}
