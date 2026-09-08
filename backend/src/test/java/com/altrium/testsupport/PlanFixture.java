package com.altrium.testsupport;

import com.altrium.org.AppUser;
import com.altrium.plan.DevelopmentPlan;
import com.altrium.plan.GoalAgreement;
import com.altrium.plan.GoalStatus;
import com.altrium.plan.ImprovementPlan;
import com.altrium.plan.PlanGoal;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Builds plans directly, bypassing {@link com.altrium.plan.PlanService}.
 *
 * <p>Needed for exactly one thing the service will not do: a plan whose deadline has already
 * passed. Opening one is refused, because a deadline nobody can extend and that has already
 * gone would make the plan unmeetable on the day it was written - so the only way to reach the
 * "deadline missed" path in a test is to put the row there.
 *
 * <p>Everything else in the improvement-plan tests goes through the API, deliberately: a
 * fixture that assembled the co-sign state by hand would prove nothing about the endpoints that
 * are supposed to guard it.
 */
@Component
public class PlanFixture {

    private final EntityManager em;

    public PlanFixture(EntityManager em) {
        this.em = em;
    }

    /** An active, co-signed plan whose deadline is in the past. */
    public ImprovementPlan overdueCosignedPlan(AppUser subject, AppUser manager, AppUser hrUser,
                                               LocalDate deadline) {
        ImprovementPlan plan = new ImprovementPlan(
                subject, manager, deadline, "Sustained improvement required or role at risk.");
        em.persist(plan);
        // Co-signed through the entity's own package-private method is not reachable here, so
        // the state is set with a direct update - the one place these tests do that.
        em.flush();
        em.createNativeQuery("""
                        UPDATE improvement_plan
                        SET cosigned_by = :hr, cosigned_at = CURRENT_TIMESTAMP(6)
                        WHERE id = :id
                        """)
                .setParameter("hr", hrUser.getId())
                .setParameter("id", plan.getId())
                .executeUpdate();
        em.refresh(plan);
        return plan;
    }

    /** A suspended development plan, as opening an improvement plan would leave it. */
    public DevelopmentPlan suspendedPlan(AppUser user) {
        DevelopmentPlan plan = new DevelopmentPlan(user);
        em.persist(plan);
        em.flush();
        em.createNativeQuery("""
                        UPDATE development_plan
                        SET status = 'SUSPENDED', suspended_at = CURRENT_TIMESTAMP(6)
                        WHERE id = :id
                        """)
                .setParameter("id", plan.getId())
                .executeUpdate();
        em.refresh(plan);
        return plan;
    }

    /**
     * A development goal in a named agreement and status, on the person's own plan.
     *
     * <p>Set directly rather than driven through the API, because the reminder tests need every
     * combination of DRAFT, PENDING and AGREED against one date, and reaching those through the
     * endpoints would take three calls each and prove something the plan tests already prove.
     * What is under test here is which of them produces an email.
     */
    public PlanGoal developmentGoal(AppUser user, String title, LocalDate targetDate,
                                    GoalAgreement agreement, GoalStatus status) {
        DevelopmentPlan plan = em.createQuery(
                        "SELECT p FROM DevelopmentPlan p WHERE p.user = :user", DevelopmentPlan.class)
                .setParameter("user", user)
                .getResultStream()
                .findFirst()
                .orElseGet(() -> {
                    DevelopmentPlan created = new DevelopmentPlan(user);
                    em.persist(created);
                    return created;
                });

        PlanGoal goal = new PlanGoal(plan, title, null, targetDate);
        em.persist(goal);
        em.flush();

        em.createNativeQuery("UPDATE plan_goal SET agreement = :a, status = :s WHERE id = :id")
                .setParameter("a", agreement.name())
                .setParameter("s", status.name())
                .setParameter("id", goal.getId())
                .executeUpdate();
        em.refresh(goal);
        return goal;
    }

    /** An active improvement plan, co-signed or not, with the development plan suspended. */
    public ImprovementPlan improvementPlan(AppUser subject, AppUser manager, LocalDate deadline,
                                           boolean cosigned) {
        suspendedPlan(subject);

        ImprovementPlan plan = new ImprovementPlan(
                subject, manager, deadline, "Sustained improvement required or role at risk.");
        em.persist(plan);
        em.flush();

        if (cosigned) {
            cosign(plan, manager);
        }
        return plan;
    }

    public PlanGoal improvementGoal(ImprovementPlan plan, String title, LocalDate targetDate) {
        PlanGoal goal = new PlanGoal(plan, title, null, targetDate);
        em.persist(goal);
        em.flush();
        return goal;
    }

    /**
     * Marks a plan co-signed.
     *
     * <p>A native update because {@code ImprovementPlan#cosign} is package-private, which is
     * right: outside its package the only route is {@code PlanService}, and that is what the
     * improvement-plan tests use. Here the co-signature is a precondition rather than the thing
     * being tested.
     */
    public void cosign(ImprovementPlan plan, AppUser hrUser) {
        em.createNativeQuery("""
                        UPDATE improvement_plan
                        SET cosigned_by = :hr, cosigned_at = CURRENT_TIMESTAMP(6)
                        WHERE id = :id
                        """)
                .setParameter("hr", hrUser.getId())
                .setParameter("id", plan.getId())
                .executeUpdate();
        em.flush();
        em.refresh(plan);
    }

    public void flush() {
        em.flush();
    }
}
