package com.altrium.plan;

/**
 * Whether a goal has been approved as complete.
 *
 * <p>Two states rather than three. There is no "employee says it is done, awaiting approval",
 * because that state would need somebody to own it and P-5.2 gives completion to {@code mgr(S)}
 * alone - a self-claimed completion sitting in the record would read like progress the manager
 * had agreed to.
 */
public enum GoalStatus {

    OPEN,

    /** Approved by {@code mgr(S)}, who is recorded on the row (P-5.2). */
    COMPLETE
}
