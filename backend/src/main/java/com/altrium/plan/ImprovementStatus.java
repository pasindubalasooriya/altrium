package com.altrium.plan;

/**
 * The outcome of an improvement plan.
 *
 * <p>Only {@link PlanService} writes it (P-5.8), and only from ACTIVE. A closed plan stays
 * closed: reopening it would mean the record of what happened depends on who looked last, and
 * a PIP is the one artifact in the system with consequences attached to it.
 */
public enum ImprovementStatus {

    /** Running. At most one per employee, guaranteed by a unique index (P-5.7). */
    ACTIVE,

    /** Goals met and approved by {@code mgr(S)}. Resumes the suspended development plan. */
    PASSED,

    /** The deadline passed with goals outstanding. */
    FAILED;

    public boolean isClosed() {
        return this != ACTIVE;
    }
}
