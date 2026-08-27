package com.altrium.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

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

    /**
     * How many peers have submitted about this subject.
     *
     * <p>The same hazard as the method above, sharpened: this is the peer count itself, which
     * P-3.3 forbids the subject to learn by any route. It has exactly one caller,
     * {@link PeerFeedbackGate}, which is reached only behind {@code DIRECT_MANAGER} grounds, and
     * it must not acquire a second one that serves a subject-facing endpoint.
     */
    long countByCycleIdAndSubjectIdAndSubmittedAtIsNotNull(Long cycleId, Long subjectId);

    /**
     * The same count for several subjects at once, for a page of a review list.
     *
     * <p>The same hazard again, and now on an endpoint the subject themselves calls - their own
     * row is in that list, because {@code SELF} is a ground on the summary. So the caller of
     * this method must pass <b>only</b> the subject ids it has already established
     * {@code READ_PEER_REVIEW} grounds for, and must leave every other row null rather than
     * zero. A zero is the count, and P-3.3 forbids the subject the count.
     *
     * <p>Batched rather than counted per row because a page of an HR user's department is
     * whatever size that department is, and one query per row would make the endpoint cost a
     * function of the organisation.
     *
     * @return one row per subject that has at least one submission; a subject with none is
     *         absent from the result rather than present with a zero
     */
    @Query("select p.subject.id, count(p) from PeerReview p"
            + " where p.cycle.id = :cycleId and p.subject.id in :subjectIds"
            + " and p.submittedAt is not null group by p.subject.id")
    List<Object[]> countSubmittedForSubjects(@Param("cycleId") Long cycleId,
                                             @Param("subjectIds") Collection<Long> subjectIds);
}
