package com.altrium.review;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * The aggregate side of cycle monitoring (P-6.3).
 *
 * <p>Every query here carries the caller's granted departments in its {@code WHERE} clause and
 * groups inside the database. Counting in Java would be the same leak as filtering in Java, one
 * step further removed and harder to spot: a total computed over rows the caller may not see is
 * still a disclosure, and it is the kind that survives code review because no forbidden row
 * appears in the response.
 *
 * <p>Each query also excludes the caller themselves. That is P-2.2 on the aggregate: an HR Head
 * whose explicit grant covers their own department oversees that department without their own
 * case being part of what they oversee. It also keeps these totals consistent with the review
 * list, which excludes the same row - two endpoints disagreeing about how many reviews a
 * department has would itself be the defect.
 *
 * <p>Extends {@link Repository} rather than {@code JpaRepository} on purpose. There is no
 * {@code findAll} to reach for here, so no unscoped read of participants can arrive through
 * this interface by accident.
 */
public interface CycleMonitoringRepository extends Repository<CycleParticipant, Long> {

    /**
     * Per-department completion counts for one cycle.
     *
     * <p>The three joins are one-to-one - each artifact is unique on {@code (cycle, subject)} -
     * so no row multiplies and the participant count stays honest. Peer reviews are counted
     * separately below for exactly that reason: there are two per subject, and joining them
     * here would double every other figure in the row.
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
              AND p.department.id IN :departmentIds
              AND p.subject.id <> :callerId
            GROUP BY p.department.id, p.department.name
            ORDER BY p.department.name
            """)
    List<DepartmentProgress> progressByDepartment(@Param("cycleId") Long cycleId,
                                                  @Param("departmentIds") Collection<Long> departmentIds,
                                                  @Param("callerId") Long callerId);

    /** Peer assignments made, per department. Counted apart to avoid multiplying the row above. */
    @Query("""
            SELECT p.department.id AS departmentId, COUNT(pa.id) AS total
            FROM CycleParticipant p
            JOIN PeerAssignment pa ON pa.cycle = p.cycle AND pa.subject = p.subject
            WHERE p.cycle.id = :cycleId
              AND p.department.id IN :departmentIds
              AND p.subject.id <> :callerId
            GROUP BY p.department.id
            """)
    List<DepartmentTotal> peerAssignmentsByDepartment(@Param("cycleId") Long cycleId,
                                                      @Param("departmentIds") Collection<Long> departmentIds,
                                                      @Param("callerId") Long callerId);

    /** Peer reviews actually submitted, per department. */
    @Query("""
            SELECT p.department.id AS departmentId, COUNT(pr.id) AS total
            FROM CycleParticipant p
            JOIN PeerReview pr ON pr.cycle = p.cycle AND pr.subject = p.subject
            WHERE p.cycle.id = :cycleId
              AND p.department.id IN :departmentIds
              AND p.subject.id <> :callerId
              AND pr.submittedAt IS NOT NULL
            GROUP BY p.department.id
            """)
    List<DepartmentTotal> peerReviewsByDepartment(@Param("cycleId") Long cycleId,
                                                  @Param("departmentIds") Collection<Long> departmentIds,
                                                  @Param("callerId") Long callerId);

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

    interface DepartmentTotal {
        Long getDepartmentId();

        long getTotal();
    }
}
