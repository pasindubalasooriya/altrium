package com.altrium.review;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One quadrimester's review round.
 *
 * <p>A configured quadrimester and the cycle it becomes are the same record. Keeping them
 * apart would let "is this cycle open?" be answered from two places, and P-6.2 - the start
 * date is locked once {@code openedAt} is set - needs exactly one.
 */
@Entity
@Table(name = "review_cycle")
public class ReviewCycle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "financial_year", nullable = false)
    private int financialYear;

    /**
     * 1, 2 or 3. Stored as TINYINT, which is the honest width for a value with three legal
     * states, and mapped explicitly so schema validation agrees - an {@code int} field over
     * a TINYINT column fails validation, and widening the column to suit the Java type would
     * be letting the mapping dictate the schema.
     */
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "quadrimester_no", nullable = false)
    private int quadrimesterNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CycleStatus status = CycleStatus.CONFIGURED;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /**
     * Stamped by the sweep. Its presence is what makes the sweep idempotent - a second run
     * selects on {@code opened_at IS NULL} and finds nothing to do (P-6.4).
     */
    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected ReviewCycle() {
        // for JPA
    }

    public ReviewCycle(int financialYear, int quadrimesterNo, LocalDate startDate, LocalDate endDate) {
        this.financialYear = financialYear;
        this.quadrimesterNo = quadrimesterNo;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public Long getId() {
        return id;
    }

    public int getFinancialYear() {
        return financialYear;
    }

    public int getQuadrimesterNo() {
        return quadrimesterNo;
    }

    public CycleStatus getStatus() {
        return status;
    }

    public void setStatus(CycleStatus status) {
        this.status = status;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(AppUser createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** True once the sweep has fired. After this the start date may not move (P-6.2). */
    public boolean isOpened() {
        return openedAt != null;
    }

    /** e.g. {@code FY2026 Q2}, for display and log lines. */
    public String label() {
        return "FY" + financialYear + " Q" + quadrimesterNo;
    }
}
