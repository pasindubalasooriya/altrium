package com.altrium.testsupport;

import com.altrium.org.AppUser;
import com.altrium.review.Cohort;
import com.altrium.review.CohortMember;
import com.altrium.review.CycleParticipant;
import com.altrium.review.CycleStatus;
import com.altrium.review.FinalRating;
import com.altrium.review.ManagerReview;
import com.altrium.review.PeerAssignment;
import com.altrium.review.PeerReview;
import com.altrium.review.Rating;
import com.altrium.review.ReviewCycle;
import com.altrium.review.SelfReview;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds review artifacts directly, bypassing the services that will eventually write them.
 *
 * <p>Deliberate: features 7 to 13 do not exist yet, and a read-access test must not wait on
 * them. It also keeps the denial tests honest - they assert what the <em>read</em> layer
 * permits, with no chance of passing because a write path happened to refuse first.
 */
@Component
public class ReviewFixture {

    private static final AtomicInteger QUADRIMESTER = new AtomicInteger();

    private final EntityManager em;

    public ReviewFixture(EntityManager em) {
        this.em = em;
    }

    /** An open cycle. Each call takes a distinct period, so cycles never collide. */
    public ReviewCycle openCycle() {
        int n = QUADRIMESTER.incrementAndGet();
        ReviewCycle cycle = new ReviewCycle(
                2000 + n, (n % 3) + 1,
                LocalDate.of(2000 + n, 1, 1),
                LocalDate.of(2000 + n, 4, 30));
        cycle.setStatus(CycleStatus.OPEN);
        cycle.setOpenedAt(Instant.now());
        em.persist(cycle);
        return cycle;
    }

    /**
     * A cycle that is configured but not open, dated so the sweep will find it due.
     *
     * <p>Takes the quadrimester explicitly, because a sweep test's whole point is that the
     * cycle's quadrimester and the cohort's agree. The financial year is still unique per call,
     * so cycles never collide on the period key.
     */
    public ReviewCycle configuredCycle(int quadrimesterNo, LocalDate startDate) {
        int n = QUADRIMESTER.incrementAndGet();
        ReviewCycle cycle = new ReviewCycle(
                2000 + n, quadrimesterNo, startDate, startDate.plusMonths(4));
        em.persist(cycle);
        return cycle;
    }

    /** A cohort attached to a quadrimester, or to none when {@code quadrimesterNo} is null. */
    public Cohort cohort(String name, Integer quadrimesterNo) {
        Cohort cohort = new Cohort(name + "-" + QUADRIMESTER.incrementAndGet(), quadrimesterNo);
        em.persist(cohort);
        return cohort;
    }

    public CohortMember member(Cohort cohort, AppUser user) {
        CohortMember member = new CohortMember(cohort, user);
        em.persist(member);
        return member;
    }

    /** Puts a person under review, snapshotting the department they were in at intake. */
    public CycleParticipant participant(ReviewCycle cycle, AppUser subject) {
        CycleParticipant participant = new CycleParticipant(cycle, subject, subject.getDepartment());
        em.persist(participant);
        return participant;
    }

    public SelfReview selfReview(ReviewCycle cycle, AppUser subject, String achievements) {
        SelfReview review = new SelfReview(cycle, subject);
        review.setAchievements(achievements);
        review.setSubmittedAt(Instant.now());
        em.persist(review);
        return review;
    }

    public ManagerReview managerReview(ReviewCycle cycle, AppUser subject, AppUser manager, String feedback) {
        ManagerReview review = new ManagerReview(cycle, subject, manager);
        review.setFeedback(feedback);
        review.setSubmittedAt(Instant.now());
        em.persist(review);
        return review;
    }

    public PeerAssignment assignPeer(ReviewCycle cycle, AppUser subject, AppUser peer, AppUser assignedBy) {
        PeerAssignment assignment = new PeerAssignment(cycle, subject, peer, assignedBy);
        em.persist(assignment);
        return assignment;
    }

    public PeerReview peerReview(ReviewCycle cycle, AppUser subject, AppUser peer, String feedback) {
        PeerReview review = new PeerReview(cycle, subject, peer);
        review.setFeedback(feedback);
        review.setRating(Rating.MEETS_EXPECTATIONS);
        review.setSubmittedAt(Instant.now());
        em.persist(review);
        return review;
    }

    /** A rating the subject may not see yet - the P-4.4 state gate's "before" case. */
    public FinalRating unreleasedRating(ReviewCycle cycle, AppUser subject, AppUser setBy, Rating rating) {
        FinalRating finalRating = new FinalRating(cycle, subject, rating, setBy);
        em.persist(finalRating);
        return finalRating;
    }

    public FinalRating releasedRating(ReviewCycle cycle, AppUser subject, AppUser setBy, Rating rating) {
        FinalRating finalRating = unreleasedRating(cycle, subject, setBy, rating);
        finalRating.setReleasedAt(Instant.now());
        return finalRating;
    }

    public void flush() {
        em.flush();
    }
}
