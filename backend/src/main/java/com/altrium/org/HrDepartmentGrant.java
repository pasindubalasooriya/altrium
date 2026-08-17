package com.altrium.org;

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
 * One HR user's permission to operate in one department, granted by the Super Admin
 * (P-2.1, P-9.2).
 *
 * <p>{@code explicitGrant} is the HR Head mechanism (P-2.4). An ordinary HR user is blocked
 * in their own department (P-2.3); this flag lifts that block for this pairing only.
 *
 * <p>It does <strong>not</strong> lift the own-review block (P-2.2). That rule is absolute,
 * has no override, and is enforced above this table - an HR Head with an explicit grant over
 * their own department still cannot reach their own review. Checking the override before the
 * own-review block is precisely the ordering mistake P-0.6 exists to prevent.
 */
@Entity
@Table(name = "hr_department_grant")
public class HrDepartmentGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hr_user_id", nullable = false)
    private AppUser hrUser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @Column(name = "explicit_grant", nullable = false)
    private boolean explicitGrant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by")
    private AppUser grantedBy;

    @Column(name = "granted_at", insertable = false, updatable = false)
    private Instant grantedAt;

    protected HrDepartmentGrant() {
        // for JPA
    }

    public HrDepartmentGrant(AppUser hrUser, Department department, boolean explicitGrant, AppUser grantedBy) {
        this.hrUser = hrUser;
        this.department = department;
        this.explicitGrant = explicitGrant;
        this.grantedBy = grantedBy;
    }

    public Long getId() {
        return id;
    }

    public AppUser getHrUser() {
        return hrUser;
    }

    public Department getDepartment() {
        return department;
    }

    public boolean isExplicitGrant() {
        return explicitGrant;
    }

    public void setExplicitGrant(boolean explicitGrant) {
        this.explicitGrant = explicitGrant;
    }

    public AppUser getGrantedBy() {
        return grantedBy;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }
}
