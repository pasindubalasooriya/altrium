package com.altrium.auth;

import java.util.EnumSet;
import java.util.Set;

import static com.altrium.auth.Grounds.ASSIGNED_PEER;
import static com.altrium.auth.Grounds.DIRECT_MANAGER;
import static com.altrium.auth.Grounds.HR_IN_SCOPE;
import static com.altrium.auth.Grounds.LEADERSHIP;
import static com.altrium.auth.Grounds.SELF;
import static com.altrium.auth.Grounds.SUPER_ADMIN;

/**
 * Every authorization-relevant action in Altrium, with the grounds that can justify it.
 *
 * <p>This enum is the policy list as data. Reading down the {@code grounds} column answers
 * "who may do this?" for the whole system in one screen, which is the point: rules scattered
 * across seventeen controllers cannot be reviewed, and a rule nobody can review is a rule
 * nobody can trust.
 *
 * <p>Two absences carry as much weight as anything present:
 * <ul>
 *   <li>{@link Grounds#SUPER_ADMIN} appears on no review, rating or plan capability (P-9.4).</li>
 *   <li>{@link Grounds#SELF} is absent from {@link #READ_PEER_REVIEW}, which is what makes
 *       peer anonymity structural rather than conditional (P-3.3).</li>
 * </ul>
 *
 * <p>And one capability has <em>no</em> grounds at all: {@link #EXTEND_PIP_DEADLINE}. A PIP
 * deadline is immutable once set (P-5.5), so there is no actor to name — not the manager who
 * opened it, not HR, not the Super Admin. Modelling it as an empty set rather than leaving
 * the endpoint unbuilt means the rule is enforced rather than merely unimplemented.
 */
public enum Capability {

    // ---- Reviews (P-3) ----------------------------------------------------------------

    /**
     * Seeing that a person is under review this cycle, and how far along they are — the
     * roster behind "view permitted reviews", with no review content in it.
     *
     * <p>Its grounds are the union of the three read capabilities below, which is not a
     * coincidence to be relied on: a caller who may read nothing about a subject must not
     * learn that the subject is being reviewed at all, so the roster is exactly as wide as
     * the content behind it and no wider.
     */
    READ_REVIEW_SUMMARY(Kind.ARTIFACT, "P-0.3", SELF, DIRECT_MANAGER, HR_IN_SCOPE),

    /** Written by S; read by S, {@code mgr(S)} and HR-in-scope. Carries no rating field. */
    READ_SELF_REVIEW(Kind.ARTIFACT, "P-3.1", SELF, DIRECT_MANAGER, HR_IN_SCOPE),
    WRITE_SELF_REVIEW(Kind.ARTIFACT, "P-3.1", SELF),

    /**
     * Readable by {@code mgr(S)} and HR-in-scope <em>including author identity</em> (P-3.2).
     *
     * <p>{@link Grounds#SELF} is deliberately not listed. The subject may never receive peer
     * text, rating, author or count, by any route (P-3.3) — so the subject has no grounds
     * here at all, rather than grounds narrowed by a filter that someone later forgets.
     */
    READ_PEER_REVIEW(Kind.ARTIFACT, "P-3.2/P-3.3", DIRECT_MANAGER, HR_IN_SCOPE),

    /** Only the two assigned peers, only for their assigned subject, only while open (P-3.4). */
    WRITE_PEER_REVIEW(Kind.ARTIFACT, "P-3.4", ASSIGNED_PEER),

    /**
     * Exactly two peers, chosen by {@code mgr(S)} (P-1.3, P-3.6). Applied to a manager who is
     * themselves a reviewee, this is scenario §7's "the manager's manager selects" — the same
     * rule one level up, not a second mechanism.
     */
    ASSIGN_PEERS(Kind.ARTIFACT, "P-1.3/P-3.6", DIRECT_MANAGER),

    READ_MANAGER_REVIEW(Kind.ARTIFACT, "P-3.7", SELF, DIRECT_MANAGER, HR_IN_SCOPE),
    WRITE_MANAGER_REVIEW(Kind.ARTIFACT, "P-3.7", DIRECT_MANAGER),

    // ---- Ratings (P-4) ----------------------------------------------------------------

    /** Chosen by {@code mgr(S)}, never computed from the peer ratings (P-4.1). */
    SET_FINAL_RATING(Kind.ARTIFACT, "P-4.1", DIRECT_MANAGER),

    /** HR normalises across the department; every change is recorded (P-4.3). */
    CALIBRATE_RATING(Kind.ARTIFACT, "P-4.3", HR_IN_SCOPE),

    /** S sees their final rating and manager feedback, and only once released (P-4.4). */
    READ_FINAL_RATING(Kind.ARTIFACT, "P-4.4", SELF, DIRECT_MANAGER, HR_IN_SCOPE),

    // ---- Plans (P-5) ------------------------------------------------------------------

    READ_DEVELOPMENT_PLAN(Kind.ARTIFACT, "P-5.1", SELF, DIRECT_MANAGER, HR_IN_SCOPE),

    /** HR is read-only on a PDP (P-5.1): present on the read above, absent here. */
    WRITE_DEVELOPMENT_PLAN(Kind.ARTIFACT, "P-5.1", SELF, DIRECT_MANAGER),

    /** Only {@code mgr(S)} marks a goal complete, on either plan type (P-5.2). */
    APPROVE_GOAL(Kind.ARTIFACT, "P-5.2", DIRECT_MANAGER),

    /** Invisible to S until co-signed — a state gate, not a role gate (P-5.3). */
    READ_IMPROVEMENT_PLAN(Kind.ARTIFACT, "P-5.3", SELF, DIRECT_MANAGER, HR_IN_SCOPE),

    OPEN_IMPROVEMENT_PLAN(Kind.ARTIFACT, "P-5.7", DIRECT_MANAGER),

    /**
     * Co-signing and recording the witness are HR-only (P-5.4). The manager who opened the
     * PIP can do neither, and that separation is the entire point of the formality objects.
     */
    COSIGN_IMPROVEMENT_PLAN(Kind.ARTIFACT, "P-5.4", HR_IN_SCOPE),
    RECORD_WITNESS(Kind.ARTIFACT, "P-5.4", HR_IN_SCOPE),

    /** Nobody. PIP deadlines are immutable once set (P-5.5). */
    EXTEND_PIP_DEADLINE(Kind.ARTIFACT, "P-5.5"),

    // ---- Cycles, aggregates, administration (P-6, P-7, P-9) ---------------------------

    /** HR monitors completion for their granted departments only (P-6.3). */
    MONITOR_CYCLE(Kind.DEPARTMENT, "P-6.3", HR_IN_SCOPE),

    CONFIGURE_CYCLE(Kind.GLOBAL, "P-6.1", SUPER_ADMIN),
    MANAGE_ORG(Kind.GLOBAL, "P-9.1/P-9.2", SUPER_ADMIN),

    /** Totals only. No endpoint drills from an aggregate to an individual row (P-7.1). */
    READ_AGGREGATE_METRICS(Kind.GLOBAL, "P-7.1", LEADERSHIP);

    /** What the capability is asked about, which determines what must be supplied to decide. */
    public enum Kind {
        /** Concerns one reviewee's review, rating or plan. Requires a {@link ReviewSubject}. */
        ARTIFACT,
        /** Concerns a department as a whole. Requires a department id. */
        DEPARTMENT,
        /** Concerns the system. Neither subject nor department applies. */
        GLOBAL
    }

    private final Kind kind;
    private final String policy;
    private final Set<Grounds> grounds;

    Capability(Kind kind, String policy, Grounds... grounds) {
        this.kind = kind;
        this.policy = policy;
        this.grounds = grounds.length == 0
                ? EnumSet.noneOf(Grounds.class)
                : EnumSet.copyOf(Set.of(grounds));
    }

    public Kind kind() {
        return kind;
    }

    /** The policy id this capability implements, cited in denial logs and test names. */
    public String policy() {
        return policy;
    }

    public Set<Grounds> grounds() {
        return grounds;
    }

    public boolean allows(Grounds candidate) {
        return grounds.contains(candidate);
    }

    /**
     * Whether this capability touches review, rating or plan content.
     *
     * <p>Used for the two blanket rules that apply to all such content regardless of which
     * artifact it is: Leadership is never a reviewee (P-1.5, P-7.2), and the Super Admin
     * reads none of it (P-9.4).
     */
    public boolean concernsReviewContent() {
        return kind == Kind.ARTIFACT;
    }
}
