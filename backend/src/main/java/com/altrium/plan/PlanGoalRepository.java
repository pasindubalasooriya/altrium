package com.altrium.plan;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlanGoalRepository extends JpaRepository<PlanGoal, Long> {

    /**
     * Loads a goal with the plan and its owner attached, because every decision about a goal is
     * really a decision about the person whose plan it belongs to.
     *
     * <p>A goal is addressed by its own id, so an id guessed from somebody else's screen lands
     * here. That is deliberate rather than unfortunate: it forces the point decision on the way
     * out (P-0.4), which is the check that a scoped list alone would never make.
     */
    @EntityGraph(attributePaths = {"plan", "plan.user"})
    Optional<PlanGoal> findWithPlanById(Long id);
}
