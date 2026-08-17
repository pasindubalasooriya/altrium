package com.altrium.review;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Organisation-wide totals for Leadership (P-7.1).
 *
 * <p>Unscoped by department, and that is the difference from {@link CycleMonitoringRepository}:
 * HR oversee the departments they were granted, Leadership see the organisation. Neither sees a
 * person. Every query here counts and groups inside the database, so no individual row is ever
 * loaded on the way to a total.
 *
 * <p>Extends {@link Repository} rather than {@code JpaRepository} for the same reason as the
 * monitoring repository: there is no {@code findAll} to reach for, so an unscoped read of
 * participants cannot arrive through this interface by accident.
 */
public interface LeadershipMetricsRepository extends Repository<CycleParticipant, Long> {

    /**
     * Per-department completion for one cycle.
     *
     * <p>The three joins are one-to-one on {@code (cycle, subject)}, so no row multiplies and the
     * participant count stays honest. Peer reviews are deliberately absent: there are two per
     * subject, and joining them would double every other figure in the row. Leadership are asking
     * whether the cycle is progressing, which these columns answer.
     *
     * <p>No caller exclusion appears here, unlike the HR queries. Leadership are never reviewees
     * (P-1.5, P-7.2), so a Leadership caller has no participant row for the cycle to exclude.
     */
    @Query("""
            SELECT p.department.id                                              AS departmentId,
                   p.department.name                                            AS departmentName,
                   COUNT(p.id)                                                  AS participants,
                   SUM(CASE WHEN sr.submittedAt IS NOT NULL THEN 1 ELSE 0 END)  AS selfReviewsSubmitted,
                   SUM(CASE WHEN mr.submittedAt IS NOT NULL THEN 1 ELSE 0 END)  AS managerReviewsSubmitted,
                   SUM(CASE WHEN fr.id          IS NOT NULL THEN 1 ELSE 0 END)  AS ratingsSet,
                   SUM(CASE WHEN fr.releasedAt  IS NOT NULL THEN 1 ELSE 0 END)  AS ratingsReleased
            FROM CycleParticipant p
            LEFT JOIN SelfReview    sr ON sr.cycle = p.cycle AND sr.subject = p.subject
            LEFT JOIN ManagerReview mr ON mr.cycle = p.cycle AND mr.subject = p.subject
            LEFT JOIN FinalRating   fr ON fr.cycle = p.cycle AND fr.subject = p.subject
            WHERE p.cycle.id = :cycleId
            GROUP BY p.department.id, p.department.name
            ORDER BY p.department.name
            """)
    List<DepartmentProgress> progressByDepartment(@Param("cycleId") Long cycleId);

    /**
     * The rating distribution for the whole organisation.
     *
     * <p><strong>Organisation-wide on purpose, never per department.</strong> A department with
     * one participant and a distribution of one would name that person's rating, and Leadership
     * hold no grounds to read an individual rating (P-7.1). Grouping by department would turn an
     * aggregate into a disclosure in exactly the departments where it matters most, and it would
     * do so without any individual row appearing in the response - the kind of leak that survives
     * review. The completion counts above are safe to break down because they say only that a
     * rating exists, not what it is.
     */
    @Query("""
            SELECT fr.rating AS rating, COUNT(fr.id) AS total
            FROM CycleParticipant p
            JOIN FinalRating fr ON fr.cycle = p.cycle AND fr.subject = p.subject
            WHERE p.cycle.id = :cycleId
            GROUP BY fr.rating
            """)
    List<RatingTotal> ratingDistribution(@Param("cycleId") Long cycleId);

    /** Counts only. Nothing here names a person, so no review content passes through it. */
    interface DepartmentProgress {
        Long getDepartmentId();

        String getDepartmentName();

        long getParticipants();

        long getSelfReviewsSubmitted();

        long getManagerReviewsSubmitted();

        long getRatingsSet();

        long getRatingsReleased();
    }

    interface RatingTotal {
        Rating getRating();

        long getTotal();
    }
}
