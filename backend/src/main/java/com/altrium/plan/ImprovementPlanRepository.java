package com.altrium.plan;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
