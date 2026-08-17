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

    /**
     * The writing peer's own row, if they have already written one (P-3.5).
     *
     * <p>Keyed on the peer as well as the subject, so it answers only "have <em>I</em> written
     * about this person?". It cannot be turned into "who has written about this person?", which
     * is the question the subject must never be able to ask.
     */
    java.util.Optional<PeerReview> findByCycleIdAndSubjectIdAndPeerId(Long cycleId, Long subjectId, Long peerId);

    /**
     * Whether any peer has submitted about this subject yet.
     *
     * <p><strong>Only ever called behind an {@code ASSIGN_PEERS} decision</strong>, which is
     * {@code mgr(S)} alone. It is a genuine hazard otherwise: a true here tells the caller
     * somebody has written about them, which is the count P-3.3 forbids the subject to learn,
     * one bit at a time. It exists because reassigning peers after feedback has arrived would
     * strand that feedback, and the manager has to be told why.
     */
    boolean existsByCycleIdAndSubjectIdAndSubmittedAtIsNotNull(Long cycleId, Long subjectId);
}
