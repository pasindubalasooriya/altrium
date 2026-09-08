package com.altrium.plan;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * When each of these people last had progress recorded on a development goal.
     *
     * <p>Feeds the manager's prompt: an employee writes progress and nothing tells anybody, so
     * the note sits on a screen the manager has no reason to open. One grouped query for the
     * whole team rather than one plan fetch per report, for the same reason the peer counts on
     * the review list are batched - the cost must not grow with the size of the team.
     *
     * <p>The ids are the caller's direct reports and they travel in the {@code WHERE} clause,
     * so this cannot return a row for somebody the caller does not manage.
     *
     * <p>Keyed on {@code updatedAt} of a goal that <em>has</em> a progress note. There is no
     * column recording when the note itself was written, and adding one would be a migration
     * for a dot. The approximation is safe in the direction that matters: a manager editing a
     * goal's wording could move the timestamp too, showing a dot with nothing new behind it,
     * and that clears the moment they look. Missing a real update is what would be unforgivable
     * and cannot happen here.
     */
    @Query("""
            SELECT g.plan.user.id, MAX(g.updatedAt) FROM PlanGoal g
            WHERE g.plan.user.id IN :userIds
              AND g.progressNote IS NOT NULL
            GROUP BY g.plan.user.id
            """)
    List<Object[]> findLatestProgressFor(@Param("userIds") Collection<Long> userIds);
}
