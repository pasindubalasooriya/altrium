package com.altrium.review;

import com.altrium.auth.ArtifactState;
import com.altrium.auth.AuthorizationService;
import com.altrium.auth.Capability;
import com.altrium.auth.CurrentUserService;
import com.altrium.auth.ReviewSubject;
import com.altrium.config.ConflictApiException;
import com.altrium.config.NotFoundApiException;
import com.altrium.config.ValidationApiException;
import com.altrium.org.AppUser;
import com.altrium.org.AppUserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Features 7 to 11 - the four review streams being written.
 *
 * <p>Every method here begins with a decision from {@link AuthorizationService} and none of
 * them makes its own. What varies between them is only which capability is asked about, which
 * is the payoff for having built the capability list first: "who may write a peer review?" is
 * answered in {@code Capability}, not scattered through this file.
 *
 * <h2>Two kinds of refusal, and the difference matters</h2>
 *
 * <p><strong>403</strong> means the caller has no business here at all - not this person's
 * manager, not an assigned peer, not the subject. That decision is never made in this class.
 *
 * <p><strong>409</strong> means the caller has exactly the permission they think they have, and
 * the <em>record</em> forbids the write: a peer review already submitted (P-3.5), a cycle not
 * open, a self-review already final. Answering 403 to those would tell somebody they lack a
 * permission they hold, and send them to an administrator to be given something they already
 * have. This follows the settled decision on duplicate peer submission, generalised: the
 * blanket 403 rule governs <em>access</em> denials, and these are not access denials.
 *
 * <h2>The cycle window</h2>
 *
 * <p>Every write requires an open cycle. That is deliberately a domain invariant here rather
 * than a state gate inside the authorization service: a closed cycle refuses everybody
 * identically, including the subject writing about themselves, so it says nothing about the
 * caller and belongs with the other facts about the record.
 */
@Service
@Transactional
public class ReviewWriteService {

    /** Scenario section 5: exactly two peers per subject per cycle (P-3.6). */
    private static final int REQUIRED_PEERS = 2;

    private final AuthorizationService authorization;
    private final CurrentUserService currentUser;
    private final AppUserRepository users;
    private final ReviewCycleRepository cycles;
    private final CycleParticipantRepository participants;
    private final SelfReviewRepository selfReviews;
    private final PeerAssignmentRepository assignments;
    private final PeerReviewRepository peerReviews;
    private final ManagerReviewRepository managerReviews;

    public ReviewWriteService(AuthorizationService authorization,
                              CurrentUserService currentUser,
                              AppUserRepository users,
                              ReviewCycleRepository cycles,
                              CycleParticipantRepository participants,
                              SelfReviewRepository selfReviews,
                              PeerAssignmentRepository assignments,
                              PeerReviewRepository peerReviews,
                              ManagerReviewRepository managerReviews) {
        this.authorization = authorization;
        this.currentUser = currentUser;
        this.users = users;
        this.cycles = cycles;
        this.participants = participants;
        this.selfReviews = selfReviews;
        this.assignments = assignments;
        this.peerReviews = peerReviews;
        this.managerReviews = managerReviews;
    }

    // ================================================================= feature 7: self-review

    /**
     * Writes the caller's own self-review (P-3.1).
     *
     * <p>Takes no subject id, and that is the enforcement rather than a convenience. An
     * endpoint shaped {@code PUT /reviews/{subjectId}/self-review} would need a rule saying the
     * id must be your own; this shape has no id to get wrong. {@code WRITE_SELF_REVIEW} carries
     * {@code SELF} as its only grounds, so the decision agrees, but the API cannot express the
     * mistake in the first place.
     *
     * <p>Saved as a draft until {@code submit} is true, after which it is final. Editing after
     * submission would let somebody rewrite what their manager has already read and acted on.
     */
    public <T> T saveSelfReview(Long cycleId, SelfReviewInput input, boolean submit,
                                Function<SelfReview, T> mapper) {

        Long callerId = currentUser.require().id();
        ReviewSubject subject = authorization.subject(callerId);
        authorization.require(Capability.WRITE_SELF_REVIEW, subject);

        ReviewCycle cycle = requireOpenCycle(cycleId);
        requireParticipant(cycle, subject.id(), "You are not under review in this cycle");

        SelfReview review = selfReviews.findByCycleIdAndSubjectId(cycleId, callerId)
                .orElseGet(() -> selfReviews.save(
                        new SelfReview(cycle, users.getReferenceById(callerId))));

        if (review.isSubmitted()) {
            throw new ConflictApiException(
                    "Your self-review for " + cycle.label() + " was submitted on "
                            + review.getSubmittedAt() + " and can no longer be changed");
        }

        review.setAchievements(input.achievements());
        review.setChallenges(input.challenges());
        review.setGoals(input.goals());

        if (submit) {
            requireSomethingWritten(input);
            review.setSubmittedAt(Instant.now());
        }
        return mapper.apply(review);
    }

    // ================================================================= feature 8: peer assignment

    /**
     * Nominates the subject's two peer reviewers (P-1.3, P-3.6).
     *
     * <p>One uniform rule: the peers are chosen by {@code mgr(S)}. Applied to a manager who is
     * themselves a reviewee, that is scenario section 7's "the manager's manager selects" - the
     * same rule one level up, not a second mechanism, and nothing here has to know which case
     * it is in.
     *
     * <p>Exactly two, enforced here rather than in the schema. A unique key can forbid a
     * duplicate pairing but cannot require a count, and a trigger would put the rule somewhere
     * {@code AuthorizationService} cannot see.
     */
    public <T> List<T> assignPeers(Long cycleId, Long subjectId, List<Long> peerIds,
                                   Function<PeerAssignment, T> mapper) {

        ReviewSubject subject = authorization.subject(subjectId);
        authorization.require(Capability.ASSIGN_PEERS, subject);

        ReviewCycle cycle = requireOpenCycle(cycleId);
        requireParticipant(cycle, subjectId, "That person is not under review in this cycle");

        Set<Long> distinct = new LinkedHashSet<>(peerIds == null ? List.of() : peerIds);
        if (distinct.size() != REQUIRED_PEERS) {
            throw new ValidationApiException(
                    "A subject has exactly " + REQUIRED_PEERS + " peer reviewers; "
                            + distinct.size() + " distinct people were supplied");
        }

        // Reassignment is allowed while nothing has been written and refused afterwards.
        // Replacing a peer who has already submitted would strand their feedback: it would sit
        // in the table unreadable through any endpoint, which is worse than either keeping it
        // or deleting it, because nobody would know it was there.
        if (peerReviews.existsByCycleIdAndSubjectIdAndSubmittedAtIsNotNull(cycleId, subjectId)) {
            throw new ConflictApiException(
                    "Peer feedback has already been submitted for this cycle; the peers are now fixed");
        }

        List<AppUser> peers = new ArrayList<>();
        for (Long peerId : distinct) {
            peers.add(validPeer(peerId, subject));
        }

        // Replaced wholesale rather than merged, so "these two" is what the manager sent and
        // not what the manager sent plus whatever was there before.
        assignments.deleteByCycleIdAndSubjectId(cycleId, subjectId);
        assignments.flush();

        AppUser assignedBy = users.getReferenceById(currentUser.require().id());
        AppUser reviewee = users.getReferenceById(subjectId);
        List<T> saved = new ArrayList<>();
        for (AppUser peer : peers) {
            saved.add(mapper.apply(assignments.save(
                    new PeerAssignment(cycle, reviewee, peer, assignedBy))));
        }
        return saved;
    }

    /** The subject's assigned peers, for the manager who chose them. Never for the subject. */
    @Transactional(readOnly = true)
    public <T> List<T> listAssignedPeers(Long cycleId, Long subjectId, Function<PeerAssignment, T> mapper) {
        ReviewSubject subject = authorization.subject(subjectId);

        // ASSIGN_PEERS, not a read capability, and that is the point: the only person entitled
        // to know who is reviewing S is the person who decided it. The subject asking about
        // themselves has no grounds here at all, so this is a 403 by the ordinary route rather
        // than by a special case about anonymity (P-3.3).
        authorization.require(Capability.ASSIGN_PEERS, subject);

        return assignments.findByCycleIdAndSubjectId(cycleId, subjectId).stream().map(mapper).toList();
    }

    /**
     * Who could be assigned as a peer for this subject (P-3.6).
     *
     * <p>Guarded by {@link Capability#ASSIGN_PEERS} - the same capability as the write it feeds,
     * so the only person who can see the roster through this route is the one person entitled to
     * choose from it. That matters: peer assignment is cross-department, so the candidate set is
     * everybody active, and a wider gate here would have handed the whole employee directory to
     * anybody who asked.
     *
     * <p>The exclusions live in the query, not in a filter applied afterwards, so the page count
     * describes the candidates rather than the organisation.
     */
    @Transactional(readOnly = true)
    public <T> Page<T> peerCandidates(Long subjectId, String name, Pageable pageable,
                                      Function<AppUser, T> mapper) {

        ReviewSubject subject = authorization.subject(subjectId);
        authorization.require(Capability.ASSIGN_PEERS, subject);

        String filter = (name == null || name.isBlank()) ? null : name.trim();
        return users.findPeerCandidates(subjectId, subject.managerId(), filter, pageable)
                .map(mapper);
    }

    /**
     * What this caller has been asked to review this cycle.
     *
     * <p>The safe direction of the assignment table: you may know whom you must review, never
     * who must review you. It needs no capability check because it is keyed on the caller - the
     * query cannot return anybody else's workload.
     */
    @Transactional(readOnly = true)
    public List<PeerTask> myPeerAssignments(Long cycleId) {
        Long callerId = currentUser.require().id();
        return assignments.findByCycleIdAndPeerId(cycleId, callerId).stream()
                .map(assignment -> new PeerTask(
                        assignment.getSubject().getId(),
                        assignment.getSubject().getFullName(),
                        cycleId,
                        // Whether this caller has written theirs. Keyed on them, so it says
                        // nothing about the other peer's progress, which is not theirs to know.
                        peerReviews.findByCycleIdAndSubjectIdAndPeerId(
                                cycleId, assignment.getSubject().getId(), callerId).isPresent()))
                .toList();
    }

    // ================================================================= features 9 and 10: peer review

    /**
     * Submits peer feedback (P-3.4, P-3.5).
     *
     * <p>Written once and final. The duplicate is a <strong>409</strong>: the peer holds the
     * permission and it is the existing record that forbids the second write. The unique key on
     * {@code (cycle, subject, peer)} is the real guarantee; this check exists to produce a
     * civil answer rather than a constraint violation.
     *
     * <p>Note there is no draft state, unlike the self-review and the manager review. A peer is
     * asked one question once, and a draft would be a half-written opinion sitting where the
     * manager could read it (P-3.2) before its author considered it finished.
     */
    public <T> T submitPeerReview(Long cycleId, Long subjectId, String feedback, Rating rating,
                                  Function<PeerReview, T> mapper) {

        Long callerId = currentUser.require().id();
        ReviewSubject subject = authorization.subject(subjectId);

        // The assignment table is read here and handed to the decision as state - this class
        // owns that table, and AuthorizationService owns what the fact means (P-3.4). A caller
        // who is not assigned holds no grounds and is refused 403, which is also why they
        // cannot use this endpoint to discover whether they were assigned.
        boolean assigned = assignments.existsByCycleIdAndSubjectIdAndPeerId(cycleId, subjectId, callerId);
        authorization.require(Capability.WRITE_PEER_REVIEW, subject,
                assigned ? ArtifactState.assignedPeer() : ArtifactState.none());

        ReviewCycle cycle = requireOpenCycle(cycleId);
        requireParticipant(cycle, subjectId, "That person is not under review in this cycle");

        peerReviews.findByCycleIdAndSubjectIdAndPeerId(cycleId, subjectId, callerId)
                .ifPresent(existing -> {
                    throw new ConflictApiException(
                            "You have already submitted peer feedback for this person in "
                                    + cycle.label());
                });

        if (feedback == null || feedback.isBlank()) {
            throw new ValidationApiException("Peer feedback cannot be empty");
        }

        PeerReview review = new PeerReview(cycle, users.getReferenceById(subjectId),
                users.getReferenceById(callerId));
        review.setFeedback(feedback);
        review.setRating(rating);
        review.setSubmittedAt(Instant.now());
        return mapper.apply(peerReviews.save(review));
    }

    // ================================================================= feature 11: manager review

    /**
     * Writes the manager's assessment of a direct report (P-3.7).
     *
     * <p>The author is recorded as the caller, not looked up from the subject's reporting line.
     * They are the same person today - {@code WRITE_MANAGER_REVIEW} has {@code DIRECT_MANAGER}
     * as its only grounds - and they will not be forever. Reporting lines move, and inferring
     * the author later would silently reattribute this quarter's review to whoever holds the
     * post next year.
     */
    public <T> T saveManagerReview(Long cycleId, Long subjectId, String feedback, boolean submit,
                                   Function<ManagerReview, T> mapper) {

        ReviewSubject subject = authorization.subject(subjectId);
        authorization.require(Capability.WRITE_MANAGER_REVIEW, subject);

        ReviewCycle cycle = requireOpenCycle(cycleId);
        requireParticipant(cycle, subjectId, "That person is not under review in this cycle");

        Long callerId = currentUser.require().id();
        ManagerReview review = managerReviews.findByCycleIdAndSubjectId(cycleId, subjectId)
                .orElseGet(() -> managerReviews.save(new ManagerReview(
                        cycle, users.getReferenceById(subjectId), users.getReferenceById(callerId))));

        if (review.isSubmitted()) {
            // Final once submitted, because the subject may already have read it (P-3.7) and a
            // silently edited assessment is not one they can respond to.
            throw new ConflictApiException(
                    "The manager review for " + cycle.label() + " was submitted on "
                            + review.getSubmittedAt() + " and can no longer be changed");
        }

        review.setFeedback(feedback);
        if (submit) {
            if (feedback == null || feedback.isBlank()) {
                throw new ValidationApiException("A manager review cannot be submitted empty");
            }
            review.setSubmittedAt(Instant.now());
        }
        return mapper.apply(review);
    }

    // ================================================================= internals

    /**
     * A candidate peer (P-3.6).
     *
     * <p>Three exclusions, one of which is a settled decision rather than a reading of the
     * scenario: {@code mgr(S)} is never their own report's peer, because they already write the
     * manager review and a second opinion from the same person is not a second opinion.
     * Cross-department peers are deliberately allowed - the scenario wants colleagues who have
     * actually worked with the subject, and those are not always in the same team.
     */
    private AppUser validPeer(Long peerId, ReviewSubject subject) {
        AppUser peer = users.findById(peerId)
                .orElseThrow(() -> new ValidationApiException("No such user: " + peerId));

        if (peerId.equals(subject.id())) {
            throw new ValidationApiException("Nobody is their own peer reviewer");
        }
        if (peerId.equals(subject.managerId())) {
            throw new ValidationApiException(
                    peer.getFullName() + " is this person's manager and already writes the"
                            + " manager review, so cannot also be a peer (P-3.6)");
        }
        if (!peer.isActive()) {
            // P-0.7: deactivated users are out of peer-selection lists. Assigning one would
            // guarantee feedback that never arrives.
            throw new ValidationApiException(
                    peer.getFullName() + " is deactivated and cannot be assigned as a peer");
        }
        return peer;
    }

    /**
     * The write window. 409 rather than 403, because it refuses everybody identically - the
     * subject included - and so says nothing about who is asking.
     */
    private ReviewCycle requireOpenCycle(Long cycleId) {
        ReviewCycle cycle = cycles.findById(cycleId)
                .orElseThrow(() -> new NotFoundApiException("No such cycle"));
        if (cycle.getStatus() != CycleStatus.OPEN) {
            throw new ConflictApiException(
                    cycle.label() + " is " + cycle.getStatus() + "; reviews can only be written"
                            + " while a cycle is open");
        }
        return cycle;
    }

    /**
     * Reached only after the caller has been permitted to act on this subject, so a 404 here
     * cannot be used to find out who is under review (P-0.5).
     */
    private void requireParticipant(ReviewCycle cycle, Long subjectId, String message) {
        if (!participants.existsByCycleIdAndSubjectId(cycle.getId(), subjectId)) {
            throw new NotFoundApiException(message);
        }
    }

    private static void requireSomethingWritten(SelfReviewInput input) {
        boolean empty = isBlank(input.achievements()) && isBlank(input.challenges()) && isBlank(input.goals());
        if (empty) {
            throw new ValidationApiException("A self-review cannot be submitted empty");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Carries no rating, because the self-review table has no rating column and never will. */
    public record SelfReviewInput(String achievements, String challenges, String goals) {
    }

    /**
     * One entry on a peer's to-do list.
     *
     * <p>Names the subject and nothing about the other peer. A peer who knew who else was
     * assigned to the same person would be one conversation away from the subject knowing it
     * too, and P-3.3 would then hold only by everyone's discretion.
     */
    public record PeerTask(Long subjectId, String subjectName, Long cycleId, boolean submitted) {
    }
}
