package com.altrium.auth;

/**
 * The reason an action was permitted - never a role, always a relationship or a scope.
 *
 * <p>Recording <em>why</em> rather than just <em>whether</em> is what makes the model
 * auditable. "Permitted" tells you nothing; "permitted as the subject's direct manager"
 * can be checked against the policy list.
 *
 * <p>The set is deliberately small. Every rule in scenario §14 reduces to one of these,
 * which is the evidence the model is coherent rather than a pile of special cases.
 */
public enum Grounds {

    /** The caller is the subject. Bounded by state gates: P-4.4 and P-5.3 both bite here. */
    SELF,

    /** {@code mgr(S) = A} (P-1.1). Direct reports only - never transitive, never skip-level. */
    DIRECT_MANAGER,

    /**
     * The caller is an HR user whose granted departments cover this resource, after the
     * own-department block and any explicit-grant override (P-2.1, P-2.3, P-2.4).
     *
     * <p>Unreachable when the caller is the subject. That is P-2.2, and it is decided at
     * step 2 of the evaluation order so no later step can restore it.
     */
    HR_IN_SCOPE,

    /** One of the two peers {@code mgr(S)} assigned to this subject for this cycle (P-3.4). */
    ASSIGNED_PEER,

    /**
     * The C-suite, acting as the C-suite. Carries aggregate metrics only (P-7.1) and is
     * never grounds for reading an individual artifact.
     *
     * <p>Where a Leadership member reviews the tier below them - including the HR Head
     * (P-2.6, P-7.3) - they do so as {@link #DIRECT_MANAGER}, because that is what they are.
     * Scenario §7 needs no separate mechanism.
     */
    LEADERSHIP,

    /**
     * Platform administration: users, org structure, grants, cycle configuration (P-9.1 to
     * P-9.3). Never grounds for review, rating or plan content (P-9.4) - the Super Admin
     * grants HR their departments, so reading on top of that would make the role omnipotent.
     */
    SUPER_ADMIN,

    /**
     * The cycle sweep, which has no user (P-6.4). It bypasses these checks precisely because
     * there is nobody to check, and remains bound by every domain invariant.
     */
    SYSTEM
}
