package com.altrium.plan;

/**
 * The states a development plan can be in.
 *
 * <p>Only {@link PlanService} writes this (P-5.8). A status field written from two places is a
 * state machine with no single definition, and the plan machine is the one the carry-over
 * pillar rests on.
 */
public enum PlanStatus {

    /** The normal state. Every employee below Leadership holds an active plan (scenario section 8). */
    ACTIVE,

    /**
     * Set aside while an improvement plan runs (P-5.7).
     *
     * <p>Suspension is not deletion and not archival: the row keeps its goals and their
     * progress, and a passed PIP resumes <em>this</em> row rather than creating a new one. That
     * is the mechanism behind carry-over, and it is why the plan is not keyed to a cycle.
     *
     * <p>Nothing in feature 15 sets this. It arrives with the improvement plan, and belongs to
     * {@code PlanService} when it does.
     */
    SUSPENDED
}
