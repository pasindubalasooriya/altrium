package com.altrium.web.plan;

import com.altrium.plan.DevelopmentPlan;
import com.altrium.plan.PlanGoal;
import com.altrium.plan.PlanService;
import com.altrium.plan.PlanStatus;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Feature 15 - development plans (P-5.1, P-5.2, P-5.5).
 *
 * <p>No {@code @PreAuthorize}, as everywhere else. "A manager may call this" is not the rule.
 *
 * <p>Three write routes to one goal, which looks like more surface than necessary until you
 * notice that a different person holds each: the employee writes the text, {@code mgr(S)} moves
 * the date, {@code mgr(S)} approves the completion. A single {@code PUT /goals/{id}} taking
 * every field would have had to work out which of the three was being attempted, and would have
 * let an employee move their own deadline by including the field.
 */
@RestController
@RequestMapping("/api/plans/development")
public class DevelopmentPlanController {

    private final PlanService plans;

    public DevelopmentPlanController(PlanService plans) {
        this.plans = plans;
    }

    // ---------------------------------------------------------------- wire shapes

    public record GoalRequest(@NotBlank String title, String detail, LocalDate targetDate) {
    }

    public record GoalEditRequest(@NotBlank String title, String detail) {
    }

    /** Progress is free text and may be cleared, so nothing here is required. */
    public record ProgressRequest(String note) {
    }

    public record TargetDateRequest(LocalDate targetDate) {
    }

    /**
     * @param agreement    DRAFT, PENDING or AGREED (P-5.9). Drives what the client offers, and
     *                     never reaches the employee as DRAFT, because those are not returned
     *                     to them at all.
     * @param progressNote how the goal is going, in the employee's own words, kept apart from
     *                     {@code detail} so that reporting progress cannot restate the goal
     */
    public record GoalView(Long id, String title, String detail, LocalDate targetDate,
                           String status, Instant completedAt, String approvedBy,
                           String agreement, Instant submittedAt, Instant agreedAt,
                           String progressNote) {

        static GoalView of(PlanGoal goal) {
            return new GoalView(
                    goal.getId(), goal.getTitle(), goal.getDetail(), goal.getTargetDate(),
                    goal.getStatus().name(), goal.getCompletedAt(),
                    goal.getApprovedBy() == null ? null : goal.getApprovedBy().getFullName(),
                    goal.getAgreement() == null ? null : goal.getAgreement().name(),
                    goal.getSubmittedAt(), goal.getAgreedAt(), goal.getProgressNote());
        }
    }

    public record PlanView(Long userId, String userName, String status, boolean active,
                           Instant suspendedAt, List<GoalView> goals) {

        /**
         * Goals are sorted here rather than left to the entity's {@code @OrderBy}, which only
         * runs when the collection is loaded from the database. A goal added earlier in the
         * same transaction is already in the session and arrives in insertion order, so the
         * response would be sorted or not depending on what else the request had done. Sorting
         * in the mapper makes the order a property of the response.
         *
         * <p>Undated goals sort last: a goal with no date yet is one still being agreed, and it
         * belongs after the ones that have been.
         *
         * @param suspensionVisible false where the caller is the employee and their improvement
         *   plan has not been co-signed. Suspension has one cause, so showing it would announce
         *   the plan that P-5.3 withholds until HR sign it. The plan then reads as ordinary and
         *   active to them, which is what it read as the day before it was opened.
         */
        static PlanView of(DevelopmentPlan plan, List<PlanGoal> visibleGoals,
                           boolean suspensionVisible) {
            List<GoalView> goals = visibleGoals.stream()
                    .sorted(Comparator
                            .comparing(PlanGoal::getTargetDate,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(PlanGoal::getId))
                    .map(GoalView::of)
                    .toList();

            return new PlanView(
                    plan.getUser().getId(),
                    plan.getUser().getFullName(),
                    suspensionVisible ? plan.getStatus().name() : PlanStatus.ACTIVE.name(),
                    suspensionVisible ? plan.isActive() : true,
                    suspensionVisible ? plan.getSuspendedAt() : null,
                    goals);
        }
    }

    // ---------------------------------------------------------------- reading

    /** The caller's own plan. Takes no id, so it cannot be pointed at anybody else. */
    @GetMapping("/me")
    @Operation(summary = "Your own development plan, created on first sight (scenario section 8)")
    public PlanView myPlan() {
        return plans.myPlan(PlanView::of);
    }

    /**
     * Somebody's plan, for their manager or for HR-in-scope.
     *
     * <p>Returns an empty plan rather than a 404 for an employee who has never written a goal,
     * because they do have a plan; nobody had put anything in it.
     */
    @GetMapping("/{userId}")
    @Operation(summary = "An employee's development plan; HR may read and never write (P-5.1)")
    public PlanView plan(@PathVariable Long userId) {
        return plans.readPlan(userId, PlanView::of);
    }

    // ---------------------------------------------------------------- writing

    @PostMapping("/{userId}/goals")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a goal, as the employee or their manager")
    public GoalView addGoal(@PathVariable Long userId, @Valid @RequestBody GoalRequest request) {
        return plans.addGoal(
                userId, request.title(), request.detail(), request.targetDate(), GoalView::of);
    }

    /** Text only, and only while the goal is still a draft. The manager's (P-5.9). */
    @PutMapping("/goals/{goalId}")
    @Operation(summary = "Reword a goal; refused once the employee has agreed to it (P-5.9)")
    public GoalView editGoal(@PathVariable Long goalId, @Valid @RequestBody GoalEditRequest request) {
        return plans.editGoal(goalId, request.title(), request.detail(), GoalView::of);
    }

    /**
     * When each of the caller's direct reports last had progress recorded on a goal.
     *
     * <p>Drives the manager's prompt, and carries **timestamps only** - no goal, no note, no
     * plan content. A manager may read all of that anyway, one report at a time, but a feed is
     * a different shape from a page: this one exists to say "there is something to look at",
     * and it says exactly that and nothing more.
     *
     * <p>A manager with no reports gets an empty object rather than a denial, the same shape
     * the scoped review list takes for somebody who may see nothing.
     */
    @GetMapping("/my-team/progress")
    @Operation(summary = "When each direct report last recorded progress; timestamps only")
    public Map<Long, Instant> teamProgress() {
        return plans.latestGoalProgressForMyReports();
    }

    /**
     * Submits a drafted goal to the employee (P-5.9).
     *
     * <p>Until this, the goal is not on their plan at all - not shown as pending, not counted,
     * simply not returned to them. Submitting is what puts it in front of them to accept.
     */
    @PostMapping("/goals/{goalId}/submission")
    @Operation(summary = "Submit a drafted goal to the employee for agreement (P-5.9)")
    public GoalView submitGoal(@PathVariable Long goalId) {
        return plans.submitGoal(goalId, GoalView::of);
    }

    /**
     * The employee agreeing to a submitted goal (P-5.9).
     *
     * <p>Takes no user id, so the API cannot express agreeing on somebody else's behalf - the
     * same shape as the self-review, and for the same reason. {@code AGREE_DEVELOPMENT_GOAL}
     * carries {@code SELF} alone, so the manager who wrote the goal cannot agree to it either.
     */
    @PostMapping("/goals/{goalId}/agreement")
    @Operation(summary = "Agree to a goal your manager submitted; the employee's alone (P-5.9)")
    public GoalView agreeGoal(@PathVariable Long goalId) {
        return plans.agreeGoal(goalId, GoalView::of);
    }

    /**
     * Recording how an agreed goal is going (P-5.9).
     *
     * <p>Writes a field of its own and never the goal's wording, so progress cannot restate the
     * goal that was agreed. The employee or their manager.
     */
    @PutMapping("/goals/{goalId}/progress")
    @Operation(summary = "Record progress on an agreed goal, without touching its wording")
    public GoalView reportProgress(@PathVariable Long goalId,
                                   @RequestBody ProgressRequest request) {
        return plans.reportProgress(goalId, request.note(), GoalView::of);
    }

    /**
     * Moves the target date (P-5.5). {@code mgr(S)} only.
     *
     * <p>Development dates move, which is exactly what a PIP deadline does not do. That
     * contrast is the difference in weight between the two instruments, and it is worth being
     * able to point at two endpoints that behave differently.
     */
    @PutMapping("/goals/{goalId}/target-date")
    @Operation(summary = "Move a goal's target date; the manager's, unlike a PIP deadline (P-5.5)")
    public GoalView moveTargetDate(@PathVariable Long goalId,
                                   @RequestBody TargetDateRequest request) {
        return plans.moveTargetDate(goalId, request.targetDate(), GoalView::of);
    }

    /** Approves completion (P-5.2). The employee reports progress; the manager says it is done. */
    @PostMapping("/goals/{goalId}/approval")
    @Operation(summary = "Approve a goal as complete; the manager's alone (P-5.2)")
    public GoalView approveGoal(@PathVariable Long goalId) {
        return plans.approveGoal(goalId, GoalView::of);
    }

    /** Reverses an approval. The same authority that granted it, so the two cannot drift apart. */
    @DeleteMapping("/goals/{goalId}/approval")
    @Operation(summary = "Reopen an approved goal")
    public GoalView reopenGoal(@PathVariable Long goalId) {
        return plans.reopenGoal(goalId, GoalView::of);
    }

    @DeleteMapping("/goals/{goalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove an open goal; an approved one must be reopened first")
    public void removeGoal(@PathVariable Long goalId) {
        plans.removeGoal(goalId);
    }
}
