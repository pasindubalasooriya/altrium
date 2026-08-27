package com.altrium.plan;

/**
 * Where a development goal stands between the manager who wrote it and the employee it is for.
 *
 * <p>A second axis from {@link GoalStatus}, and deliberately not folded into it. {@code status}
 * answers "has the manager approved this as finished?" (P-5.2); this answers "has the employee
 * agreed to it?". A goal is normally {@code AGREED} and {@code OPEN} for most of its life, and
 * collapsing the two would make that ordinary state unrepresentable.
 *
 * <p>Null on an improvement goal, and that absence is the rule. A PIP is put <em>to</em> an
 * employee rather than agreed with them, and an employee who could withhold agreement from a
 * PIP goal could stall the plan meant to correct their performance.
 */
public enum GoalAgreement {

    /**
     * The manager is still writing it. Invisible to the employee - not merely unagreed, but
     * absent from their plan entirely, because a goal still being drafted is not yet a goal
     * anybody has been asked to accept.
     */
    DRAFT,

    /** Submitted. The employee can see it and agree to it, and nothing else has changed. */
    PENDING,

    /**
     * The employee has agreed. The wording is fixed from this moment, and progress reporting
     * opens.
     *
     * <p>Fixed is what makes the agreement mean anything: a goal whose text could be rewritten
     * afterwards is not one that was agreed to, and the employee would have no way of knowing
     * it had changed. The target date still moves under {@code MOVE_GOAL_TARGET_DATE}, which
     * section 8 asks for in as many words.
     */
    AGREED
}
