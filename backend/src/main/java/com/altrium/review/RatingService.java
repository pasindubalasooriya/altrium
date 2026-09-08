package com.altrium.review;

import com.altrium.auth.ArtifactState;
import com.altrium.auth.AuthorizationService;
import com.altrium.auth.Capability;
import com.altrium.auth.CurrentUserService;
import com.altrium.auth.ReviewSubject;
import com.altrium.config.ConflictApiException;
import com.altrium.config.NotFoundApiException;
import com.altrium.config.ValidationApiException;
import com.altrium.org.AppUserRepository;
import com.altrium.org.HrDepartmentGrantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * Features 12 to 14 - the final rating, its calibration, and the subject's view of it.
 *
 * <p>Three actors touch one row and each may do exactly one thing to it. The manager chooses
 * it, HR adjusts it, somebody releases it, and the subject reads it once released. Every one of
 * those is a separate capability, so the question "may this person change this rating?" has a
 * single answer that does not depend on what happened to the rating earlier.
 *
 * <h2>Chosen, never computed</h2>
 *
 * <p>There is no method here that reads the peer ratings, and none that averages anything.
 * P-4.1 says the manager chooses the rating using peer input plus discretion, and the moment a
 * suggested figure exists it becomes a default that has to be argued away. The absence is the
 * implementation.
 *
 * <h2>What each refusal means</h2>
 *
 * <p>403 is an access denial and is never decided in this class. 409 means the caller holds the
 * permission and the record's state forbids the write: a rating already calibrated cannot be
 * overwritten by the manager, and a rating already released cannot be quietly changed by
 * anybody.
 */
@Service
@Transactional
public class RatingService {

    private final AuthorizationService authorization;
    private final CurrentUserService currentUser;
    private final AppUserRepository users;
    private final ReviewCycleRepository cycles;
    private final CycleParticipantRepository participants;
    private final FinalRatingRepository ratings;
    private final RatingCalibrationRepository calibrations;
    private final ManagerReviewRepository managerReviews;
    private final PeerFeedbackGate peerFeedbackGate;
    private final HrDepartmentGrantRepository hrGrants;

    public RatingService(AuthorizationService authorization,
                         CurrentUserService currentUser,
                         AppUserRepository users,
                         ReviewCycleRepository cycles,
                         CycleParticipantRepository participants,
                         FinalRatingRepository ratings,
                         RatingCalibrationRepository calibrations,
                         ManagerReviewRepository managerReviews,
                         PeerFeedbackGate peerFeedbackGate,
                         HrDepartmentGrantRepository hrGrants) {
        this.authorization = authorization;
        this.currentUser = currentUser;
        this.users = users;
        this.cycles = cycles;
        this.participants = participants;
        this.ratings = ratings;
        this.calibrations = calibrations;
        this.managerReviews = managerReviews;
        this.peerFeedbackGate = peerFeedbackGate;
        this.hrGrants = hrGrants;
    }

    // ================================================================= feature 12: the manager chooses

    /**
     * Sets or changes the manager's rating for a direct report (P-4.1).
     *
     * <p><strong>Set once.</strong> Setting a rating is submitting it for HR sign-off - there
     * is no other act that hands it over - so the moment it exists it is with HR, and the
     * manager does not take it back. From there only HR move it, by calibrating (P-4.3), and
     * the trail records that they did.
     *
     * <p>This is stricter than it was. The rating used to stay editable until HR actually
     * calibrated, which left a window where HR were looking at a figure the manager could
     * change underneath them, and nothing recorded that it had moved. The audit trail is meant
     * to answer "who decided this and when"; a rating revised silently between submission and
     * sign-off is a decision the trail cannot see.
     *
     * <p>The two later refusals stay, and are reported separately because they mean different
     * things to the person asking:
     *
     * <ul>
     *   <li><b>HR has calibrated it.</b> Letting the manager set it back would make
     *       normalisation advisory, and would leave an audit row recording a change that no
     *       longer holds - worse than no audit at all, because it reads as authoritative.</li>
     *   <li><b>It has been released.</b> The employee has been told. Changing it silently
     *       afterwards is not a correction, it is a different conversation nobody has had.</li>
     * </ul>
     *
     * <p>All three are 409 and not 403: the manager holds {@code SET_FINAL_RATING} throughout,
     * and it is the state of the record that refuses.
     */
    public <T> T setRating(Long cycleId, Long subjectId, Rating rating, Function<FinalRating, T> mapper) {
        if (rating == null) {
            throw new ValidationApiException("A final rating must be one of the three values");
        }

        ReviewSubject subject = authorization.subject(subjectId);
        authorization.require(Capability.SET_FINAL_RATING, subject);

        ReviewCycle cycle = requireOpenCycle(cycleId);
        requireParticipant(cycle, subjectId);

        // Scenario section 5 step 4: the manager reads the peer ratings and then, using them
        // plus discretion, sets one final rating. Reading them is not something the system can
        // observe, so what it enforces instead is that they exist to be read. Placed before the
        // create-or-update branch rather than only on creation, so a manager cannot obtain the
        // first rating some other way and then revise it freely.
        peerFeedbackGate.requireBothPeersSubmitted(cycle, subjectId, "Setting a final rating");

        FinalRating existing = ratings.findByCycleIdAndSubjectId(cycleId, subjectId).orElse(null);
        if (existing == null) {
            return mapper.apply(ratings.save(new FinalRating(
                    cycle,
                    users.getReferenceById(subjectId),
                    rating,
                    users.getReferenceById(currentUser.require().id()))));
        }

        // Ordered from the furthest state back, so the message names the thing that actually
        // happened rather than the first gate it trips.
        if (existing.isReleased()) {
            throw new ConflictApiException(
                    "This rating was released on " + existing.getReleasedAt()
                            + " and can no longer be changed");
        }
        if (calibrations.existsByFinalRatingId(existing.getId())) {
            throw new ConflictApiException(
                    "HR has calibrated this rating; it is no longer the manager's to change (P-4.3)");
        }
        throw new ConflictApiException(
                "This rating was submitted for calibration on " + existing.getSetAt()
                        + " and is with HR. Only HR can change it now (P-4.1)");
    }

    // ================================================================= feature 13: HR calibrates

    /**
     * Adjusts a rating and records why, as an immutable row (P-4.3).
     *
     * <p>The before-value is the whole point of the audit table. Without it an adjusted rating
     * is indistinguishable from a manager who simply chose differently, and the normalisation
     * the client asked for becomes untraceable - the opposite of the accountability it exists
     * to provide.
     *
     * <p>HR-in-scope only, and never on their own rating: {@code CALIBRATE_RATING} carries
     * {@code HR_IN_SCOPE} as its only grounds, and P-2.2 withholds exactly that ground when the
     * caller is the subject. An HR Head with an explicit grant over their own department is
     * refused here by the ordering, not by a check written into this method.
     */
    public <T> T calibrate(Long cycleId, Long subjectId, Rating adjustedTo, String note,
                           Function<RatingCalibration, T> mapper) {
        if (adjustedTo == null) {
            throw new ValidationApiException("A calibrated rating must be one of the three values");
        }

        ReviewSubject subject = authorization.subject(subjectId);
        authorization.require(Capability.CALIBRATE_RATING, subject);

        requireOpenCycle(cycleId);
        FinalRating rating = requireRating(cycleId, subjectId);

        if (rating.isReleased()) {
            // The employee has already been told this number. Renormalising it behind them
            // would mean the rating they hold and the rating on file are different things.
            throw new ConflictApiException(
                    "This rating was released to the employee on " + rating.getReleasedAt()
                            + " and can no longer be calibrated");
        }

        Rating before = rating.getRating();
        if (before == adjustedTo) {
            // Still refused here, but no longer because agreement needs no record - under P-4.8
            // it very much does, since agreement is what unlocks the manager's release. It is a
            // separate act with its own endpoint, so the trail says which of the two happened
            // rather than leaving it to be inferred from two equal values.
            throw new ValidationApiException(
                    "That is already the rating. To sign it off unchanged, approve it instead");
        }

        rating.setRating(adjustedTo);
        RatingCalibration audit = new RatingCalibration(
                rating, before, adjustedTo,
                users.getReferenceById(currentUser.require().id()), note);

        // Appended in the same transaction as the change, so no rating can move without the
        // row that explains it, and no explanation can survive a change that rolled back.
        return mapper.apply(calibrations.save(audit));
    }

    /**
     * HR signs a rating off unchanged (P-4.8).
     *
     * <p>The counterpart of {@link #calibrate}: the same authority, exercised the other way. It
     * carries {@code CALIBRATE_RATING} rather than a capability of its own, because approving a
     * rating and adjusting one are the same act of HR judgement over the same record - and
     * because a second capability would be a second place for P-2.2 to be got wrong. An HR user
     * cannot sign off their own rating, and the ordering refuses it without this method saying
     * so.
     *
     * <p>Recorded as a calibration row whose before and after are equal. That is not a
     * workaround: the table's purpose is to say who touched this rating and when, and an
     * approval is exactly that. It also means the release gate has one question to ask - is
     * there a row - rather than two states to keep in step.
     */
    public <T> T approve(Long cycleId, Long subjectId, String note,
                         Function<RatingCalibration, T> mapper) {

        ReviewSubject subject = authorization.subject(subjectId);
        authorization.require(Capability.CALIBRATE_RATING, subject);

        requireOpenCycle(cycleId);
        FinalRating rating = requireRating(cycleId, subjectId);

        if (rating.isReleased()) {
            throw new ConflictApiException(
                    "This rating was released to the employee on " + rating.getReleasedAt()
                            + " and no longer needs signing off");
        }
        if (isSignedOff(rating)) {
            // A second approval is a 409 and not a silent success: the caller believes they are
            // doing something, and the first sign-off is already what unlocked the release.
            throw new ConflictApiException("This rating has already been signed off");
        }

        Rating current = rating.getRating();
        RatingCalibration audit = new RatingCalibration(
                rating, current, current,
                users.getReferenceById(currentUser.require().id()), note);
        return mapper.apply(calibrations.save(audit));
    }

    /** Whether HR have looked at this rating at all - by adjusting it, or by approving it. */
    private boolean isSignedOff(FinalRating rating) {
        return calibrations.existsByFinalRatingId(rating.getId());
    }

    /**
     * The calibration trail for one rating.
     *
     * <p>{@code READ_RATING_AUDIT} has no {@code SELF} grounds, so the subject cannot read this
     * about themselves. P-4.4 gives them their rating and their manager's feedback and nothing
     * else, and "your manager said Meets, HR moved it to Exceeds" is neither.
     */
    @Transactional(readOnly = true)
    public <T> List<T> calibrationHistory(Long cycleId, Long subjectId,
                                          Function<RatingCalibration, T> mapper) {
        ReviewSubject subject = authorization.subject(subjectId);
        authorization.require(Capability.READ_RATING_AUDIT, subject);

        FinalRating rating = requireRating(cycleId, subjectId);
        return calibrations.findByFinalRatingIdOrderByCalibratedAtAsc(rating.getId())
                .stream().map(mapper).toList();
    }

    // ================================================================= feature 14: release and read

    /**
     * Shares the rating with the employee, which is what opens the P-4.4 gate.
     *
     * <p>Idempotent by refusal rather than by silence: releasing twice is a 409, because the
     * second caller believes they are doing something and should be told they are not.
     */
    public <T> T release(Long cycleId, Long subjectId, Function<FinalRating, T> mapper) {
        ReviewSubject subject = authorization.subject(subjectId);
        authorization.require(Capability.RELEASE_RATING, subject);

        requireOpenCycle(cycleId);
        FinalRating rating = requireRating(cycleId, subjectId);

        if (rating.isReleased()) {
            throw new ConflictApiException("This rating was already released on " + rating.getReleasedAt());
        }

        // P-4.8: HR see the peer feedback, the manager's review and the number, and either
        // adjust it or approve it as set. Only then may it go to the employee. A 409 and not a
        // 403 - the manager holds RELEASE_RATING throughout, and it is the state of the
        // sign-off that refuses.
        if (!isSignedOff(rating) && hrCouldSignOff(subject)) {
            throw new ConflictApiException(
                    "This rating is waiting for HR sign-off. HR review the peer feedback and"
                            + " your review, then either calibrate the rating or approve it as"
                            + " set, and it can be shared after that");
        }

        rating.setReleasedAt(Instant.now());
        return mapper.apply(rating);
    }

    /**
     * What the employee is entitled to see: their rating and their manager's feedback (P-4.4).
     *
     * <p>Takes no subject id, like the self-review write. There is no id here to point at
     * somebody else, so the endpoint cannot be asked the question P-4.4 forbids.
     *
     * <p>The release gate is applied through {@link AuthorizationService}, not by an
     * {@code if} in this method. An unreleased rating therefore refuses the same way whether it
     * is reached here, through the record endpoint, or by any route added later.
     */
    @Transactional(readOnly = true)
    public OwnRating myRating(Long cycleId) {
        Long callerId = currentUser.require().id();
        ReviewSubject subject = authorization.subject(callerId);

        FinalRating rating = ratings.findByCycleIdAndSubjectId(cycleId, callerId).orElse(null);
        boolean readable = rating != null && authorization.decide(
                        Capability.READ_FINAL_RATING, subject,
                        ArtifactState.released(rating.isReleased()))
                .permitted();

        // The manager's feedback travels with it, because scenario section 5 step 6 shares the
        // two together and a rating with no explanation is the thing the client complained of.
        // It is gated on the same release: feedback arriving before the number would tell the
        // employee the outcome without telling them the outcome.
        String feedback = readable
                ? managerReviews.findByCycleIdAndSubjectId(cycleId, callerId)
                        .filter(ManagerReview::isSubmitted)
                        .map(ManagerReview::getFeedback)
                        .orElse(null)
                : null;

        return new OwnRating(
                cycleId,
                readable ? rating.getRating() : null,
                readable ? rating.getReleasedAt() : null,
                feedback,
                readable);
    }

    // ================================================================= internals

    private FinalRating requireRating(Long cycleId, Long subjectId) {
        return ratings.findByCycleIdAndSubjectId(cycleId, subjectId)
                .orElseThrow(() -> new NotFoundApiException(
                        "No rating has been set for this person in this cycle"));
    }

    private ReviewCycle requireOpenCycle(Long cycleId) {
        ReviewCycle cycle = cycles.findById(cycleId)
                .orElseThrow(() -> new NotFoundApiException("No such cycle"));
        if (cycle.getStatus() != CycleStatus.OPEN) {
            throw new ConflictApiException(
                    cycle.label() + " is " + cycle.getStatus()
                            + "; ratings can only be set, calibrated or released while a cycle is open");
        }
        return cycle;
    }

    private void requireParticipant(ReviewCycle cycle, Long subjectId) {
        if (!participants.existsByCycleIdAndSubjectId(cycle.getId(), subjectId)) {
            throw new NotFoundApiException("That person is not under review in this cycle");
        }
    }

    /**
     * The employee's own view.
     *
     * <p>"No rating exists yet" and "a rating exists and has not been shared" produce exactly
     * the same response, and that is the point rather than an oversight. Distinguishing them
     * would tell the employee a rating had been decided and was being withheld, which is the
     * fact P-4.4 exists to withhold. The {@code released} flag says whether there is anything
     * to read, never whether there is anything to know.
     */
    /**
     * Whether anybody could sign this rating off, which is what decides if the gate applies.
     *
     * <p>A gate nobody can open is not a gate, it is a stuck record. Where no HR user has
     * authority over the subject's department the manager releases alone, because the
     * alternative is a rating that can never reach the employee it belongs to.
     *
     * <p><b>The cost of that, stated:</b> revoking every grant over a department switches the
     * requirement off for it rather than blocking releases. That is the Product Owner's call,
     * taken on the ground that a permanently unreleasable rating is the worse failure. The
     * grants are the Super Admin's to manage and they already decide who HR are.
     *
     * <p>Resolved per request like every other grant question (constraint 4), never cached.
     */
    private boolean hrCouldSignOff(ReviewSubject subject) {
        return subject.departmentId() != null
                && hrGrants.anyHrCovers(subject.departmentId(), subject.id());
    }

    public record OwnRating(Long cycleId, Rating rating, Instant releasedAt,
                            String managerFeedback, boolean released) {
    }
}
