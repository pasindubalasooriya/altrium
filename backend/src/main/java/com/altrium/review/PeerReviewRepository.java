package com.altrium.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * The most sensitive table in the system to read from: it carries both the feedback and the
 * name of whoever wrote it, and the subject may see neither (P-3.3).
 *
 * <p>There is deliberately no {@code findBySubjectId}. That method would read naturally at
 * every call site and would hand the subject their own peer reviews the first time one of
 * those call sites belonged to a subject-facing endpoint.
 */
public interface PeerReviewRepository
        extends JpaRepository<PeerReview, Long>, JpaSpecificationExecutor<PeerReview> {

    @Override
    @EntityGraph(attributePaths = {"subject", "peer", "cycle"})
    Page<PeerReview> findAll(Specification<PeerReview> spec, Pageable pageable);
}
