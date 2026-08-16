package com.altrium.review;

import com.altrium.auth.ArtifactState;
import com.altrium.auth.AuthorizationService;
import com.altrium.auth.Capability;
import com.altrium.auth.ReviewSubject;
import com.altrium.auth.SubjectScope;
import com.altrium.auth.SubjectScopeSpecification;
import com.altrium.config.NotFoundApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Feature 4 — reading the reviews a caller is permitted to see.
 *
 * <p>This is the first feature to sit on {@link AuthorizationService}, and it is deliberately
 * read-only: getting the visibility rules right is a whole problem by itself, and mixing
 * writes into it would make it impossible to tell which of the two was wrong.
 *
 * <p>Two mechanisms, used for two different jobs, and both are necessary:
 *
 * <ul>
 *   <li><b>Lists</b> are scoped by {@link SubjectScope} pushed into the SQL. A page the
 *       caller may not see never leaves the database, so the pagination count is right for
 *       free — which it is not if the rows are filtered in Java afterwards (P-0.3).</li>
 *   <li><b>Single records</b> are re-checked by a point decision on the way out, so an id
 *       copied from somebody else's screen is refused rather than served (P-0.4).</li>
 * </ul>
 *
 * <p>The detail view is assembled <em>per section</em>, each behind its own decision, rather
 * than fetched whole and trimmed. The subject's own bundle is then not a redacted version of
 * the manager's — the peer section is simply never built for them, and the query behind it
 * never runs (P-3.3).
 */
@Service
public class ReviewReadService {

    private final AuthorizationService authorization;
    private final CycleParticipantRepository participants;
    private final SelfReviewRepository selfReviews;
    private final PeerReviewRepository peerReviews;
    private final ManagerReviewRepository managerReviews;
    private final FinalRatingRepository finalRatings;
    private final ReviewCycleRepository cycles;

    public ReviewReadService(AuthorizationService authorization,
                             CycleParticipantRepository participants,
                             SelfReviewRepository selfReviews,
                             PeerReviewRepository peerReviews,
                             ManagerReviewRepository managerReviews,
                             FinalRatingRepository finalRatings,
                             ReviewCycleRepository cycles) {
        this.authorization = authorization;
        this.participants = participants;
        this.selfReviews = selfReviews;
        this.peerReviews = peerReviews;
        this.managerReviews = managerReviews;
        this.finalRatings = finalRatings;
        this.cycles = cycles;
    }

    /**
     * The reviews this caller may see in a cycle, paged in the database.
     *
     * <p>The mapper runs inside this transaction. That is not a style preference: mapping in
     * the controller would leave the entities detached and their associations unloaded, and
     * the failure would be invisible in tests, which hold the session open for the whole
     * test method.
     */
    @Transactional(readOnly = true)
    public <T> Page<T> listPermittedReviews(Long cycleId, Pageable pageable,
                                            Function<CycleParticipant, T> mapper) {
        SubjectScope scope = authorization.subjectScopeFor(Capability.READ_REVIEW_SUMMARY);

        Specification<CycleParticipant> spec =
                SubjectScopeSpecification.<CycleParticipant>subjectsIn(scope, "subject")
                        .and(inCycle(cycleId));

        return participants.findAll(spec, pageable).map(mapper);
    }

    /**
     * One reviewee's record, containing exactly the sections this caller has grounds for.
     *
     * <p>Every section is decided separately. A subject reading their own record gets their
     * self-review and, once released, their rating and their manager's feedback — and the
     * peer query is not merely filtered but never issued.
     */
    @Transactional(readOnly = true)
    public ReviewRecord readRecord(Long cycleId, Long subjectId) {
        ReviewSubject subject = authorization.subject(subjectId);

        // P-0.4: the point decision, before anything is read. Denied here means denied
        // whether or not the record exists, so an id cannot be probed for existence.
        authorization.require(Capability.READ_REVIEW_SUMMARY, subject);

        CycleParticipant participant = participants
                .findByCycleIdAndSubjectId(cycleId, subjectId)
                .orElseThrow(() -> new NotFoundApiException(
                        "No review for this person in this cycle"));

        // Reaching a 404 is only possible for someone already permitted to see the record,
        // so it cannot be used to distinguish "no such review" from "not yours" (P-0.5).

        Optional<SelfReview> self = mayRead(Capability.READ_SELF_REVIEW, subject)
                ? one(selfReviews, cycleId, subjectId)
                : Optional.empty();

        Optional<ManagerReview> manager = mayRead(Capability.READ_MANAGER_REVIEW, subject)
                ? one(managerReviews, cycleId, subjectId)
                : Optional.empty();

        // P-3.3. Not a filter over a fetched list, and not a redaction of the author's name:
        // when the caller has no grounds, the peer table is never queried at all. There is
        // no count to leak and no ordering to infer from.
        List<PeerReview> peers = mayRead(Capability.READ_PEER_REVIEW, subject)
                ? peerReviews.findAll(subjectInCycle(cycleId, subjectId))
                : List.of();

        Optional<FinalRating> rating = readableRating(cycleId, subjectId, subject);

        return new ReviewRecord(participant, self, manager, peers, rating);
    }

    /**
     * The rating, subject to the release gate (P-4.4).
     *
     * <p>The gate has to be evaluated against the record's own state, so the rating is
     * fetched first and the decision made second. That ordering is safe only because the
     * caller has already passed the summary check above; it would be an access leak in a
     * method reachable without it.
     */
    private Optional<FinalRating> readableRating(Long cycleId, Long subjectId, ReviewSubject subject) {
        Optional<FinalRating> rating = one(finalRatings, cycleId, subjectId);
        if (rating.isEmpty()) {
            return Optional.empty();
        }
        boolean permitted = authorization.decide(
                        Capability.READ_FINAL_RATING, subject,
                        ArtifactState.released(rating.get().isReleased()))
                .permitted();
        return permitted ? rating : Optional.empty();
    }

    /** All cycles. A cycle's dates and status are not about anybody, so nothing is scoped. */
    @Transactional(readOnly = true)
    public <T> List<T> listCycles(Function<ReviewCycle, T> mapper) {
        return cycles.findAll().stream().map(mapper).toList();
    }

    @Transactional(readOnly = true)
    public ReviewCycle requireCycle(Long cycleId) {
        return cycles.findById(cycleId)
                .orElseThrow(() -> new NotFoundApiException("No such cycle"));
    }

    /** Which grounds, if any, this caller holds — used to decide whether to build a section. */
    private boolean mayRead(Capability capability, ReviewSubject subject) {
        return authorization.decide(capability, subject, ArtifactState.none()).permitted();
    }

    private static Specification<CycleParticipant> inCycle(Long cycleId) {
        return (root, query, cb) -> cb.equal(root.get("cycle").get("id"), cycleId);
    }

    /**
     * Restricts an artifact query to one subject in one cycle. Used only after a point
     * decision has already permitted that subject.
     */
    private static <T> Specification<T> subjectInCycle(Long cycleId, Long subjectId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("cycle").get("id"), cycleId),
                cb.equal(root.get("subject").get("id"), subjectId));
    }

    private static <T> Optional<T> one(org.springframework.data.jpa.repository.JpaSpecificationExecutor<T> repository,
                                       Long cycleId, Long subjectId) {
        return repository.findOne(subjectInCycle(cycleId, subjectId));
    }

    /**
     * What a caller is permitted to see about one reviewee. Absent sections are absent, not
     * null-filled or blanked: a response that says "peerReviews: []" to a subject would be
     * indistinguishable from one that says "nobody has written yet", and the second is
     * information the subject is not entitled to (P-3.3).
     */
    public record ReviewRecord(
            CycleParticipant participant,
            Optional<SelfReview> selfReview,
            Optional<ManagerReview> managerReview,
            List<PeerReview> peerReviews,
            Optional<FinalRating> finalRating) {
    }
}
