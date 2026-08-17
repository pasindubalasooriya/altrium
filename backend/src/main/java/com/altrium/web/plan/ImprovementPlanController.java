package com.altrium.web.plan;

import com.altrium.plan.ImprovementPlan;
import com.altrium.plan.PlanGoal;
import com.altrium.plan.PlanService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
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

/**
 * Feature 16 - improvement plans (P-5.3 to P-5.7).
 *
 * <p>Three actors, and the separation between them is the feature rather than a side effect of
 * it. The manager opens the plan, writes its goals and its consequence clause, and judges the
 * outcome. HR co-sign it and record the witness, and can do neither of the manager's jobs. The
 * employee sees it only once HR have signed, and writes nothing at all.
 *
 * <p>One endpoint here exists purely in order to refuse: {@link #extendDeadline}.
 * {@code EXTEND_PIP_DEADLINE} names no actor, so it denies the manager who opened the plan, the
 * HR user who co-signed it and the Super Admin alike. Leaving it unbuilt would have been a rule
 * nobody enforced, and the first person who needed one would have added it without the rule.
 */
@RestController
@RequestMapping("/api/plans/improvement")
public class ImprovementPlanController {

    private final PlanService plans;

    public ImprovementPlanController(PlanService plans) {
        this.plans = plans;
    }

    // ---------------------------------------------------------------- wire shapes

    public record OpenRequest(String consequenceClause, @NotNull LocalDate deadline) {
    }

    public record ConsequenceRequest(String consequenceClause) {
    }

    public record WitnessRequest(@NotBlank String witnessName) {
    }

    public record GoalRequest(@NotBlank String title, String detail, LocalDate targetDate) {
    }

    public record DeadlineRequest(@NotNull LocalDate deadline) {
    }

    public record ImprovementGoalView(Long id, String title, String detail, LocalDate targetDate,
                                      String status, Instant completedAt, String approvedBy) {

        static ImprovementGoalView of(PlanGoal goal) {
            return new ImprovementGoalView(
                    goal.getId(), goal.getTitle(), goal.getDetail(), goal.getTargetDate(),
                    goal.getStatus().name(), goal.getCompletedAt(),
                    goal.getApprovedBy() == null ? null : goal.getApprovedBy().getFullName());
        }
    }

    /**
     * Names the co-signer and the witness, because a formality nobody can be identified with is
     * not a formality. The deadline is present and there is no endpoint that changes it.
     */
    public record ImprovementPlanView(
            Long id, Long userId, String userName, String status, boolean active,
            String openedBy, Instant openedAt, LocalDate deadline,
            String consequenceClause,
            String cosignedBy, Instant cosignedAt, boolean cosigned,
            String witnessName, Instant witnessRecordedAt,
            Instant closedAt,
            List<ImprovementGoalView> goals) {

        static ImprovementPlanView of(ImprovementPlan plan) {
            List<ImprovementGoalView> goals = plan.getGoals().stream()
                    .sorted(Comparator
                            .comparing(PlanGoal::getTargetDate,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(PlanGoal::getId))
                    .map(ImprovementGoalView::of)
                    .toList();

            return new ImprovementPlanView(
                    plan.getId(),
                    plan.getUser().getId(),
                    plan.getUser().getFullName(),
                    plan.getStatus().name(),
                    plan.isActive(),
                    plan.getOpenedBy().getFullName(),
                    plan.getOpenedAt(),
                    plan.getDeadline(),
                    plan.getConsequenceClause(),
                    plan.getCosignedBy() == null ? null : plan.getCosignedBy().getFullName(),
                    plan.getCosignedAt(),
                    plan.isCosigned(),
                    plan.getWitnessName(),
                    plan.getWitnessRecordedAt(),
                    plan.getClosedAt(),
                    goals);
        }
    }

    /**
     * The employee's own view.
     *
     * <p>{@code hasPlan} is false both when no plan exists and when one exists but has not been
     * co-signed, and those two cases are deliberately indistinguishable. Telling them apart
     * would disclose that a plan had been drafted about somebody, which is exactly what the
     * co-sign gate withholds (P-5.3).
     */
    public record OwnImprovementPlanView(boolean hasPlan, ImprovementPlanView plan) {
    }

    // ---------------------------------------------------------------- the manager's half

    @PostMapping("/{userId}")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open an improvement plan; suspends the development plan (P-5.7)")
    public ImprovementPlanView open(@PathVariable Long userId, @Valid @RequestBody OpenRequest request) {
        return plans.openImprovementPlan(
                userId, request.consequenceClause(), request.deadline(), ImprovementPlanView::of);
    }

    @PostMapping("/{planId}/goals")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a goal to an improvement plan; the manager's, not the employee's")
    public ImprovementGoalView addGoal(@PathVariable Long planId,
                                       @Valid @RequestBody GoalRequest request) {
        return plans.addImprovementGoal(
                planId, request.title(), request.detail(), request.targetDate(),
                ImprovementGoalView::of);
    }

    /** Fixed once co-signed: the clause is what the employee accepted (P-5.6). */
    @PutMapping("/{planId}/consequence-clause")
    @Operation(summary = "Write the consequence clause; required before co-signing (P-5.6)")
    public ImprovementPlanView setConsequenceClause(@PathVariable Long planId,
                                                    @RequestBody ConsequenceRequest request) {
        return plans.setConsequenceClause(
                planId, request.consequenceClause(), ImprovementPlanView::of);
    }

    @PostMapping("/{planId}/pass")
    @Operation(summary = "Pass the plan; resumes the same development plan, goals intact")
    public ImprovementPlanView pass(@PathVariable Long planId) {
        return plans.pass(planId, ImprovementPlanView::of);
    }

    @PostMapping("/{planId}/fail")
    @Operation(summary = "Fail the plan, once its deadline has passed")
    public ImprovementPlanView fail(@PathVariable Long planId) {
        return plans.fail(planId, ImprovementPlanView::of);
    }

    // ---------------------------------------------------------------- HR's half

    /**
     * Co-signing, which is also what makes the plan visible to the employee (P-5.3, P-5.4).
     *
     * <p>Those two being one event is the design. Until HR have read the plan and put their
     * name to it, the employee cannot see it - so a manager cannot draft a PIP and confront
     * somebody with it unreviewed.
     */
    @PostMapping("/{planId}/cosign")
    @Operation(summary = "Co-sign the plan; HR only, and refused without a consequence clause")
    public ImprovementPlanView cosign(@PathVariable Long planId) {
        return plans.cosign(planId, ImprovementPlanView::of);
    }

    /** Also HR's alone. A manager can do neither of these, which is the point of both. */
    @PostMapping("/{planId}/witness")
    @Operation(summary = "Record the witness to the meeting; HR only (P-5.4)")
    public ImprovementPlanView recordWitness(@PathVariable Long planId,
                                             @Valid @RequestBody WitnessRequest request) {
        return plans.recordWitness(planId, request.witnessName(), ImprovementPlanView::of);
    }

    // ---------------------------------------------------------------- the refusal

    /**
     * Always 403, for everybody (P-5.5).
     *
     * <p>The one endpoint in Altrium whose entire purpose is to be denied. A PIP deadline is
     * fixed the moment the plan is opened, and no role, grant or relationship moves it.
     */
    @PutMapping("/{planId}/deadline")
    @Operation(summary = "Refused for every caller: a PIP deadline is immutable (P-5.5)")
    public void extendDeadline(@PathVariable Long planId, @Valid @RequestBody DeadlineRequest request) {
        plans.extendDeadline(planId, request.deadline());
    }

    // ---------------------------------------------------------------- reading

    /** The employee's own. Identical response whether no plan exists or one is not yet co-signed. */
    @GetMapping("/me")
    @Operation(summary = "Your own improvement plan, once co-signed (P-5.3)")
    public OwnImprovementPlanView myPlan() {
        return plans.myImprovementPlan()
                .map(plan -> new OwnImprovementPlanView(true, ImprovementPlanView.of(plan)))
                .orElseGet(() -> new OwnImprovementPlanView(false, null));
    }

    /**
     * The running plans an HR user oversees - their queue.
     *
     * <p>Mapped before {@code /{userId}/active} in this file only for readability; the paths do
     * not collide. Its scope is the {@code COSIGN_IMPROVEMENT_PLAN} capability's, so a manager
     * calling it gets an empty list rather than a denial: they have no HR scope, which is not
     * the same as being refused, and it is the same shape the scoped review list takes for
     * somebody who may see nothing.
     */
    @GetMapping
    @Operation(summary = "Running improvement plans in your HR scope, including ones awaiting co-signature")
    public List<ImprovementPlanView> inScope() {
        return plans.improvementPlansInScope(ImprovementPlanView::of);
    }

    @GetMapping("/{userId}/active")
    @Operation(summary = "Someone's active improvement plan, for their manager or HR-in-scope")
    public ImprovementPlanView active(@PathVariable Long userId) {
        return plans.readImprovementPlan(userId, ImprovementPlanView::of);
    }

    @GetMapping("/{userId}/history")
    @Operation(summary = "Every improvement plan this person has held that was put to them")
    public List<ImprovementPlanView> history(@PathVariable Long userId) {
        return plans.improvementPlanHistory(userId, ImprovementPlanView::of);
    }
}
