package com.altrium.testsupport;

import com.altrium.org.AppUser;
import com.altrium.plan.DevelopmentPlan;
import com.altrium.plan.ImprovementPlan;
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

    public void flush() {
        em.flush();
    }
}
