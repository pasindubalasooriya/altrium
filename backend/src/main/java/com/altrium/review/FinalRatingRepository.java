package com.altrium.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FinalRatingRepository
        extends JpaRepository<FinalRating, Long>, JpaSpecificationExecutor<FinalRating> {

    /** The one rating a subject has in a cycle, reached only after a decision about them. */
    java.util.Optional<FinalRating> findByCycleIdAndSubjectId(Long cycleId, Long subjectId);
}
