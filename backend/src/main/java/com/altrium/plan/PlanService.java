package com.altrium.plan;

import com.altrium.auth.AccessDeniedApiException;
import com.altrium.auth.ArtifactState;
import com.altrium.auth.AuthorizationService;
import com.altrium.auth.Capability;
import com.altrium.auth.CurrentUserService;
import com.altrium.auth.ReviewSubject;
import com.altrium.auth.SubjectScope;
import com.altrium.config.ConflictApiException;
import com.altrium.config.NotFoundApiException;
import com.altrium.config.ValidationApiException;
import com.altrium.org.AppUserRepository;
import com.altrium.review.FinalRatingRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final ImprovementPlanRepository improvementPlans;
    private final FinalRatingRepository finalRatings;
    private final Clock clock;

    public PlanService(AuthorizationService authorization,
                       CurrentUserService currentUser,
                       AppUserRepository users,
                       DevelopmentPlanRepository plans,
                       PlanGoalRepository goals,
                       ImprovementPlanRepository improvementPlans,
                       FinalRatingRepository finalRatings,
                       Clock clock) {
        this.authorization = authorization;
        this.currentUser = currentUser;
        this.users = users;
        this.plans = plans;
        this.goals = goals;
        this.improvementPlans = improvementPlans;
        this.finalRatings = finalRatings;
        this.clock = clock;
    }

    // ================================================================= reading

    /**
     * How a plan is presented to one caller.
     *
     * <p>Three arguments rather than two because {@code suspensionVisible} is a decision, not a
     * property of the row: the same suspended plan is shown as suspended to the manager and as
     * ordinary to the employee whose improvement plan HR have not yet co-signed. See
     * {@link #readPlan}.
     */
    @FunctionalInterface
    public interface PlanMapper<T> {
        T map(DevelopmentPlan plan, List<PlanGoal> goals, boolean suspensionVisible);
    }

    /** The caller's own plan. Takes no id, so it cannot be pointed at anybody else. */
    public <T> T myPlan(PlanMapper<T> mapper) {
        return readPlan(currentUser.require().id(), mapper);
    }

    /**
     * Somebody's plan, for their manager or for HR-in-scope (P-5.1).
     *
     * <p>Materialises the plan if it does not exist yet, which is what makes it universal. A
     * manager asking about a report who has never opened the app gets an empty plan rather than
     * a 404, because the employee does have one - nobody had written it down.
     */
    public <T> T readPlan(Long userId, PlanMapper<T> mapper) {
        ReviewSubject subject = authorization.subject(userId);
        authorization.require(Capability.READ_DEVELOPMENT_PLAN, subject);

        DevelopmentPlan plan = planFor(userId);

        // A draft goal is the manager's unfinished thought about this person, and it is not on
        // their plan until it is submitted (P-5.9). Everyone else reading the plan - the
        // manager who is writing it, HR-in-scope - sees the drafts, because somebody has to be
        // able to see what they are working on.
        boolean callerIsSubject = currentUser.require().id().equals(userId);
        List<PlanGoal> visible = goals.findForPlan(plan.getId(), !callerIsSubject);

        return mapper.map(plan, visible, suspensionVisibleTo(userId, callerIsSubject));
    }

    /**
     * Whether this caller may be told the plan is suspended.
     *
     * <p>Suspension has exactly one cause: an improvement plan was opened (P-5.7). So telling
     * the employee their plan is on hold tells them a PIP exists - which is the fact P-5.3
     * withholds until HR have co-signed it. The plan screen was defeating the co-sign gate from
     * the side: it said "on hold while an improvement plan is running" and linked to a page
     * that then denied any plan existed.
     *
     * <p>So for the subject, and only for the subject, an uncosigned suspension is presented as
     * an ordinary active plan. The manager and HR always see the truth - somebody has to draft
     * it and somebody has to review it before signing.
     *
     * <p>The row is untouched. This is presentation, not state: writes to a suspended plan are
     * still refused with 409, which is the one visible seam and is preferable to disclosing the
     * plan early.
     */
    private boolean suspensionVisibleTo(Long userId, boolean callerIsSubject) {
        if (!callerIsSubject) {
            return true;
        }
        return improvementPlans.findByUserIdAndStatus(userId, ImprovementStatus.ACTIVE)
                .map(ImprovementPlan::isCosigned)
                .orElse(true);
    }

    // ================================================================= writing

    /**
     * Drafts a goal (P-5.9). The manager alone; never the employee, and never HR.
     *
     * <p>It starts as a draft and is invisible to the employee until {@link #submitGoal}.
     */
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
     * Rewords a goal while it is still the manager's to reword.
     *
     * <p>No longer the employee's route for reporting progress - that is
     * {@link #reportProgress}, which writes to a field of its own. Under P-5.9 this is the
     * manager's, and only until the employee agrees.
     *
     * <p>The target date is deliberately not editable here. It moves through {@link
     * #moveTargetDate}, under a different capability, because P-5.5 gives the dates to
     * {@code mgr(S)} - and unlike the wording, it keeps moving after agreement.
     */
    public <T> T editGoal(Long goalId, String title, String detail, Function<PlanGoal, T> mapper) {
        PlanGoal goal = requireGoal(goalId);
        authorization.require(writeCapabilityFor(goal), subjectOf(goal));
        requireTitle(title);

        if (goal.isAgreed()) {
            // 409: the manager holds the capability throughout, and it is the agreement that
            // forbids the write. An agreed goal whose wording could still be changed is not one
            // that was agreed to - the employee would have accepted one thing and be held to
            // another, with nothing on the record showing it had moved.
            throw new ConflictApiException(
                    "That goal has been agreed and its wording is fixed. Add a new goal instead,"
                            + " or move its target date.");
        }

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

        // A date on an improvement plan is a PIP deadline, wherever it sits, so it goes through
        // the capability with no grounds and is refused for everybody (P-5.5). Routing it here
        // rather than raising a conflict keeps one answer to "may this date move?" - and makes
        // the refusal a 403 that names the same rule as the plan-level deadline.
        authorization.require(
                goal.isImprovementGoal()
                        ? Capability.EXTEND_PIP_DEADLINE
                        : Capability.MOVE_GOAL_TARGET_DATE,
                subjectOf(goal));

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

    /**
     * Submits a drafted goal to the employee (P-5.9).
     *
     * <p>Until this happens the goal is not on their plan at all. Submitting is what asks them
     * to accept it, so it is the manager's and nobody else's.
     */
    public <T> T submitGoal(Long goalId, Function<PlanGoal, T> mapper) {
        PlanGoal goal = requireDevelopmentGoal(goalId, "submitted");
        authorization.require(Capability.WRITE_DEVELOPMENT_PLAN, subjectOf(goal));

        if (!goal.isDraft()) {
            throw new ConflictApiException("That goal has already been submitted");
        }
        if (goal.getTitle() == null || goal.getTitle().isBlank()) {
            throw new ValidationApiException("A goal cannot be submitted without a title");
        }

        goal.submit();
        return mapper.apply(goal);
    }

    /**
     * The employee agreeing to a goal their manager submitted (P-5.9).
     *
     * <p>{@code AGREE_DEVELOPMENT_GOAL} carries {@code SELF} alone, so this is the one thing in
     * the plan machinery nobody can do on somebody else's behalf.
     *
     * <p>There is no matching "decline". An un-agreed goal simply stays pending, visibly, which
     * is the signal that a conversation is owed - and a refusal recorded in the system would be
     * a disagreement with a manager written into the employee's own development record.
     */
    public <T> T agreeGoal(Long goalId, Function<PlanGoal, T> mapper) {
        PlanGoal goal = requireDevelopmentGoal(goalId, "agreed");
        authorization.require(Capability.AGREE_DEVELOPMENT_GOAL, subjectOf(goal));

        if (goal.isDraft()) {
            // Unreachable through the API, because a draft is invisible to the employee. Kept
            // so that an id lifted from somewhere else cannot agree a goal into existence.
            throw new ConflictApiException("That goal has not been submitted yet");
        }
        if (goal.isAgreed()) {
            throw new ConflictApiException("You have already agreed to that goal");
        }

        goal.agree();
        return mapper.apply(goal);
    }

    /**
     * Recording how an agreed goal is going (P-5.9).
     *
     * <p>Writes {@code progressNote} and never the goal's wording, which is what keeps
     * reporting progress from quietly restating the goal. Held by the employee and their
     * manager: section 8 asks for progress to be tracked and updated without saying by whom,
     * and a manager writing it up after a conversation is as ordinary as the employee typing it.
     *
     * <h2>On an improvement goal, the manager alone</h2>
     *
     * <p>A PIP runs for months against a fixed deadline and is judged at the end, so the
     * manager needs somewhere to record how it is actually going - otherwise the only writing
     * on the plan is the goal set on day one and the pass or fail at the end, and nothing in
     * between explains the outcome.
     *
     * <p>But the employee still writes nothing. An improvement plan is put <em>to</em> somebody
     * rather than agreed with them, so this takes {@link Capability#WRITE_IMPROVEMENT_PLAN} -
     * {@code DIRECT_MANAGER} and no {@code SELF} - rather than
     * {@link Capability#REPORT_GOAL_PROGRESS}, which carries both. That is the whole difference
     * between the growth track and the corrective track, and it is worth two capabilities.
     *
     * <p>The agreement gate below is a development concept and is skipped for a PIP goal. An
     * improvement goal has no agreement to wait for - it is live once the plan is co-signed,
     * and P-5.3 governs that a level up.
     */
    public <T> T reportProgress(Long goalId, String note, Function<PlanGoal, T> mapper) {
        PlanGoal goal = requireGoal(goalId);

        if (goal.isImprovementGoal()) {
            authorization.require(Capability.WRITE_IMPROVEMENT_PLAN, subjectOf(goal));
        } else {
            authorization.require(Capability.REPORT_GOAL_PROGRESS, subjectOf(goal));
            if (!goal.isAgreed()) {
                throw new ConflictApiException(
                        "Progress can be recorded once the goal has been agreed");
            }
        }

        if (goal.isComplete()) {
            throw new ConflictApiException(
                    "That goal was approved as complete; reopen it before recording more progress");
        }

        goal.setProgressNote(note);
        return mapper.apply(goal);
    }

    /** Removes a goal that should not have been there. The manager (P-5.9). */
    public void removeGoal(Long goalId) {
        PlanGoal goal = requireGoal(goalId);
        authorization.require(writeCapabilityFor(goal), subjectOf(goal));

        if (goal.isComplete()) {
            // Deleting approved progress would quietly rewrite the history the carry-over
            // pillar rests on. Reopen it first, deliberately, and then remove it.
            throw new ConflictApiException(
                    "That goal was approved as complete; reopen it before removing it");
        }
        if (goal.isImprovementGoal()) {
            goal.getImprovementPlan().removeGoal(goal);
        } else {
            goal.getPlan().removeGoal(goal);
        }
        goals.delete(goal);
    }

    // ================================================================= feature 16: the PIP

    /**
     * Opens an improvement plan and suspends the development plan (P-5.7).
     *
     * <p>Both halves happen in one transaction, because an employee on an improvement plan
     * whose development plan is still running is precisely the state the exclusivity invariant
     * forbids, and a two-step version would pass through it every time.
     *
     * <p>The deadline is supplied here and never again. There is no path that changes it: the
     * column is not updatable, the entity has no setter, and
     * {@link Capability#EXTEND_PIP_DEADLINE} names no actor who could try (P-5.5).
     *
     * <p>A second concurrent open is stopped by the database rather than by the check below.
     * {@code improvement_plan.active_user_id} is a generated column carrying a unique index, so
     * two requests that both see no active plan will still produce one row and one integrity
     * violation, which is translated here into the same 409 the check would have given.
     */
    public <T> T openImprovementPlan(Long userId, String consequenceClause, LocalDate deadline,
                                     Function<ImprovementPlan, T> mapper) {

        ReviewSubject subject = authorization.subject(userId);
        authorization.require(Capability.OPEN_IMPROVEMENT_PLAN, subject);

        if (deadline == null) {
            throw new ValidationApiException("An improvement plan needs a deadline, set once and fixed");
        }
        if (!deadline.isAfter(LocalDate.now(clock))) {
            // A deadline already in the past would be unmeetable on the day it was set, and
            // since nobody can extend it the plan could only ever fail.
            throw new ValidationApiException("An improvement plan's deadline must be in the future");
        }
        // Scenario section 5 puts this at step 7, after the rating is shared at step 6: the
        // improvement plan is where a completed review routes, not something that runs beside
        // one. Until the employee has been told an outcome there is nothing for a plan to
        // correct, and opening one first would present a judgment they have never heard.
        //
        // Released rather than merely set - a rating sitting with HR is a decision the employee
        // has not been given. Any cycle rather than the current one, because section 9 allows a
        // slipped development plan to be suspended in favour of a new improvement plan later,
        // which is not tied to the review that has just finished.
        //
        // 409 and not 403: the manager holds OPEN_IMPROVEMENT_PLAN on their report throughout,
        // and it is the absence of a completed review that refuses.
        if (!finalRatings.existsBySubjectIdAndReleasedAtIsNotNull(userId)) {
            throw new ConflictApiException(
                    "An improvement plan follows a completed review. Share this employee's"
                            + " final rating with them first, then open one.");
        }

        improvementPlans.findByUserIdAndStatus(userId, ImprovementStatus.ACTIVE)
                .ifPresent(existing -> {
                    throw new ConflictApiException(
                            "That employee is already on an improvement plan (P-5.7)");
                });

        ImprovementPlan plan = new ImprovementPlan(
                users.getReferenceById(userId),
                users.getReferenceById(currentUser.require().id()),
                deadline,
                consequenceClause);

        ImprovementPlan saved;
        try {
            saved = improvementPlans.saveAndFlush(plan);
        } catch (DataIntegrityViolationException ex) {
            // The unique index on the generated column. This is the branch that makes P-5.7 a
            // guarantee rather than a promise: it is reached only when two requests raced past
            // the check above, and it is the database that decided which one lost.
            throw new ConflictApiException(
                    "That employee is already on an improvement plan (P-5.7)");
        }

        DevelopmentPlan development = planFor(userId);
        development.setStatus(PlanStatus.SUSPENDED);
        development.setSuspendedAt(Instant.now());

        return mapper.apply(saved);
    }

    /** Adds a goal to an improvement plan. The manager's, never the employee's. */
    public <T> T addImprovementGoal(Long planId, String title, String detail, LocalDate targetDate,
                                    Function<PlanGoal, T> mapper) {
        ImprovementPlan plan = requireImprovementPlan(planId);
        authorization.require(Capability.WRITE_IMPROVEMENT_PLAN, subjectOf(plan));
        requireTitle(title);
        requireOpen(plan);

        PlanGoal goal = new PlanGoal(plan, title.trim(), detail, targetDate);
        plan.addGoal(goal);
        return mapper.apply(goals.save(goal));
    }

    /**
     * Writes the consequence clause (P-5.6).
     *
     * <p>Editable until the plan is co-signed and fixed afterwards. The clause is the thing the
     * employee is being asked to accept, so changing it after their signature would make the
     * signature meaningless.
     */
    public <T> T setConsequenceClause(Long planId, String text, Function<ImprovementPlan, T> mapper) {
        ImprovementPlan plan = requireImprovementPlan(planId);
        authorization.require(Capability.WRITE_IMPROVEMENT_PLAN, subjectOf(plan));
        requireOpen(plan);

        if (plan.isCosigned()) {
            throw new ConflictApiException(
                    "This plan was co-signed on " + plan.getCosignedAt()
                            + "; its consequence clause is now part of the agreement");
        }
        plan.setConsequenceClause(text);
        return mapper.apply(plan);
    }

    /**
     * HR co-signs (P-5.4), which is also what makes the plan visible to the employee (P-5.3).
     *
     * <p>Those two being the same event is the design. A manager cannot draft a PIP and confront
     * somebody with it: until HR have read it and put their name to it, the employee cannot see
     * it, and HR are exactly the people placed to notice a plan that should not have been
     * written.
     *
     * <p>Refused while the consequence clause is empty (P-5.6). A plan whose consequences are
     * unstated is a warning nobody agreed to, and co-signing one would certify that.
     */
    public <T> T cosign(Long planId, Function<ImprovementPlan, T> mapper) {
        ImprovementPlan plan = requireImprovementPlan(planId);
        authorization.require(Capability.COSIGN_IMPROVEMENT_PLAN, subjectOf(plan));
        requireOpen(plan);

        if (plan.isCosigned()) {
            throw new ConflictApiException("This plan has already been co-signed");
        }
        if (plan.getConsequenceClause() == null || plan.getConsequenceClause().isBlank()) {
            throw new ValidationApiException(
                    "This plan has no consequence clause and cannot be co-signed (P-5.6)");
        }
        plan.cosign(users.getReferenceById(currentUser.require().id()));
        return mapper.apply(plan);
    }

    /** Records who witnessed the meeting. HR's alone, like the co-signature (P-5.4). */
    public <T> T recordWitness(Long planId, String witnessName, Function<ImprovementPlan, T> mapper) {
        ImprovementPlan plan = requireImprovementPlan(planId);
        authorization.require(Capability.RECORD_WITNESS, subjectOf(plan));
        requireOpen(plan);

        if (witnessName == null || witnessName.isBlank()) {
            throw new ValidationApiException("A witness needs a name");
        }
        plan.recordWitness(witnessName.trim(), users.getReferenceById(currentUser.require().id()));
        return mapper.apply(plan);
    }

    /**
     * The endpoint that exists in order to be refused (P-5.5).
     *
     * <p>{@link Capability#EXTEND_PIP_DEADLINE} names no actor, so this denies everybody: the
     * manager who opened the plan, HR who co-signed it, the Super Admin. Building it and having
     * it refuse is not the same as leaving it unbuilt - an unbuilt endpoint is a rule nobody has
     * enforced, and the first person to need one would add it without the rule.
     */
    public void extendDeadline(Long planId, LocalDate newDeadline) {
        ImprovementPlan plan = requireImprovementPlan(planId);
        authorization.require(Capability.EXTEND_PIP_DEADLINE, subjectOf(plan));
        throw new IllegalStateException("unreachable: EXTEND_PIP_DEADLINE is permitted to nobody");
    }

    /**
     * Passes the plan and resumes the development plan (scenario section 5 step 9).
     *
     * <p>The <strong>same</strong> development plan row, with its goals and progress intact.
     * That is the carry-over pillar, and it works only because the PDP was never keyed to a
     * cycle: there is one row to resume rather than a copy to reconstruct.
     *
     * <p>Requires every goal approved. A plan passed with goals outstanding would make the
     * goals decorative, and it is the manager who approves each one (P-5.2), so nothing here
     * asks them to judge twice.
     */
    public <T> T pass(Long planId, Function<ImprovementPlan, T> mapper) {
        ImprovementPlan plan = closable(planId);

        List<PlanGoal> outstanding = plan.getGoals().stream().filter(g -> !g.isComplete()).toList();
        if (!outstanding.isEmpty()) {
            throw new ConflictApiException(
                    outstanding.size() + " goal(s) on this plan have not been approved as complete");
        }
        plan.close(ImprovementStatus.PASSED);
        resumeDevelopmentPlan(plan.getUser().getId());
        return mapper.apply(plan);
    }

    /**
     * Fails the plan once its deadline has passed.
     *
     * <p>The deadline check is the literal reading of "deadlines missed, plan failed": a plan
     * cannot be failed while there is still time to meet it, which is the protection the fixed
     * deadline is there to give.
     *
     * <p>The development plan resumes here too, and that is a judgment call rather than a rule
     * from the documents. Section 5 step 9 only says a <em>passed</em> plan resumes it. Leaving
     * it suspended would leave the employee holding no active plan at all, which contradicts
     * section 8's universal development plan. A failed PIP records an outcome; it does not end
     * somebody's development.
     */
    public <T> T fail(Long planId, Function<ImprovementPlan, T> mapper) {
        ImprovementPlan plan = closable(planId);

        if (!LocalDate.now(clock).isAfter(plan.getDeadline())) {
            throw new ConflictApiException(
                    "This plan's deadline is " + plan.getDeadline()
                            + " and has not passed; it cannot be failed yet");
        }
        plan.close(ImprovementStatus.FAILED);
        resumeDevelopmentPlan(plan.getUser().getId());
        return mapper.apply(plan);
    }

    // ================================================================= reading the PIP

    /**
     * The employee's own improvement plan, behind the co-sign gate (P-5.3).
     *
     * <p>"No plan" and "a plan exists but has not been co-signed" produce the same response, in
     * the same way an unreleased rating does. Distinguishing them would tell the employee a plan
     * had been drafted about them, which is what the gate withholds.
     *
     * <p>The mapper runs <strong>inside</strong> this transaction, like every other read here.
     * It used to return the entity for the controller to convert, which threw
     * {@code LazyInitializationException} on a closed session the moment the view read the
     * subject's or the opener's name - a 500 on the employee's own plan screen. Every test
     * passed, because a {@code @Transactional} test holds the session open for the whole method
     * and the proxy still resolves.
     */
    @Transactional(readOnly = true)
    public <T> Optional<T> myImprovementPlan(Function<ImprovementPlan, T> mapper) {
        Long callerId = currentUser.require().id();
        ReviewSubject subject = authorization.subject(callerId);

        return improvementPlans.findByUserIdAndStatus(callerId, ImprovementStatus.ACTIVE)
                .filter(plan -> authorization.decide(
                                Capability.READ_IMPROVEMENT_PLAN, subject,
                                ArtifactState.cosigned(plan.isCosigned()))
                        .permitted())
                .map(mapper);
    }

    /**
     * Somebody's active improvement plan, for their manager or HR-in-scope.
     *
     * <p>The state gate constrains the subject only, so a manager and HR see the plan before it
     * is co-signed - somebody has to draft it, and somebody has to review it before signing.
     */
    @Transactional(readOnly = true)
    public <T> T readImprovementPlan(Long userId, Function<ImprovementPlan, T> mapper) {
        ReviewSubject subject = authorization.subject(userId);
        ImprovementPlan plan = improvementPlans.findByUserIdAndStatus(userId, ImprovementStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundApiException("No active improvement plan"));

        authorization.require(Capability.READ_IMPROVEMENT_PLAN, subject,
                ArtifactState.cosigned(plan.isCosigned()));
        return mapper.apply(plan);
    }

    /** Every improvement plan a person has held. Closed ones included; that is the history. */
    @Transactional(readOnly = true)
    public <T> List<T> improvementPlanHistory(Long userId, Function<ImprovementPlan, T> mapper) {
        ReviewSubject subject = authorization.subject(userId);

        // The door check asks only whether this caller may read this person's plans at all.
        // The co-sign gate is a fact about each plan rather than about the person, so it is
        // applied per row below - which is also why cosigned(true) here is not a bypass: a
        // subject who reaches this line still receives nothing they were never shown.
        authorization.require(Capability.READ_IMPROVEMENT_PLAN, subject, ArtifactState.cosigned(true));

        return improvementPlans.findByUserIdOrderByOpenedAtDesc(userId).stream()
                // A closed plan the employee never saw stays unseen: the gate is about whether
                // this plan was ever put to them, not about whether it is still running.
                .filter(ImprovementPlan::isCosigned)
                .map(mapper)
                .toList();
    }

    /**
     * The running improvement plans an HR user oversees.
     *
     * <p>Added because HR had no way to <em>find</em> a plan awaiting their co-signature. Every
     * other route to one starts from a person, and the review list only names people under
     * review in a cycle - while an improvement plan is opened whenever a manager decides to,
     * cycle or no cycle. So the person HR must act on was, in practice, undiscoverable.
     *
     * <p>The scope comes from {@link Capability#COSIGN_IMPROVEMENT_PLAN}, whose only ground is
     * {@code HR_IN_SCOPE}. That is not a shortcut: it means the list is exactly "the plans you
     * could act on", derived from the capability table rather than from a rule written here. A
     * manager gets no reports and no self, so the same call yields them nothing - they reach
     * their own reports' plans through the person, as they already did.
     *
     * <p>Unlike the per-person read, this is <strong>not</strong> gated on co-signature. An
     * uncosigned plan is invisible to its subject (P-5.3); it must be visible to HR, because
     * co-signing it is their job and a plan they cannot see is one they cannot review.
     */
    @Transactional(readOnly = true)
    public <T> List<T> improvementPlansInScope(Function<ImprovementPlan, T> mapper) {
        SubjectScope scope = authorization.subjectScopeFor(Capability.COSIGN_IMPROVEMENT_PLAN);

        if (scope.hrDepartmentIds().isEmpty()) {
            // No query at all. An IN clause over an empty set is a SQL error in some dialects
            // and a full scan in others, and neither is a good way to express "nothing".
            return List.of();
        }

        return improvementPlans
                .findInDepartments(ImprovementStatus.ACTIVE, scope.hrDepartmentIds(), scope.callerId())
                .stream()
                .map(mapper)
                .toList();
    }

    /**
     * When each of the caller's direct reports last had progress recorded on a goal.
     *
     * <p>Exists because nothing else tells a manager. The employee writes a progress note and
     * it lands on a page nobody opens without a reason; no email goes out in Sprint 1. This is
     * the reason.
     *
     * <p>Scoped to {@code directReportIds} and nothing else. HR read plans (P-5.1) but do not
     * chase them, and handing an HR user a feed of every plan movement in their departments
     * would be a different feature with a different justification - so the HR half of the scope
     * is deliberately unused here rather than passed through because it happened to be
     * available.
     *
     * @return report id to the time progress was last recorded, absent where there is none
     */
    @Transactional(readOnly = true)
    public Map<Long, Instant> latestGoalProgressForMyReports() {
        SubjectScope scope = authorization.subjectScopeFor(Capability.READ_DEVELOPMENT_PLAN);
        Set<Long> reports = scope.directReportIds();

        if (reports.isEmpty()) {
            // No query at all. An IN clause over an empty set is a SQL error in some dialects
            // and a full scan in others, and neither is a good way to express "nothing".
            return Map.of();
        }

        Map<Long, Instant> latest = new HashMap<>();
        for (Object[] row : goals.findLatestProgressFor(reports)) {
            latest.put((Long) row[0], (Instant) row[1]);
        }
        return latest;
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
        return authorization.subject(goal.owner().getId());
    }

    private ReviewSubject subjectOf(ImprovementPlan plan) {
        return authorization.subject(plan.getUser().getId());
    }

    /**
     * Which capability governs writing this goal.
     *
     * <p>The two plan types differ here and nowhere else in the goal machinery. A development
     * goal is written with the employee, who holds {@code WRITE_DEVELOPMENT_PLAN}; an
     * improvement goal is put to them, and they hold nothing. Approval is {@code APPROVE_GOAL}
     * for both, because P-5.2 gives it to {@code mgr(S)} on either plan in the same breath.
     */
    /**
     * A development goal, refusing an improvement goal by the same id.
     *
     * <p>The agreement lifecycle belongs to development goals alone. Reaching one of these
     * operations with a PIP goal's id is a 403 rather than a 400, for the same reason every
     * unknown id is: "no such goal" and "not that kind of goal" must not be distinguishable by
     * probing, and a PIP is the last thing that should be discoverable that way.
     */
    private PlanGoal requireDevelopmentGoal(Long goalId, String verb) {
        PlanGoal goal = requireGoal(goalId);
        if (goal.isImprovementGoal()) {
            throw new AccessDeniedApiException(
                    "An improvement goal is not " + verb + " by the employee (P-5.9)");
        }
        return goal;
    }

    private static Capability writeCapabilityFor(PlanGoal goal) {
        return goal.isImprovementGoal()
                ? Capability.WRITE_IMPROVEMENT_PLAN
                : Capability.WRITE_DEVELOPMENT_PLAN;
    }

    private ImprovementPlan requireImprovementPlan(Long planId) {
        // 403 for an unknown id, as everywhere: "no such plan" and "not your plan" must not be
        // distinguishable, and a PIP is the last thing that should be discoverable by probing.
        return improvementPlans.findWithGoalsById(planId)
                .orElseThrow(() -> new AccessDeniedApiException("P-0.5: no readable plan " + planId));
    }

    private static void requireOpen(ImprovementPlan plan) {
        if (!plan.isActive()) {
            throw new ConflictApiException(
                    "This improvement plan is " + plan.getStatus() + " and is now part of the record");
        }
    }

    /**
     * Loads a plan that is about to be passed or failed.
     *
     * <p>Requires a co-signature. A plan that was never co-signed was never visible to the
     * employee (P-5.3), so closing it either way would record an outcome for something they
     * were never told about.
     */
    private ImprovementPlan closable(Long planId) {
        ImprovementPlan plan = requireImprovementPlan(planId);
        authorization.require(Capability.CLOSE_IMPROVEMENT_PLAN, subjectOf(plan));
        requireOpen(plan);

        if (!plan.isCosigned()) {
            throw new ConflictApiException(
                    "This plan has not been co-signed, so the employee has never seen it;"
                            + " it cannot be passed or failed");
        }
        return plan;
    }

    /**
     * Brings the development plan back (scenario section 5 step 9).
     *
     * <p>The same row, with its goals and their progress untouched. Nothing is copied and
     * nothing is recreated, which is the whole of the carry-over pillar and the reason the PDP
     * was never keyed to a cycle.
     */
    private void resumeDevelopmentPlan(Long userId) {
        DevelopmentPlan development = planFor(userId);
        development.setStatus(PlanStatus.ACTIVE);
        development.setSuspendedAt(null);
    }

    private static void requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new ValidationApiException("A goal needs a title");
        }
    }
}
