package com.altrium.plan;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    /**
     * A plan's goals, with drafts excluded in the query when the caller is the employee.
     *
     * <p>The predicate is in the {@code WHERE} clause rather than applied to a fetched list.
     * The leak this normally guards against is a pagination count, and a plan's goals are not
     * paged - but a draft goal is a manager's unfinished thought about somebody, and "filter it
     * out afterwards" is exactly the shape of code that later gets reused somewhere it does
     * leak. Cheaper to write it correctly once.
     */
    @Query("""
            SELECT g FROM PlanGoal g
            WHERE g.plan.id = :planId
              AND (:includeDrafts = true OR g.agreement <> com.altrium.plan.GoalAgreement.DRAFT)
            ORDER BY g.id
            """)
    List<PlanGoal> findForPlan(@Param("planId") Long planId,
                               @Param("includeDrafts") boolean includeDrafts);
}
