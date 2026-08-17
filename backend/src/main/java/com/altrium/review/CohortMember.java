package com.altrium.review;

import com.altrium.org.AppUser;
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
 * One employee's standing cohort assignment.
 *
 * <p>At most one per person, enforced by a unique key on {@code user_id} alone. That is what
 * makes scenario section 4's "assessed once per year in a fixed quadrimester" a guarantee of
 * the schema rather than a promise of the service: two rows for one person would either
 * review them twice in a year or take them into one intake twice.
 */
@Entity
@Table(name = "cohort_member")
public class CohortMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cohort_id", nullable = false)
    private Cohort cohort;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "added_at", insertable = false, updatable = false)
    private Instant addedAt;

    protected CohortMember() {
        // for JPA
    }

    public CohortMember(Cohort cohort, AppUser user) {
        this.cohort = cohort;
        this.user = user;
    }

    public Long getId() {
        return id;
    }

    public Cohort getCohort() {
        return cohort;
    }

    /** Moving somebody between cohorts is this, not a delete and an insert (see the schema). */
    public void setCohort(Cohort cohort) {
        this.cohort = cohort;
    }

    public AppUser getUser() {
        return user;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
