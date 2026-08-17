package com.altrium.web.review;

import com.altrium.org.AppUser;
import com.altrium.review.ManagerReview;
import com.altrium.review.PeerAssignment;
import com.altrium.review.PeerReview;
import com.altrium.review.Rating;
import com.altrium.review.ReviewWriteService;
import com.altrium.review.SelfReview;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Features 7 to 11 - writing the four review streams.
 *
 * <p>No {@code @PreAuthorize}, for the same reason as the read side. "A manager may call this"
 * is not the rule; "this employee's manager may call this, for this employee" is, and only
 * {@code AuthorizationService} can say it.
 *
 * <p>One shape is worth pointing at. The self-review endpoint takes <strong>no subject
 * id</strong>. Written as {@code /reviews/{subjectId}/self-review} it would need a rule saying
 * the id has to be your own, and that rule could be got wrong; written this way there is no id
 * to get wrong. The API cannot express the mistake.
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewWriteController {

    private final ReviewWriteService reviews;

    public ReviewWriteController(ReviewWriteService reviews) {
        this.reviews = reviews;
    }

    // ---------------------------------------------------------------- wire shapes

    /**
     * No rating field. The subject does not rate themselves anywhere in the scenario, and the
     * table has no column for it, so there is nothing here to send.
     */
    public record SelfReviewRequest(String achievements, String challenges, String goals) {
    }

    public record PeerAssignmentRequest(@NotNull List<Long> peerIds) {
    }

    public record PeerReviewRequest(String feedback, Rating rating) {
    }

    public record ManagerReviewRequest(String feedback) {
    }

    public record SelfReviewWritten(
            Long cycleId, String achievements, String challenges, String goals,
            Instant submittedAt, boolean submitted) {

        static SelfReviewWritten of(SelfReview review) {
            return new SelfReviewWritten(
                    review.getCycle().getId(),
                    review.getAchievements(), review.getChallenges(), review.getGoals(),
                    review.getSubmittedAt(), review.isSubmitted());
        }
    }

    /** The manager's view of an assignment: it names the peer, because they chose them. */
    public record PeerAssignmentView(Long subjectId, String subjectName, Long peerId, String peerName) {

        static PeerAssignmentView of(PeerAssignment assignment) {
            return new PeerAssignmentView(
                    assignment.getSubject().getId(), assignment.getSubject().getFullName(),
                    assignment.getPeer().getId(), assignment.getPeer().getFullName());
        }
    }

    /**
     * The peer's own workload: whom they must review.
     *
     * <p>Deliberately does not name the other peer. A peer knowing who else was assigned to the
     * same subject would be one conversation away from the subject knowing it too (P-3.3).
     */
    public record PeerTaskView(Long subjectId, String subjectName, Long cycleId, boolean submitted) {
    }

    /**
     * A person the manager could pick as a peer.
     *
     * <p>Name and department only. This is a directory the manager can page through, so it
     * carries the minimum needed to tell two colleagues apart and nothing more - no email, no
     * reporting line, and nothing whatever about anybody's review.
     */
    public record PeerCandidateView(Long id, String fullName, String departmentName) {

        static PeerCandidateView of(AppUser user) {
            return new PeerCandidateView(
                    user.getId(),
                    user.getFullName(),
                    user.getDepartment() == null ? null : user.getDepartment().getName());
        }
    }

    public record PeerReviewWritten(Long subjectId, Rating rating, Instant submittedAt) {

        static PeerReviewWritten of(PeerReview review) {
            return new PeerReviewWritten(
                    review.getSubject().getId(), review.getRating(), review.getSubmittedAt());
        }
    }

    public record ManagerReviewWritten(
            Long subjectId, String feedback, Instant submittedAt, boolean submitted) {

        static ManagerReviewWritten of(ManagerReview review) {
            return new ManagerReviewWritten(
                    review.getSubject().getId(), review.getFeedback(),
                    review.getSubmittedAt(), review.isSubmitted());
        }
    }

    // ---------------------------------------------------------------- feature 7

    /**
     * Saves the caller's own self-review, as a draft unless {@code submit=true}.
     *
     * <p>409 once submitted: editing after the fact would let somebody rewrite what their
     * manager has already read and acted on.
     */
    @PutMapping("/self-review")
    @Operation(summary = "Write or submit your own self-review (P-3.1)")
    public SelfReviewWritten saveSelfReview(@RequestParam Long cycleId,
                                            @RequestParam(defaultValue = "false") boolean submit,
                                            @RequestBody SelfReviewRequest request) {
        return reviews.saveSelfReview(
                cycleId,
                new ReviewWriteService.SelfReviewInput(
                        request.achievements(), request.challenges(), request.goals()),
                submit,
                SelfReviewWritten::of);
    }

    // ---------------------------------------------------------------- feature 8

    /**
     * Nominates the subject's two peers (P-1.3, P-3.6). Replaces any existing pair.
     *
     * <p>Applied to a manager who is themselves a reviewee, the caller is their manager's
     * manager, which is scenario section 7 falling out of the same rule rather than needing
     * its own.
     */
    @PutMapping("/{subjectId}/peers")
    @Operation(summary = "Assign exactly two peer reviewers, as the subject's manager (P-3.6)")
    public List<PeerAssignmentView> assignPeers(@PathVariable Long subjectId,
                                                @RequestParam Long cycleId,
                                                @Valid @RequestBody PeerAssignmentRequest request) {
        return reviews.assignPeers(cycleId, subjectId, request.peerIds(), PeerAssignmentView::of);
    }

    /** Who is reviewing this person. 403 for the subject themselves, by the ordinary route. */
    @GetMapping("/{subjectId}/peers")
    @Operation(summary = "The peers assigned to a subject; only their manager may ask")
    public List<PeerAssignmentView> listPeers(@PathVariable Long subjectId,
                                              @RequestParam Long cycleId) {
        return reviews.listAssignedPeers(cycleId, subjectId, PeerAssignmentView::of);
    }

    /**
     * Who could be assigned as a peer for this subject.
     *
     * <p>Added for the manager console, which otherwise had no way to choose two people: the
     * only endpoint that lists users is the Super Admin's. It is guarded by
     * {@code ASSIGN_PEERS}, the same capability as the write, so the roster is reachable only
     * by the one person entitled to pick from it and only in the act of picking.
     *
     * <p>Paged and searchable rather than a full list, because peer assignment is
     * cross-department and the candidate set is therefore everybody active.
     */
    @GetMapping("/{subjectId}/peer-candidates")
    @Operation(summary = "People assignable as peers for this subject; the assigner's alone")
    public Page<PeerCandidateView> peerCandidates(
            @PathVariable Long subjectId,
            @RequestParam(required = false) String name,
            @PageableDefault(size = 10) Pageable pageable) {
        return reviews.peerCandidates(subjectId, name, pageable, PeerCandidateView::of);
    }

    /** Whom the caller must review. The safe direction of the assignment table. */
    @GetMapping("/my-peer-assignments")
    @Operation(summary = "The colleagues you have been asked to review this cycle")
    public List<PeerTaskView> myAssignments(@RequestParam Long cycleId) {
        return reviews.myPeerAssignments(cycleId).stream()
                .map(task -> new PeerTaskView(
                        task.subjectId(), task.subjectName(), task.cycleId(), task.submitted()))
                .toList();
    }

    // ---------------------------------------------------------------- features 9 and 10

    /**
     * Submits peer feedback. Once only (P-3.5) - a second attempt is 409, because the peer has
     * the permission and it is the record that refuses.
     */
    @PostMapping("/{subjectId}/peer-review")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit peer feedback, once, as an assigned peer (P-3.4, P-3.5)")
    public PeerReviewWritten submitPeerReview(@PathVariable Long subjectId,
                                              @RequestParam Long cycleId,
                                              @RequestBody PeerReviewRequest request) {
        return reviews.submitPeerReview(
                cycleId, subjectId, request.feedback(), request.rating(), PeerReviewWritten::of);
    }

    // ---------------------------------------------------------------- feature 11

    @PutMapping("/{subjectId}/manager-review")
    @Operation(summary = "Write or submit the manager review for a direct report (P-3.7)")
    public ManagerReviewWritten saveManagerReview(@PathVariable Long subjectId,
                                                  @RequestParam Long cycleId,
                                                  @RequestParam(defaultValue = "false") boolean submit,
                                                  @RequestBody ManagerReviewRequest request) {
        return reviews.saveManagerReview(
                cycleId, subjectId, request.feedback(), submit, ManagerReviewWritten::of);
    }
}
