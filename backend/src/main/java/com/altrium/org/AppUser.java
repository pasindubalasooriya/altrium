package com.altrium.org;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * A person in the organisation.
 *
 * <p>This entity is the backbone of the authorization model. Three of its columns carry
 * nearly all of the weight:
 * <ul>
 *   <li>{@code manager} - the single reporting line. {@code isManagerOf(A,S)} is true only
 *       when {@code S.manager == A} (P-1.1): direct reports only, never transitive.</li>
 *   <li>{@code department} - the unit of HR scoping (P-2.1, P-2.3).</li>
 *   <li>{@code active} - soft delete (P-0.7). Deactivated users are excluded from peer
 *       selection, cohort intake and newly opened cycles, but are never removed, because
 *       deleting them would destroy the history and carry-over pillar.</li>
 * </ul>
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The Asgardeo {@code sub} claim. The only link between a validated JWT and a row here,
     * so every authorization decision begins by resolving it.
     */
    @Column(name = "asgardeo_subject", nullable = false, unique = true)
    private String asgardeoSubject;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    /** Null for Leadership, who sit above the department structure. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    /**
     * Null only at the top of the chain. Loop rejection is enforced in the service by
     * walking up the chain (P-1.4) - the database cannot express it, and without the check
     * the direct-reports query never terminates.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private AppUser manager;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * Additive roles (P-0.1).
     *
     * <p>Asgardeo remains the system of record for provisioning, but these rows are what the
     * authorization layer actually reads, for two reasons. First, it must reason about
     * <em>other</em> users' roles - P-1.5 and P-7.2 forbid creating any review or plan
     * artifact for a Leadership member, and the caller's token cannot answer that about
     * someone else. Second, resolving the caller's own roles per request rather than trusting
     * claims minted at login is the same principle P-2.5 imposes on HR grants: a change must
     * apply on the very next request, and a JWT keeps its claims until it expires.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    // Batched so listing a page of users costs a handful of queries rather than one per row.
    // A join fetch is not an option here: it would force Hibernate to paginate in memory,
    // which breaks the rule that nothing may be hardcoded to organisational size.
    @BatchSize(size = 50)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected AppUser() {
        // for JPA
    }

    public AppUser(String asgardeoSubject, String email, String fullName) {
        this.asgardeoSubject = asgardeoSubject;
        this.email = email;
        this.fullName = fullName;
    }

    public Long getId() {
        return id;
    }

    public String getAsgardeoSubject() {
        return asgardeoSubject;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Department getDepartment() {
        return department;
    }

    public void setDepartment(Department department) {
        this.department = department;
    }

    public AppUser getManager() {
        return manager;
    }

    public void setManager(AppUser manager) {
        this.manager = manager;
    }

    public boolean isActive() {
        return active;
    }

    /** Soft delete (P-0.7): never remove the row. */
    public void setActive(boolean active) {
        this.active = active;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
