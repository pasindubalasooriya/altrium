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

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Feature 4 - reading the reviews a caller is permitted to see.
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
 *       free - which it is not if the rows are filtered in Java afterwards (P-0.3).</li>
 *   <li><b>Single records</b> are re-checked by a point decision on the way out, so an id
 *       copied from somebody else's screen is refused rather than served (P-0.4).</li>
 * </ul>
 *
 * <p>The detail view is assembled <em>per section</em>, each behind its own decision, rather
 * than fetched whole and trimmed. The subject's own bundle is then not a redacted version of
 * the manager's - the peer section is simply never built for them, and the query behind it
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
    private final RatingCalibrationRepository calibrations;

    public ReviewReadService(AuthorizationService authorization,
                             CycleParticipantRepository participants,
                             SelfReviewRepository selfReviews,
                             PeerReviewRepository peerReviews,
                             ManagerReviewRepository managerReviews,
                             FinalRatingRepository finalRatings,
                             ReviewCycleRepository cycles,
                             RatingCalibrationRepository calibrations) {
        this.authorization = authorization;
        this.participants = participants;
        this.selfReviews = selfReviews;
        this.peerReviews = peerReviews;
        this.managerReviews = managerReviews;
        this.finalRatings = finalRatings;
        this.cycles = cycles;
        this.calibrations = calibrations;
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
    public <T> Page<T> listPermittedReviews(Long cycleId, boolean excludeSelf, Pageable pageable,
                                            Function<ReviewRow, T> mapper) {
        SubjectScope scope = authorization.subjectScopeFor(Capability.READ_REVIEW_SUMMARY);
        if (excludeSelf) {
            // Applied to the scope, so it lands in the WHERE clause with every other ground and
            // the count describes the page. Never a filter over the results (P-0.3).
            scope = scope.withoutSelf();
        }

        Specification<CycleParticipant> spec =
                SubjectScopeSpecification.<CycleParticipant>subjectsIn(scope, "subject")
                        .and(inCycle(cycleId));

        Page<CycleParticipant> page = participants.findAll(spec, pageable);
        Map<Long, Integer> peerCounts = peerCountsFor(cycleId, page.getContent());
        Map<Long, RatingStage> stages = ratingStagesFor(cycleId, page.getContent());

        return page.map(participant -> mapper.apply(new ReviewRow(
                participant,
                peerCounts.get(participant.getSubject().getId()),
                stages.get(participant.getSubject().getId()))));
    }

    /**
     * How many peers have submitted, for the rows on this page whose peer feedback the caller
     * may read - and for no others.
     *
     * <p>This is the count P-3.3 forbids the subject, and their own row is on this page: the
     * summary admits {@code SELF}, so somebody under review sees themselves in the list they
     * call. {@code READ_PEER_REVIEW} has no {@code SELF} ground, so the decision below withholds
     * their own row for the ordinary reason rather than by a special case about anonymity, and
     * the map simply has no entry for them.
     *
     * <p><b>Absent, not zero.</b> A zero would be the count, told to the one person who may not
     * have it. The DTO turns a missing entry into a null field and the row is omitted from the
     * JSON entirely.
     *
     * <p>One grouped query for the page rather than one per row, so the cost does not grow with
     * the department (constraint 6).
     */
    /**
     * How far each rating on this page has got, for the rows whose caller is entitled to know.
     *
     * <p>Gated on {@code READ_RATING_AUDIT}, which is `mgr(S)` and HR-in-scope and has no
     * {@code SELF} ground - so the subject's own row carries nothing (P-4.7). Without that, an
     * employee would learn from their own list that a rating had been set and was sitting with
     * HR, which is the disclosure release exists to control.
     *
     * <p>Two batched queries for the page, not two per row.
     */
    private Map<Long, RatingStage> ratingStagesFor(Long cycleId, List<CycleParticipant> rows) {
        List<Long> readable = rows.stream()
                .map(row -> row.getSubject().getId())
                .filter(subjectId -> mayRead(Capability.READ_RATING_AUDIT,
                        authorization.subject(subjectId)))
                .toList();

        if (readable.isEmpty()) {
            return Map.of();
        }

        List<FinalRating> found = finalRatings.findByCycleIdAndSubjectIdIn(cycleId, readable);
        Set<Long> signedOff = found.isEmpty()
                ? Set.of()
                : Set.copyOf(calibrations.signedOffRatingIds(
                        found.stream().map(FinalRating::getId).toList()));

        Map<Long, FinalRating> bySubject = new HashMap<>();
        found.forEach(rating -> bySubject.put(rating.getSubject().getId(), rating));

        Map<Long, RatingStage> stages = new HashMap<>();
        for (Long subjectId : readable) {
            FinalRating rating = bySubject.get(subjectId);
            stages.put(subjectId, RatingStage.of(
                    rating, rating != null && signedOff.contains(rating.getId())));
        }
        return stages;
    }

    private Map<Long, Integer> peerCountsFor(Long cycleId, List<CycleParticipant> rows) {
        List<Long> readable = rows.stream()
                .map(row -> row.getSubject().getId())
                .filter(subjectId -> mayRead(Capability.READ_PEER_REVIEW,
                        authorization.subject(subjectId)))
                .toList();

        if (readable.isEmpty()) {
            return Map.of();
        }

        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : peerReviews.countSubmittedForSubjects(cycleId, readable)) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    /**
     * One reviewee's record, containing exactly the sections this caller has grounds for.
     *
     * <p>Every section is decided separately. A subject reading their own record gets their
     * self-review and, once released, their rating and their manager's feedback - and the
     * peer query is not merely filtered but never issued.
     *
     * <p><b>The mapper is applied here, inside the transaction</b>, for the same reason
     * {@link #listPermittedReviews} takes one. The record carries entities, and several of
     * their associations are lazy - the participant's cycle among them. Converting in the
     * controller reads those proxies after the session has closed, which is a 500 rather than
     * a denial and is invisible to any test that runs inside a transaction of its own.
     */
    @Transactional(readOnly = true)
    public <T> T readRecord(Long cycleId, Long subjectId, Function<ReviewRecord, T> mapper) {
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

        // Each decision is kept, not just acted on. Whether a section is empty and whether the
        // caller was allowed to see it are two different facts, and the second one is what the
        // client needs in order to say "not submitted yet" rather than "not yours".
        Set<ReviewSection> grounds = EnumSet.noneOf(ReviewSection.class);

        boolean maySeeSelf = mayRead(Capability.READ_SELF_REVIEW, subject);
        Optional<SelfReview> self = maySeeSelf
                ? one(selfReviews, cycleId, subjectId)
                : Optional.empty();
        if (maySeeSelf) {
            grounds.add(ReviewSection.SELF_REVIEW);
        }

        // Fetched here rather than further down, because it is not only a section of its own:
        // the manager review's gate depends on it. Reading the row is not a decision - the
        // caller already passed the point check above - it is the state the decisions are made
        // against.
        Optional<FinalRating> ratingRow = one(finalRatings, cycleId, subjectId);
        ArtifactState release = ArtifactState.released(
                ratingRow.map(FinalRating::isReleased).orElse(false));

        // The manager's words are gated on the rating's release for the subject (P-4.4) and on
        // nothing at all for the manager and HR. The distinction is made in stateGate, which
        // constrains SELF only, so the same call serves every caller.
        boolean maySeeManagerReview = mayRead(Capability.READ_MANAGER_REVIEW, subject, release);
        Optional<ManagerReview> manager = maySeeManagerReview
                ? one(managerReviews, cycleId, subjectId)
                : Optional.empty();
        if (maySeeManagerReview) {
            grounds.add(ReviewSection.MANAGER_REVIEW);
        }

        // P-3.3. Not a filter over a fetched list, and not a redaction of the author's name:
        // when the caller has no grounds, the peer table is never queried at all. There is
        // no count to leak and no ordering to infer from.
        boolean maySeePeers = mayRead(Capability.READ_PEER_REVIEW, subject);
        List<PeerReview> peers = maySeePeers
                ? peerReviews.findAll(subjectInCycle(cycleId, subjectId))
                : List.of();
        if (maySeePeers) {
            grounds.add(ReviewSection.PEER_REVIEWS);
        }

        // The rating is the one section whose grounds are deliberately not reported, and the
        // asymmetry is the point. Its gate depends on the record's state, not only on the
        // caller: an unreleased rating is withheld from the subject who would otherwise be
        // entitled to it. Saying "you have grounds, there is nothing here" would therefore
        // separate "no rating set" from "rating set but not shared", which is exactly the
        // disclosure release exists to control (P-4.4). So it stays tied to what arrived.
        Optional<FinalRating> rating = ratingRow.filter(r -> authorization
                .decide(Capability.READ_FINAL_RATING, subject, release)
                .permitted());
        if (rating.isPresent()) {
            grounds.add(ReviewSection.FINAL_RATING);
        }

        // The manager needs this to know whether they may share yet; the subject never sees it.
        Boolean signedOff = rating.isPresent()
                && mayRead(Capability.READ_RATING_AUDIT, subject)
                ? calibrations.existsByFinalRatingId(rating.get().getId())
                : null;

        return mapper.apply(new ReviewRecord(
                participant, self, manager, peers, rating, grounds, signedOff));
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

    /** Which grounds, if any, this caller holds - used to decide whether to build a section. */
    private boolean mayRead(Capability capability, ReviewSubject subject) {
        return mayRead(capability, subject, ArtifactState.none());
    }

    /**
     * The same question where the artifact's own state is part of the answer.
     *
     * <p>The state gate constrains {@code SELF} only, so passing a release status here does not
     * narrow what the manager or HR may read - it decides whether the subject may.
     */
    private boolean mayRead(Capability capability, ReviewSubject subject, ArtifactState state) {
        return authorization.decide(capability, subject, state).permitted();
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
    /**
     * One row of a review list: the participant, and the peer-submission count where the caller
     * is entitled to it.
     *
     * @param peerReviewsSubmitted null when the caller has no grounds to read this subject's
     *                             peer feedback - which includes the subject themselves. Never
     *                             zero in that case: zero is the count (P-3.3)
     */
    public record ReviewRow(CycleParticipant participant,
                           Integer peerReviewsSubmitted,
                           RatingStage ratingStage) {
    }

    public record ReviewRecord(
            CycleParticipant participant,
            Optional<SelfReview> selfReview,
            Optional<ManagerReview> managerReview,
            List<PeerReview> peerReviews,
            Optional<FinalRating> finalRating,
            Set<ReviewSection> grounds,
            /**
             * Whether HR have signed the rating off (P-4.8), or null where the caller has no
             * grounds to know. Part of the calibration trail, which the subject never reads
             * (P-4.7) - so null, not false. A false is an answer.
             */
            Boolean ratingSignedOff) {
    }
}
