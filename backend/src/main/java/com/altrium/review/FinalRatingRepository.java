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
}
