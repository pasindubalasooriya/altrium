package com.altrium.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FinalRatingRepository
        extends JpaRepository<FinalRating, Long>, JpaSpecificationExecutor<FinalRating> {

    /** The one rating a subject has in a cycle, reached only after a decision about them. */
    java.util.Optional<FinalRating> findByCycleIdAndSubjectId(Long cycleId, Long subjectId);

    /**
     * The ratings for a page of subjects, for the sign-off state shown on a review list.
     *
     * <p>Same caution as the batched peer count: pass only the subject ids the caller has
     * already been permitted for. Batched so a page's cost does not grow with the department.
     */
    java.util.List<FinalRating> findByCycleIdAndSubjectIdIn(
            Long cycleId, java.util.Collection<Long> subjectIds);

    /**
     * Whether this person has ever been through a review to its end.
     *
     * <p>Released, not merely set: scenario section 5 shares the rating at step 6 and routes the
     * plan track at step 7, so "the review is complete" means the employee has been told the
     * outcome. A rating sitting unreleased is a decision they have not heard yet.
     *
     * <p>Any cycle, not the current one. Section 9 allows a slipped development plan to be
     * suspended in favour of a new improvement plan later, which is not tied to the review that
     * has just finished.
     */
    boolean existsBySubjectIdAndReleasedAtIsNotNull(Long subjectId);

    /**
     * Every rating this person has ever been given, for the history timeline.
     *
     * <p>Reached only after a point decision about the subject, and only where the caller holds
     * a ground other than {@code SELF}. The subject's own timeline uses the released-only
     * finder below, so an unreleased rating is never fetched for them at all.
     */
    java.util.List<FinalRating> findBySubjectId(Long subjectId);

    /**
     * The same, restricted to ratings that have been shared with the employee (P-4.4).
     *
     * <p>A separate finder rather than a flag on the one above, so the release filter is
     * visible as a {@code WHERE} clause at the call site instead of hiding inside a boolean
     * parameter. Fetching then dropping in Java would leave the withheld rows in the result the
     * mapper walks, one refactor away from being returned.
     */
    java.util.List<FinalRating> findBySubjectIdAndReleasedAtIsNotNull(Long subjectId);
}
