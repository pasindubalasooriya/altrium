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
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A performance improvement plan (feature 16).
 *
 * <p>The one artifact in Altrium with consequences attached, and its shape is deliberately
 * heavier than the development plan's in three specific ways:
 *
 * <ul>
 *   <li><b>The consequence clause</b> must be written and non-empty before anyone can co-sign
 *       (P-5.6). A plan whose consequences are unstated is a warning nobody agreed to.</li>
 *   <li><b>The co-signature</b> is HR's, never the manager's (P-5.4), and until it exists the
 *       employee cannot see the plan at all (P-5.3). Those two facts together are what stop a
 *       manager drafting a PIP and confronting somebody with it unreviewed.</li>
 *   <li><b>The deadline</b> is set once and moves for nobody (P-5.5), in direct contrast to a
 *       development goal's target date, which its manager may reschedule freely.</li>
 * </ul>
 *
 * <p>Exclusivity with the development plan (P-5.7) is guaranteed by the database, not here: the
 * {@code active_user_id} generated column carries a unique index, so a second ACTIVE plan for
 * one person cannot be inserted whatever the service believes.
 */
@Entity
@Table(name = "improvement_plan")
public class ImprovementPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ImprovementStatus status = ImprovementStatus.ACTIVE;

    /**
     * Guards the close transitions. Pass and fail both read the plan, check its state and write
     * it back; without this, two concurrent closes would both see ACTIVE and both proceed, and
     * one would resume a development plan the other had already resumed.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "opened_by", nullable = false)
    private AppUser openedBy;

    @Column(name = "opened_at", insertable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "consequence_clause", columnDefinition = "TEXT")
    private String consequenceClause;

    /** {@code updatable = false} so no code path can move it, whatever it intends (P-5.5). */
    @Column(name = "deadline", nullable = false, updatable = false)
    private LocalDate deadline;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cosigned_by")
    private AppUser cosignedBy;

    @Column(name = "cosigned_at")
    private Instant cosignedAt;

    @Column(name = "witness_name", length = 200)
    private String witnessName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "witness_recorded_by")
    private AppUser witnessRecordedBy;

    @Column(name = "witness_recorded_at")
    private Instant witnessRecordedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @OneToMany(mappedBy = "improvementPlan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PlanGoal> goals = new ArrayList<>();

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected ImprovementPlan() {
        // for JPA
    }

    public ImprovementPlan(AppUser user, AppUser openedBy, LocalDate deadline, String consequenceClause) {
        this.user = user;
        this.openedBy = openedBy;
        this.deadline = deadline;
        this.consequenceClause = consequenceClause;
    }

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public ImprovementStatus getStatus() {
        return status;
    }

    public AppUser getOpenedBy() {
        return openedBy;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public String getConsequenceClause() {
        return consequenceClause;
    }

    public void setConsequenceClause(String consequenceClause) {
        this.consequenceClause = consequenceClause;
    }

    /** No setter. The column is not updatable either, so the rule holds twice over (P-5.5). */
    public LocalDate getDeadline() {
        return deadline;
    }

    public AppUser getCosignedBy() {
        return cosignedBy;
    }

    public Instant getCosignedAt() {
        return cosignedAt;
    }

    public String getWitnessName() {
        return witnessName;
    }

    public AppUser getWitnessRecordedBy() {
        return witnessRecordedBy;
    }

    public Instant getWitnessRecordedAt() {
        return witnessRecordedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
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

    /** P-5.3: the employee sees nothing until this is true. */
    public boolean isCosigned() {
        return cosignedAt != null;
    }

    public boolean isActive() {
        return status == ImprovementStatus.ACTIVE;
    }

    public boolean hasWitness() {
        return witnessName != null;
    }

    /** Set together, so a signature can never exist without a time or an author. */
    void cosign(AppUser hrUser) {
        this.cosignedBy = hrUser;
        this.cosignedAt = Instant.now();
    }

    void recordWitness(String name, AppUser hrUser) {
        this.witnessName = name;
        this.witnessRecordedBy = hrUser;
        this.witnessRecordedAt = Instant.now();
    }

    void close(ImprovementStatus outcome) {
        this.status = outcome;
        this.closedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
