package com.altrium.auth;

/**
 * The facts about an artifact's <em>state</em> that a decision depends on — step 6 of the
 * P-0.6 evaluation order.
 *
 * <p>These are separated from the caller's relationship to the subject because they answer a
 * different question. Steps 4 and 5 ask "is this person entitled to this kind of thing?";
 * step 6 asks "is this particular record ready to be seen or written yet?". Conflating them
 * produces rules nobody can reason about, and lets a state check accidentally become a
 * permission grant.
 *
 * <p>Every field defaults to false, which is the safe direction: a caller who forgets to
 * supply the co-sign status gets a denial, not a leak.
 *
 * @param callerIsAssignedPeer whether the caller is one of the two peers {@code mgr(S)}
 *                             assigned for this cycle (P-3.4). Supplied by the peer-review
 *                             feature, which owns the assignment table
 * @param cosigned             whether a PIP has been co-signed by HR. Until it has, it is
 *                             invisible to the subject (P-5.3)
 * @param released             whether the final rating has been released to the subject. A
 *                             rating still being calibrated is not theirs to read (P-4.4)
 */
public record ArtifactState(boolean callerIsAssignedPeer, boolean cosigned, boolean released) {

    private static final ArtifactState NONE = new ArtifactState(false, false, false);

    /** For decisions that depend on no state at all, and for defaults. */
    public static ArtifactState none() {
        return NONE;
    }

    public static ArtifactState assignedPeer() {
        return new ArtifactState(true, false, false);
    }

    public static ArtifactState cosigned(boolean cosigned) {
        return new ArtifactState(false, cosigned, false);
    }

    public static ArtifactState released(boolean released) {
        return new ArtifactState(false, false, released);
    }
}
