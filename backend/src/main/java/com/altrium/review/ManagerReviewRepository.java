package com.altrium.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ManagerReviewRepository
        extends JpaRepository<ManagerReview, Long>, JpaSpecificationExecutor<ManagerReview> {

    /** The write path's lookup, reached only after a {@code DIRECT_MANAGER} decision (P-3.7). */
    java.util.Optional<ManagerReview> findByCycleIdAndSubjectId(Long cycleId, Long subjectId);

    /**
     * The manager's words for one person across a named set of cycles, for the history
     * timeline.
     *
     * <p>The cycle ids are the filter, deliberately. Where the caller is the subject, only the
     * cycles whose rating has been released are passed in, so a withheld cycle's feedback is
     * never fetched rather than fetched and hidden (P-3.7 applies the P-4.4 release gate to the
     * words as well as the number). Pass only cycles the caller has already been permitted for.
     */
    java.util.List<ManagerReview> findBySubjectIdAndCycleIdIn(
            Long subjectId, java.util.Collection<Long> cycleIds);
}
