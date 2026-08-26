package com.altrium.review;

import com.altrium.config.ConflictApiException;
import org.springframework.stereotype.Component;

/**
 * The rule that both of the manager's closing acts wait for both peer reviews.
 *
 * <p>A manager may not submit their review of an employee, nor set that employee's final
 * rating, until both assigned peers have submitted. Drafting is untouched: the manager writes
 * and saves freely, and it is only the irreversible act of submitting that waits.
 *
 * <h2>This is a recorded deviation from scenario section 5</h2>
 *
 * <p>Section 5 step 2 lists the three feedback streams as parallel, and it is step 4, the final
 * rating, that reads the peer ratings. Read literally, only the rating depends on peer input.
 * The Product Owner has ruled that the manager review waits as well, so that the manager writes
 * their assessment having read the peer feedback rather than alongside it, and this class is
 * where that ruling lives rather than being spread across two services.
 *
 * <p>The ruling comes with an operating assumption on the record: <b>submitting a peer review is
 * mandatory and everyone submits before the deadline.</b> Where somebody does not, the manager
 * replaces the unresponsive peer through {@code PUT /api/reviews/{id}/peers}, which is why no
 * override, waiver or time-based escape exists here. There is deliberately nothing in this class
 * that lets anybody past the gate, because every such hatch would be a second way to obtain a
 * rating and the simplest reading of the rule would stop being true.
 *
 * <h2>Why 409 and not 403</h2>
 *
 * <p>The manager holds {@code WRITE_MANAGER_REVIEW} and {@code SET_FINAL_RATING} throughout. It
 * is the state of the peer stream that forbids the write, so this is a conflict, exactly like a
 * duplicate peer submission. Nothing in this class decides access, and it is called only after
 * {@link com.altrium.auth.AuthorizationService} has already allowed the caller through.
 *
 * <h2>Why the count is safe to read here</h2>
 *
 * <p>How many peers have written about a subject is a number the subject may never learn
 * (P-3.3), and {@link PeerReviewRepository} carries a standing warning about exactly this. Both
 * call sites sit behind a capability whose only grounds are {@code DIRECT_MANAGER}, so the
 * caller is never the subject. The count is never returned to anybody either: it reaches the
 * caller only as the refusal message on their own write, which they provoked and which names
 * their own report.
 */
@Component
public class PeerFeedbackGate {

    /**
     * Two, from scenario section 5 - the same two that {@code ASSIGN_PEERS} requires.
     *
     * <p>Named rather than written twice, so the gate cannot drift from the assignment rule and
     * leave a manager permanently short of a peer they were never allowed to assign.
     */
    public static final int REQUIRED_PEER_REVIEWS = 2;

    private final PeerReviewRepository peerReviews;

    public PeerFeedbackGate(PeerReviewRepository peerReviews) {
        this.peerReviews = peerReviews;
    }

    /**
     * Refuses with 409 until both peers have submitted.
     *
     * @param act what the caller was trying to do, so the message says which write was refused
     */
    public void requireBothPeersSubmitted(ReviewCycle cycle, Long subjectId, String act) {
        long submitted = peerReviews.countByCycleIdAndSubjectIdAndSubmittedAtIsNotNull(
                cycle.getId(), subjectId);

        if (submitted < REQUIRED_PEER_REVIEWS) {
            throw new ConflictApiException(
                    act + " waits for both peer reviews. " + submitted + " of "
                            + REQUIRED_PEER_REVIEWS + " have been submitted for " + cycle.label()
                            + ". If a peer is not going to submit, assign a different one.");
        }
    }
}
