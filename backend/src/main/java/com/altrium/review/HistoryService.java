package com.altrium.review;

import com.altrium.auth.AuthorizationService;
import com.altrium.auth.Capability;
import com.altrium.auth.Grounds;
import com.altrium.auth.CurrentUserService;
import com.altrium.auth.ReviewSubject;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Feature 16 - history and carry-over, the read half (scenario section 5 step 9).
 *
 * <p>One person's cycles, newest first, with the rating and the manager's words attached
 * where the caller is entitled to them. This is the fourth pillar in scenario section 2:
 * "each round starts from scratch; last cycle's plan and progress are gone."
 *
 * <h2>Almost none of carry-over is here</h2>
 *
 * <p>Worth stating plainly, because the feature name suggests more code than it needs. The
 * <em>carrying</em> was built in Sprint 1 and is structural, not a job that runs:
 *
 * <ul>
 *   <li>The development plan is not keyed to a cycle (P-5.9). One enduring row per employee,
 *       so its goals and progress cross a cycle boundary by never having been tied to one.
 *       Nothing copies them forward, which is why nothing can drop them.</li>
 *   <li>A passed improvement plan resumes the suspended development plan, and a failed one
 *       resumes it too (P-5.14), both inside {@code PlanService}.</li>
 *   <li>Ratings already carry a cycle, so past ones were never overwritten - there was simply
 *       no endpoint that returned more than one.</li>
 * </ul>
 *
 * <p>So this class adds the missing read and nothing else. There is no migration and no
 * copying step, and that absence is the design: a carry-forward job would be a second place
 * for the plan to live and a chance, once a quadrimester, to lose it.
 *
 * <h2>The subject sees every cycle, and released outcomes only</h2>
 *
 * <p>Every cycle the person took part in appears, for every caller. What narrows is what each
 * cycle <em>carries</em>: for the employee an unreleased rating is a decision they have not been
 * given (P-4.4), so the cycle comes back with no rating and no feedback - byte for byte the
 * response a cycle where nothing had been written yet would produce. Hiding the cycle itself
 * would add no protection, since the employee wrote a self-review in it, and would make their
 * own timeline misreport their history.
 *
 * <p>{@link AuthorizationService#require} returns the ground that justified the read, so that
 * narrowing is taken from the authorization decision rather than re-derived from role names or
 * from comparing ids. Where the ground is {@link Grounds#SELF} the release filter goes into the
 * {@code WHERE} clause, so a withheld row never reaches Java to be dropped.
 *
 * <p>{@code SELF} implies the caller is the subject and holds no other ground here, because
 * a reporting line cannot form a cycle (P-1.4) - nobody is their own manager, so nobody can
 * hold {@code DIRECT_MANAGER} over themselves. An HR user reading their own timeline arrives
 * as {@code SELF} too, since P-2.2 withholds HR grounds on their own record, and they are
 * then treated exactly as any employee.
 *
 * <p>Manager feedback is fetched for a set of cycle ids rather than fetched whole and
 * filtered, so for the subject the query for a withheld cycle never runs at all - the same
 * shape {@code ReviewReadService} uses for peer reviews, and for the same reason.
 */
@Service
public class HistoryService {

    private final AuthorizationService authorization;
    private final CurrentUserService currentUser;
    private final CycleParticipantRepository participants;
    private final FinalRatingRepository finalRatings;
    private final ManagerReviewRepository managerReviews;

    public HistoryService(AuthorizationService authorization,
                          CurrentUserService currentUser,
                          CycleParticipantRepository participants,
                          FinalRatingRepository finalRatings,
                          ManagerReviewRepository managerReviews) {
        this.authorization = authorization;
        this.currentUser = currentUser;
        this.participants = participants;
        this.finalRatings = finalRatings;
        this.managerReviews = managerReviews;
    }

    /** Maps one cycle of somebody's history. */
    @FunctionalInterface
    public interface TimelineMapper<T> {
        T map(CycleParticipant participant,
              Optional<FinalRating> rating,
              Optional<ManagerReview> managerReview);
    }

    /**
     * The caller's own history. Takes no id, so it cannot be pointed at anybody else.
     */
    @Transactional(readOnly = true)
    public <T> List<T> myTimeline(TimelineMapper<T> mapper) {
        return timelineFor(currentUser.require().id(), mapper);
    }

    /**
     * Somebody's history: every cycle they took part in, newest first.
     *
     * <p>Gated on {@code READ_REVIEW_SUMMARY}, the same roster capability the review list uses
     * (P-0.3). Its grounds are the union of the content capabilities behind it, so a caller
     * who may read nothing about this person does not learn that they have a history either.
     *
     * <p>Returns an empty list rather than a 404 for somebody who has never been in a cycle.
     * They have a history; nothing has happened in it yet. A 404 would separate "never
     * reviewed" from "not yours", which is the distinction P-0.5 exists to remove.
     */
    @Transactional(readOnly = true)
    public <T> List<T> timelineFor(Long subjectId, TimelineMapper<T> mapper) {
        ReviewSubject subject = authorization.subject(subjectId);

        // P-0.4, and the narrowing below is taken from what this returns rather than
        // recomputed. One decision, used twice.
        Grounds ground = authorization.require(Capability.READ_REVIEW_SUMMARY, subject);
        boolean releasedOnly = ground == Grounds.SELF;

        List<CycleParticipant> rows = participants.findAll(
                forSubject(subjectId),
                Sort.by(Sort.Direction.DESC, "cycle.financialYear")
                        .and(Sort.by(Sort.Direction.DESC, "cycle.quadrimesterNo")));

        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Long, FinalRating> ratingsByCycle = ratings(subjectId, releasedOnly);

        // For the subject, the manager's words travel with the rating and are withheld with it
        // (P-3.7 applies the P-4.4 gate to the words as well as the number). Passing only the
        // released cycle ids is what keeps that in SQL: there is no unreleased row fetched and
        // then hidden, so nothing to leak through a count or an ordering.
        Set<Long> feedbackCycles = releasedOnly
                ? ratingsByCycle.keySet()
                : rows.stream().map(r -> r.getCycle().getId()).collect(Collectors.toSet());

        Map<Long, ManagerReview> feedbackByCycle = feedback(subjectId, feedbackCycles);

        return rows.stream()
                .map(row -> mapper.map(
                        row,
                        Optional.ofNullable(ratingsByCycle.get(row.getCycle().getId())),
                        Optional.ofNullable(feedbackByCycle.get(row.getCycle().getId()))))
                .toList();
    }

    private Map<Long, FinalRating> ratings(Long subjectId, boolean releasedOnly) {
        List<FinalRating> found = releasedOnly
                ? finalRatings.findBySubjectIdAndReleasedAtIsNotNull(subjectId)
                : finalRatings.findBySubjectId(subjectId);

        Map<Long, FinalRating> byCycle = new HashMap<>();
        for (FinalRating rating : found) {
            byCycle.put(rating.getCycle().getId(), rating);
        }
        return byCycle;
    }

    private Map<Long, ManagerReview> feedback(Long subjectId, Set<Long> cycleIds) {
        if (cycleIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, ManagerReview> byCycle = new HashMap<>();
        for (ManagerReview review : managerReviews.findBySubjectIdAndCycleIdIn(subjectId, cycleIds)) {
            byCycle.put(review.getCycle().getId(), review);
        }
        return byCycle;
    }

    /**
     * One person's participation rows.
     *
     * <p>A specification rather than a derived finder, because
     * {@link CycleParticipantRepository} deliberately exposes no way to read participants
     * without one. Reached only after the point decision above has permitted this subject.
     */
    private static Specification<CycleParticipant> forSubject(Long subjectId) {
        return (root, query, cb) -> cb.equal(root.get("subject").get("id"), subjectId);
    }
}
