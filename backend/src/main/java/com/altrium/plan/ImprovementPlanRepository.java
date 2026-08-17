package com.altrium.plan;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ImprovementPlanRepository extends JpaRepository<ImprovementPlan, Long> {

    /**
     * The one running plan, if there is one. At most one can exist per person, and that is a
     * guarantee of the schema rather than of this method: {@code active_user_id} is a generated
     * column carrying a unique index (P-5.7).
     */
    @EntityGraph(attributePaths = {"user", "goals"})
    Optional<ImprovementPlan> findByUserIdAndStatus(Long userId, ImprovementStatus status);

    /** Every plan a person has held, newest first. History, so closed plans are included. */
    @EntityGraph(attributePaths = {"user", "goals"})
    List<ImprovementPlan> findByUserIdOrderByOpenedAtDesc(Long userId);

    @EntityGraph(attributePaths = {"user", "goals"})
    Optional<ImprovementPlan> findWithGoalsById(Long id);

    /**
     * Running plans in an HR user's granted departments (P-2.1, P-6.3 in spirit).
     *
     * <p>Scoped in the query, like every other collection read. The department comes from the
     * employee's current department rather than a cycle snapshot, because an improvement plan
     * is not attached to a cycle - it runs whenever a manager opens one.
     *
     * <p>{@code subject.id <> :callerId} is P-2.2 as a SQL predicate. An HR user never finds
     * their own improvement plan in a list their grant produced, so an HR Head with an explicit
     * grant over their own department cannot arrive at their own case through the department
     * route. They still read it as its subject, through {@code /me}, once it is co-signed.
     */
    @EntityGraph(attributePaths = {"user", "goals"})
    @Query("""
            SELECT p FROM ImprovementPlan p
            WHERE p.status = :status
              AND p.user.department.id IN :departmentIds
              AND p.user.id <> :callerId
            ORDER BY p.deadline, p.id
            """)
    List<ImprovementPlan> findInDepartments(@Param("status") ImprovementStatus status,
                                            @Param("departmentIds") Collection<Long> departmentIds,
                                            @Param("callerId") Long callerId);
}
