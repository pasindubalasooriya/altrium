package com.altrium.review;

import com.altrium.org.AppUser;
import com.altrium.org.Department;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A person under review in a given cycle — the row that makes "the reviews I may see" a
 * query rather than a computation.
 *
 * <p>It is a snapshot taken when the cycle opens, and that is the point. The department is
 * copied here rather than read live from {@code app_user}, so HR scoping asks which
 * department the review <em>was</em> in. Someone who transfers in March does not
 * retroactively move last quarter's review into their new department, and the HR user who
 * legitimately oversaw it does not silently lose it.
 *
 * <p>The row also survives deactivation (P-0.7): the review happened, and whoever could read
 * it still can.
 */
@Entity
@Table(name = "cycle_participant")
public class CycleParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    private ReviewCycle cycle;

    /**
     * The reviewee. Named {@code subject} in every artifact so one Specification can scope
     * them all identically (P-0.3) — a table whose column were named differently would need
     * its own predicate, and that is where the divergence starts.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private AppUser subject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected CycleParticipant() {
        // for JPA
    }

    public CycleParticipant(ReviewCycle cycle, AppUser subject, Department department) {
        this.cycle = cycle;
        this.subject = subject;
        this.department = department;
    }

    public Long getId() {
        return id;
    }

    public ReviewCycle getCycle() {
        return cycle;
    }

    public AppUser getSubject() {
        return subject;
    }

    public Department getDepartment() {
        return department;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
