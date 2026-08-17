package com.altrium.plan;

import com.altrium.auth.AccessDeniedApiException;
import com.altrium.auth.AuthorizationService;
import com.altrium.auth.Capability;
import com.altrium.auth.CurrentUserService;
import com.altrium.auth.ReviewSubject;
import com.altrium.config.ConflictApiException;
import com.altrium.config.ValidationApiException;
import com.altrium.org.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.function.Function;

/**
 * Feature 15 - development plans.
 *
 * <p><strong>The only thing in Altrium that writes a plan's status</strong> (P-5.8), which is
 * why the setters on {@link DevelopmentPlan} are package-private. Nothing sets a status in this
 * feature - suspension arrives with the improvement plan - but the door is shut now rather than
 * after somebody has walked through it.
 *
 * <h2>Universal, by materialising on demand</h2>
 *
 * <p>Scenario section 8 gives every employee a plan from the moment they join. This is
 * implemented by {@link #planFor} creating the row the first time anybody asks for it, rather
 * than by {@code OrgService} creating one alongside each user.
 *
 * <p>That is a deliberate choice between two honest options. Creating it at user creation would
 * put plan machinery inside the org service, and would still have needed this path anyway for
 * the thirty-one people who already existed before the feature did - two mechanisms, one of
 * which would be exercised almost never and would therefore be the broken one. From the API's
 * point of view every employee has a plan, which is what section 8 asks for.
 *
 * <h2>Who writes what</h2>
 *
 * <p>Three capabilities rather than one, because three different people are involved and the
 * differences are the policy:
 *
 * <ul>
 *   <li>{@code WRITE_DEVELOPMENT_PLAN} - the employee and their manager. <b>Not HR</b>, who
 *       read plans and never write them (P-5.1): development is between the two of them, and
 *       HR oversee that it is happening.</li>
 *   <li>{@code MOVE_GOAL_TARGET_DATE} - {@code mgr(S)} alone (P-5.5). An employee who could
 *       reschedule their own deadlines would make the date decorative.</li>
 *   <li>{@code APPROVE_GOAL} - {@code mgr(S)} alone (P-5.2). Self-approval is the whole thing
 *       a development plan is meant not to be.</li>
 * </ul>
 */
@Service
@Transactional
public class PlanService {

    private final AuthorizationService authorization;
    private final CurrentUserService currentUser;
    private final AppUserRepository users;
    private final DevelopmentPlanRepository plans;
    private final PlanGoalRepository goals;

    public PlanService(AuthorizationService authorization,
                       CurrentUserService currentUser,
                       AppUserRepository users,
                       DevelopmentPlanRepository plans,
                       PlanGoalRepository goals) {
        this.authorization = authorization;
        this.currentUser = currentUser;
        this.users = users;
        this.plans = plans;
        this.goals = goals;
    }

    // ================================================================= reading

    /** The caller's own plan. Takes no id, so it cannot be pointed at anybody else. */
    public <T> T myPlan(Function<DevelopmentPlan, T> mapper) {
        return readPlan(currentUser.require().id(), mapper);
    }

    /**
     * Somebody's plan, for their manager or for HR-in-scope (P-5.1).
     *
     * <p>Materialises the plan if it does not exist yet, which is what makes it universal. A
     * manager asking about a report who has never opened the app gets an empty plan rather than
     * a 404, because the employee does have one - nobody had written it down.
     */
    public <T> T readPlan(Long userId, Function<DevelopmentPlan, T> mapper) {
        ReviewSubject subject = authorization.subject(userId);
        authorization.require(Capability.READ_DEVELOPMENT_PLAN, subject);
        return mapper.apply(planFor(userId));
    }

    // ================================================================= writing

    /** Adds a goal. Written by the employee or their manager; never by HR (P-5.1). */
    public <T> T addGoal(Long userId, String title, String detail, LocalDate targetDate,
                         Function<PlanGoal, T> mapper) {

        ReviewSubject subject = authorization.subject(userId);
        authorization.require(Capability.WRITE_DEVELOPMENT_PLAN, subject);
        requireTitle(title);

        DevelopmentPlan plan = requireActivePlan(userId);
        PlanGoal goal = new PlanGoal(plan, title.trim(), detail, targetDate);
        plan.addGoal(goal);
        return mapper.apply(goals.save(goal));
    }

    /**
     * Edits a goal's text. The employee's route for reporting progress.
     *
     * <p>The target date is deliberately not editable here. It moves through {@link
     * #moveTargetDate}, under a different capability, because P-5.5 gives the dates to
     * {@code mgr(S)}.
     */
    public <T> T editGoal(Long goalId, String title, String detail, Function<PlanGoal, T> mapper) {
        PlanGoal goal = requireGoal(goalId);
        authorization.require(Capability.WRITE_DEVELOPMENT_PLAN, subjectOf(goal));
        requireTitle(title);

        if (goal.isComplete()) {
            // 409: the caller holds the permission, and it is the approved record that
            // refuses. Rewriting a goal the manager has signed off would change what was
            // approved after the fact.
            throw new ConflictApiException(
                    "That goal was approved as complete and can no longer be edited");
        }

        goal.setTitle(title.trim());
        goal.setDetail(detail);
        return mapper.apply(goal);
    }

    /**
     * Moves a goal's target date (P-5.5).
     *
     * <p>Development dates move; that is the point of them. A goal that slipped because the
     * quarter went differently is one to reschedule, not a failure to record. The contrast with
     * a PIP deadline, which no actor may extend, is the whole difference in weight between the
     * two instruments.
     */
    public <T> T moveTargetDate(Long goalId, LocalDate targetDate, Function<PlanGoal, T> mapper) {
        PlanGoal goal = requireGoal(goalId);
        authorization.require(Capability.MOVE_GOAL_TARGET_DATE, subjectOf(goal));

        if (goal.isComplete()) {
            throw new ConflictApiException(
                    "That goal is complete; its target date is now part of the record");
        }
        goal.setTargetDate(targetDate);
        return mapper.apply(goal);
    }

    /**
     * Approves a goal as complete (P-5.2).
     *
     * <p>{@code mgr(S)} alone, and the approver is recorded. The employee reports progress in
     * the goal's text and does not close it, which is the difference between a development plan
     * and a to-do list.
     */
    public <T> T approveGoal(Long goalId, Function<PlanGoal, T> mapper) {
        PlanGoal goal = requireGoal(goalId);
        authorization.require(Capability.APPROVE_GOAL, subjectOf(goal));

        if (goal.isComplete()) {
            throw new ConflictApiException("That goal has already been approved");
        }
        goal.approve(users.getReferenceById(currentUser.require().id()));
        return mapper.apply(goal);
    }

    /**
     * Reopens an approved goal.
     *
     * <p>Also {@code APPROVE_GOAL}: reversing a completion is the same authority as granting
     * it, and giving it its own capability would let the two drift apart. An approval reversed
     * by anybody other than the person who could grant it would be worth very little.
     */
    public <T> T reopenGoal(Long goalId, Function<PlanGoal, T> mapper) {
        PlanGoal goal = requireGoal(goalId);
        authorization.require(Capability.APPROVE_GOAL, subjectOf(goal));

        if (!goal.isComplete()) {
            throw new ConflictApiException("That goal is already open");
        }
        goal.reopen();
        return mapper.apply(goal);
    }

    /** Removes a goal that should not have been there. The employee or their manager. */
    public void removeGoal(Long goalId) {
        PlanGoal goal = requireGoal(goalId);
        authorization.require(Capability.WRITE_DEVELOPMENT_PLAN, subjectOf(goal));

        if (goal.isComplete()) {
            // Deleting approved progress would quietly rewrite the history the carry-over
            // pillar rests on. Reopen it first, deliberately, and then remove it.
            throw new ConflictApiException(
                    "That goal was approved as complete; reopen it before removing it");
        }
        goal.getPlan().removeGoal(goal);
        goals.delete(goal);
    }

    // ================================================================= internals

    /**
     * The plan, created on first sight.
     *
     * <p>Reached only after a decision about the person, so a Leadership member never arrives
     * here - every plan capability is an artifact capability, and those are refused for a
     * Leadership subject at step 2 (P-1.5, P-7.2). Their plan is not hidden; it is never
     * brought into existence.
     */
    private DevelopmentPlan planFor(Long userId) {
        return plans.findByUserId(userId)
                .orElseGet(() -> plans.save(new DevelopmentPlan(users.getReferenceById(userId))));
    }

    private DevelopmentPlan requireActivePlan(Long userId) {
        DevelopmentPlan plan = planFor(userId);
        if (!plan.isActive()) {
            // Reachable only once feature 16 can suspend a plan. Written now because a
            // suspended plan accepting new goals would defeat the point of suspending it, and
            // this is cheaper to state than to remember.
            throw new ConflictApiException(
                    "This development plan is suspended while an improvement plan runs (P-5.7)");
        }
        return plan;
    }

    private PlanGoal requireGoal(Long goalId) {
        // 403 rather than 404 for an unknown id, exactly as for a review: the difference
        // between "no such goal" and "not your goal" is enough to enumerate one at a time.
        return goals.findWithPlanById(goalId)
                .orElseThrow(() -> new AccessDeniedApiException("P-0.5: no readable goal " + goalId));
    }

    /** Every decision about a goal is really a decision about whose plan it is. */
    private ReviewSubject subjectOf(PlanGoal goal) {
        return authorization.subject(goal.getPlan().getUser().getId());
    }

    private static void requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new ValidationApiException("A goal needs a title");
        }
    }
}
