package com.altrium.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A named group of employees attached to a quadrimester (scenario section 4).
 *
 * <p>Distinct from {@link CycleParticipant}, and the distinction matters. This is standing
 * configuration the Super Admin maintains and the sweep reads; a participant row is what one
 * intake produced and is never read back as configuration. Collapsing the two would mean
 * deriving next year's cohort from last year's participants, which would make an intake
 * mistake permanent and unfixable.
 */
@Entity
@Table(name = "cohort")
public class Cohort {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /**
     * 1, 2 or 3, or null when the cohort is attached to no quadrimester.
     *
     * <p>Null is a real state, not a gap in the data: scenario section 4 lets the Super Admin
     * remove a cohort from a quadrimester, and a detached cohort is simply never picked up by
     * the sweep. Expressing that as the absence of an attachment rather than as a separate
     * enabled flag keeps one answer to "will this group be reviewed in Q2?".
     */
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "quadrimester_no")
    private Integer quadrimesterNo;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected Cohort() {
        // for JPA
    }

    public Cohort(String name, Integer quadrimesterNo) {
        this.name = name;
        this.quadrimesterNo = quadrimesterNo;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getQuadrimesterNo() {
        return quadrimesterNo;
    }

    public void setQuadrimesterNo(Integer quadrimesterNo) {
        this.quadrimesterNo = quadrimesterNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Whether the sweep will pick this cohort up for the given quadrimester. */
    public boolean isAttached() {
        return quadrimesterNo != null;
    }
}
