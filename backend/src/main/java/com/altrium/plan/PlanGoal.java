package com.altrium.plan;

import com.altrium.org.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A freeform development goal with a movable target date (P-5.2, P-5.5).
 *
 * <p>Two properties are worth separating, because feature 16 will invert one of them and keep
 * the other:
 *
 * <ul>
 *   <li><b>The target date moves.</b> A development goal that slipped because the quarter went
 *       differently is a goal to reschedule, not a failure to record. PIP deadlines are the
 *       opposite and are immutable once set, which is the whole difference in weight between
 *       the two instruments.</li>
 *   <li><b>Only {@code mgr(S)} approves completion.</b> The employee reports progress in the
 *       goal's text; the manager is the one who says it is done, and is recorded on the row
 *       for having said so.</li>
 * </ul>
 */
@Entity
@Table(name = "plan_goal")
public class PlanGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Exactly one of these two is set, which the database also insists on.
     *
     * <p>V6 extended this table rather than adding an {@code improvement_goal} alongside it,
     * because P-5.2 gives goal approval to {@code mgr(S)} on both plan types in the same
     * breath. Two tables would have meant two copies of the approval rule, and the second copy
     * is the one that drifts.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "development_plan_id")
    private DevelopmentPlan plan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "improvement_plan_id")
    private ImprovementPlan improvementPlan;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    /** Nullable: a goal can be agreed before a date for it is. */
    @Column(name = "target_date")
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GoalStatus status = GoalStatus.OPEN;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Where the goal stands between manager and employee. Null on an improvement goal, which
     * has no agreement step (see {@link GoalAgreement}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "agreement", length = 16)
    private GoalAgreement agreement;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "agreed_at")
    private Instant agreedAt;

    /**
     * The employee's own account of how the goal is going.
     *
     * <p>Kept apart from {@link #detail}, which belongs to the manager and is fixed once the
     * goal is agreed. Sharing one field would let progress reporting overwrite the goal that
     * was agreed to, which is the exact thing fixing the wording exists to prevent.
     */
    @Column(name = "progress_note", columnDefinition = "TEXT")
    private String progressNote;

    /** Who approved it. A completed goal with no approver would be one that completed itself. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private AppUser approvedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected PlanGoal() {
        // for JPA
    }

    public PlanGoal(DevelopmentPlan plan, String title, String detail, LocalDate targetDate) {
        this.plan = plan;
        this.title = title;
        this.detail = detail;
        this.targetDate = targetDate;
        // Drafted, never submitted, so a half-written goal is not on the employee's plan the
        // instant their manager starts typing it.
        this.agreement = GoalAgreement.DRAFT;
    }

    public PlanGoal(ImprovementPlan improvementPlan, String title, String detail, LocalDate targetDate) {
        this.improvementPlan = improvementPlan;
        this.title = title;
        this.detail = detail;
        this.targetDate = targetDate;
    }

    public Long getId() {
        return id;
    }

    public DevelopmentPlan getPlan() {
        return plan;
    }

    public ImprovementPlan getImprovementPlan() {
        return improvementPlan;
    }

    public boolean isImprovementGoal() {
        return improvementPlan != null;
    }

    public GoalAgreement getAgreement() {
        return agreement;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getAgreedAt() {
        return agreedAt;
    }

    public String getProgressNote() {
        return progressNote;
    }

    public void setProgressNote(String progressNote) {
        this.progressNote = progressNote;
    }

    /** Submitted to the employee, who can now see it and agree to it. */
    public void submit() {
        this.agreement = GoalAgreement.PENDING;
        this.submittedAt = Instant.now();
    }

    /** Agreed by the employee. The wording is fixed from here. */
    public void agree() {
        this.agreement = GoalAgreement.AGREED;
        this.agreedAt = Instant.now();
    }

    public boolean isDraft() {
        return agreement == GoalAgreement.DRAFT;
    }

    public boolean isAgreed() {
        return agreement == GoalAgreement.AGREED;
    }

    /**
     * Whether the employee whose plan this is may see it.
     *
     * <p>An improvement goal has no agreement and is governed by the plan's co-signature
     * instead (P-5.3), so it is visible here and gated a level up.
     */
    public boolean isVisibleToSubject() {
        return !isDraft();
    }

    /**
     * Whose goal this is, whichever plan holds it.
     *
     * <p>Every authorization decision about a goal is really a decision about this person, so
     * resolving it in one place keeps the two plan types from growing two different answers.
     */
    public AppUser owner() {
        return improvementPlan != null ? improvementPlan.getUser() : plan.getUser();
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    /** Moving an existing date is {@code mgr(S)}'s (P-5.5), which {@link PlanService} enforces. */
    public void setTargetDate(LocalDate targetDate) {
        this.targetDate = targetDate;
    }

    public GoalStatus getStatus() {
        return status;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public AppUser getApprovedBy() {
        return approvedBy;
    }

    public boolean isComplete() {
        return status == GoalStatus.COMPLETE;
    }

    /**
     * Approval and its evidence, set together so a goal cannot be complete without a record of
     * who said so - which the database also insists on.
     */
    void approve(AppUser manager) {
        this.status = GoalStatus.COMPLETE;
        this.completedAt = Instant.now();
        this.approvedBy = manager;
    }

    void reopen() {
        this.status = GoalStatus.OPEN;
        this.completedAt = null;
        this.approvedBy = null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
