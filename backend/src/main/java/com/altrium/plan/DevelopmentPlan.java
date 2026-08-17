package com.altrium.plan;

import com.altrium.org.AppUser;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One employee's development plan (feature 15, P-5.1).
 *
 * <p><strong>One row per person, for as long as they are here.</strong> Not one per cycle. That
 * is what makes scenario section 8's "from the moment they join" and section 5 step 9's "resumes
 * the suspended development plan with its goals and progress intact" the same fact rather than
 * two features. A per-cycle plan would have turned carry-over into a copying operation, and a
 * copy is exactly where progress gets lost.
 *
 * <p>HR read this and never write it (P-5.1): {@code READ_DEVELOPMENT_PLAN} lists
 * {@code HR_IN_SCOPE} and {@code WRITE_DEVELOPMENT_PLAN} does not. Development is between the
 * employee and their manager; HR oversee that it is happening.
 *
 * <p>Leadership hold no plan at all (P-7.2). Nothing here enforces that, because nothing here
 * needs to: every plan capability is an artifact capability, and the authorization service
 * refuses those for a Leadership subject at step 2, before any of this is reached.
 */
@Entity
@Table(name = "development_plan")
public class DevelopmentPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PlanStatus status = PlanStatus.ACTIVE;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    /**
     * Cascaded because a goal has no life outside its plan.
     *
     * <p>Safe to map as a collection here, unlike anywhere on the review side: a plan is
     * fetched one at a time for one person, never as a page, so there is no pagination for an
     * eager collection to force into memory.
     *
     * <p>{@code @OrderBy} is deliberately absent. It only takes effect when the collection is
     * loaded from the database, so a goal added earlier in the same transaction is already in
     * the session and arrives in insertion order - which would make the response sorted or not
     * depending on what else the request had done. The sort belongs to the mapper, where it
     * happens every time.
     */
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PlanGoal> goals = new ArrayList<>();

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected DevelopmentPlan() {
        // for JPA
    }

    public DevelopmentPlan(AppUser user) {
        this.user = user;
    }

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public PlanStatus getStatus() {
        return status;
    }

    /** Written by {@link PlanService} alone (P-5.8). */
    void setStatus(PlanStatus status) {
        this.status = status;
    }

    public Instant getSuspendedAt() {
        return suspendedAt;
    }

    void setSuspendedAt(Instant suspendedAt) {
        this.suspendedAt = suspendedAt;
    }

    public List<PlanGoal> getGoals() {
        return goals;
    }

    public void addGoal(PlanGoal goal) {
        goals.add(goal);
    }

    public void removeGoal(PlanGoal goal) {
        goals.remove(goal);
    }

    public boolean isActive() {
        return status == PlanStatus.ACTIVE;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
