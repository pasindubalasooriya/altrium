package com.altrium.web.review;

import com.altrium.review.CycleParticipant;
import com.altrium.review.FinalRating;
import com.altrium.review.ManagerReview;
import com.altrium.review.PeerReview;
import com.altrium.review.Rating;
import com.altrium.review.RatingStage;
import com.altrium.review.ReviewCycle;
import com.altrium.review.ReviewReadService;
import com.altrium.review.SelfReview;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The wire shapes for reading reviews.
 *
 * <p>Separate records rather than serialised entities, for a reason specific to this domain:
 * a serialised entity exposes whatever fields it happens to have, so adding a column to
 * {@code peer_review} later would publish it to every caller of every endpoint that returns
 * one. Here, a field reaches a client only because somebody wrote it into a DTO.
 *
 * <p>Absent sections are {@code null} and are omitted from the JSON rather than sent empty.
 * Returning {@code "peerReviews": []} to a subject would tell them the peer table exists and
 * that nobody had written yet - which is itself something they are not entitled to know
 * (P-3.3).
 */
public final class ReviewDtos {

    private ReviewDtos() {
    }

    public record CycleView(
            Long id,
            String label,
            int financialYear,
            int quadrimesterNo,
            String status,
            LocalDate startDate,
            LocalDate endDate,
            Instant openedAt,
            Instant closedAt) {

        public static CycleView of(ReviewCycle cycle) {
            return new CycleView(
                    cycle.getId(),
                    cycle.label(),
                    cycle.getFinancialYear(),
                    cycle.getQuadrimesterNo(),
                    cycle.getStatus().name(),
                    cycle.getStartDate(),
                    cycle.getEndDate(),
                    cycle.getOpenedAt(),
                    cycle.getClosedAt());
        }
    }

    /**
     * One row of the review list. Carries progress, never content - a manager scanning their
     * team sees who has submitted, and opens the record to read anything at all.
     */
    public record ReviewSummary(
            Long subjectId,
            String subjectName,
            String departmentName,
            Long managerId,
            boolean subjectActive,
            Long cycleId,
            String cycleLabel,
            Integer peerReviewsSubmitted,
            RatingStage ratingStage) {

        /**
         * @param row carries the peer count only where the caller had grounds for it. Null here
         *            is omitted from the JSON entirely, which is what the subject's own row
         *            gets - a zero would be the count P-3.3 withholds from them
         */
        public static ReviewSummary of(ReviewReadService.ReviewRow row) {
            CycleParticipant participant = row.participant();
            var subject = participant.getSubject();
            return new ReviewSummary(
                    subject.getId(),
                    subject.getFullName(),
                    participant.getDepartment() == null ? null : participant.getDepartment().getName(),
                    subject.getManager() == null ? null : subject.getManager().getId(),
                    subject.isActive(),
                    participant.getCycle().getId(),
                    participant.getCycle().label(),
                    row.peerReviewsSubmitted(),
                    row.ratingStage());
        }

        /**
         * The same summary as the header of a single record, where the count has no business
         * being.
         *
         * <p>A record already carries the peer section itself when the caller has grounds for
         * it, so a count here would be a second route to the same number - and a second route
         * is a second thing to get wrong. The subject's own record is the case that matters:
         * their peer section is never built, and this must not quietly hand them its size.
         */
        public static ReviewSummary withoutPeerCount(CycleParticipant participant) {
            var subject = participant.getSubject();
            return new ReviewSummary(
                    subject.getId(),
                    subject.getFullName(),
                    participant.getDepartment() == null ? null : participant.getDepartment().getName(),
                    subject.getManager() == null ? null : subject.getManager().getId(),
                    subject.isActive(),
                    participant.getCycle().getId(),
                    participant.getCycle().label(),
                    null,
                    null);
        }
    }

    public record SelfReviewView(String achievements, String challenges, String goals, Instant submittedAt) {

        public static SelfReviewView of(SelfReview review) {
            return new SelfReviewView(
                    review.getAchievements(), review.getChallenges(),
                    review.getGoals(), review.getSubmittedAt());
        }
    }

    public record ManagerReviewView(Long managerId, String managerName, String feedback, Instant submittedAt) {

        public static ManagerReviewView of(ManagerReview review) {
            return new ManagerReviewView(
                    review.getManager().getId(), review.getManager().getFullName(),
                    review.getFeedback(), review.getSubmittedAt());
        }
    }

    /**
     * Peer feedback <strong>with its author named</strong>.
     *
     * <p>This DTO is only ever built for a caller who has already been granted
     * {@code READ_PEER_REVIEW}, which the subject can never hold. There is deliberately no
     * anonymised variant of this record: an anonymised DTO would be a second, subtly
     * different rule about peer visibility living outside the authorization layer, and the
     * day the two disagreed the wrong one would be the one in use.
     */
    public record PeerReviewView(
            Long peerId,
            String peerName,
            String feedback,
            Rating rating,
            Instant submittedAt) {

        public static PeerReviewView of(PeerReview review) {
            return new PeerReviewView(
                    review.getPeer().getId(), review.getPeer().getFullName(),
                    review.getFeedback(), review.getRating(), review.getSubmittedAt());
        }
    }

    public record FinalRatingView(Rating rating, Instant setAt, Instant releasedAt,
                                 Boolean signedOffByHr) {

        /** {@code setBy} is not exposed: it is always {@code mgr(S)}, and the subject reading
         *  their own rating gains nothing from the id but a second place it could leak. */
        public static FinalRatingView of(FinalRating rating, Boolean signedOffByHr) {
            return new FinalRatingView(rating.getRating(), rating.getSetAt(),
                    rating.getReleasedAt(), signedOffByHr);
        }

        /**
         * The same rating with the sign-off withheld.
         *
         * <p>Whether HR have been through this rating is part of the calibration trail, and
         * {@code READ_RATING_AUDIT} has no {@code SELF} grounds (P-4.7). Null rather than false,
         * for the reason the peer count is absent rather than zero: a false is an answer.
         */
        public static FinalRatingView withoutSignOff(FinalRating rating) {
            return of(rating, null);
        }
    }

    /**
     * One reviewee's record, containing only the sections the caller had grounds for.
     *
     * @param visibleSections the sections the caller had <em>grounds</em> for, named - so a
     *                        client can render honestly instead of guessing from nulls. It is
     *                        the grounds and not the content: a manager who may read peer
     *                        feedback is named here even when no peer has written yet, so the
     *                        screen says "not submitted yet" rather than accusing them of
     *                        lacking access they plainly have. Deriving this from whether the
     *                        section arrived collapses those two cases, and the wrong one wins.
     */
    public record ReviewRecordView(
            ReviewSummary summary,
            SelfReviewView selfReview,
            ManagerReviewView managerReview,
            List<PeerReviewView> peerReviews,
            FinalRatingView finalRating,
            List<String> visibleSections) {

        public static ReviewRecordView of(ReviewReadService.ReviewRecord record) {
            // Reported, not recomputed. The read service made these decisions section by
            // section and is the only place that knows how each one was reached.
            List<String> sections = record.grounds().stream().map(Enum::name).sorted().toList();

            return new ReviewRecordView(
                    ReviewSummary.withoutPeerCount(record.participant()),
                    record.selfReview().map(SelfReviewView::of).orElse(null),
                    record.managerReview().map(ManagerReviewView::of).orElse(null),
                    record.peerReviews().isEmpty()
                            ? null
                            : record.peerReviews().stream().map(PeerReviewView::of).toList(),
                    record.finalRating()
                            .map(rating -> FinalRatingView.of(rating, record.ratingSignedOff()))
                            .orElse(null),
                    sections);
        }
    }
}
