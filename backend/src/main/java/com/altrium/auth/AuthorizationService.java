package com.altrium.auth;

import com.altrium.org.AppUser;
import com.altrium.org.AppUserRepository;
import com.altrium.org.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * The one place in Altrium that decides who may do what.
 *
 * <p>No controller, service or repository performs its own check (P-0.2). That is not a
 * tidiness preference: a rule enforced in eleven places is eleven rules, and the day one of
 * them is edited and the others are not, the system has a hole nobody can see by reading any
 * single file. Here, the ordering that matters is visible in one method and proved by one
 * test class.
 *
 * <h2>The evaluation order (P-0.6)</h2>
 * <ol>
 *   <li><b>Authenticated</b> - a provisioned, active Altrium user</li>
 *   <li><b>Absolute self-blocks</b> - P-2.2 and P-1.5, which nothing later can undo</li>
 *   <li><b>Role gate</b> - does any actor hold this capability at all?</li>
 *   <li><b>Relationship and scope</b> - P-1.1 direct reports, P-2.1/P-2.3 HR departments</li>
 *   <li><b>Explicit-grant override</b> - P-2.4, applied inside {@link HrScopeResolver}</li>
 *   <li><b>State gates</b> - P-5.3 co-sign, P-4.4 release</li>
 * </ol>
 *
 * <p><strong>Step 5 can never reach past step 2, and that is the whole design.</strong> The
 * explicit-grant flag lifts the own-<em>department</em> block only. Were the override checked
 * before the self-block - the natural order if you write the rules as you read them in
 * scenario §3 - an HR Head holding an explicit grant over their own department would reach
 * their own review, marking their own homework. Step 2 removes HR grounds outright when the
 * caller is the subject, and steps 4 and 5 can only choose among the grounds that survive.
 *
 * <p>What step 2 removes is HR <em>authority</em> over one's own case, not the ordinary
 * rights of the person who happens to hold the role. An HR user is an employee as well
 * (P-0.1), so they read their own rating and manager feedback exactly as anyone does - while
 * remaining unable to calibrate that rating, co-sign their own PIP, or see the peer feedback
 * written about them. The distinction is the point: this is a conflict-of-interest control,
 * not a penalty for working in HR.
 *
 * <p>The two halves of the API answer different questions and both are needed. {@link
 * #require} decides about one artifact; {@link #subjectScopeFor} produces the predicate for a
 * list. A system with only the first leaks through pagination counts; a system with only the
 * second leaks through a guessed identifier.
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private final CurrentUserService currentUser;
    private final HrScopeResolver hrScope;
    private final AppUserRepository users;

    public AuthorizationService(CurrentUserService currentUser,
                                HrScopeResolver hrScope,
                                AppUserRepository users) {
        this.currentUser = currentUser;
        this.hrScope = hrScope;
        this.users = users;
    }

    // ================================================================= point decisions

    /**
     * Decides, and throws on denial. The form every caller should use - a decision that must
     * be inspected to take effect is a decision somebody will eventually forget to inspect.
     *
     * @throws AccessDeniedApiException always surfacing as 403 with an opaque body (P-0.5)
     */
    public Grounds require(Capability capability, ReviewSubject subject, ArtifactState state) {
        AuthorizationDecision decision = decide(capability, subject, state);
        if (decision.denied()) {
            throw refuse(capability, decision);
        }
        return decision.grounds();
    }

    /** For capabilities with no state gate. */
    public Grounds require(Capability capability, ReviewSubject subject) {
        return require(capability, subject, ArtifactState.none());
    }

    /**
     * Loads the subject and decides in one step (P-0.4), for the common case of a request
     * that names a reviewee by id.
     *
     * <p>An unknown id is refused with 403, not 404. The difference between "no such person"
     * and "not your person" is enough to enumerate the organisation one identifier at a time.
     */
    @Transactional(readOnly = true)
    public Grounds requireForUser(Capability capability, Long subjectId, ArtifactState state) {
        return require(capability, subject(subjectId), state);
    }

    @Transactional(readOnly = true)
    public Grounds requireForUser(Capability capability, Long subjectId) {
        return requireForUser(capability, subjectId, ArtifactState.none());
    }

    /** HR monitoring and anything else scoped to a department rather than a person (P-6.3). */
    public Grounds requireForDepartment(Capability capability, Long departmentId) {
        AuthorizationDecision decision = evaluate(capability, null, departmentId, ArtifactState.none());
        if (decision.denied()) {
            throw refuse(capability, decision);
        }
        return decision.grounds();
    }

    /** Cycle configuration, org administration, aggregate metrics. */
    public Grounds requireGlobal(Capability capability) {
        AuthorizationDecision decision = evaluate(capability, null, null, ArtifactState.none());
        if (decision.denied()) {
            throw refuse(capability, decision);
        }
        return decision.grounds();
    }

    /** The decision without the throw, for callers that need to branch rather than fail. */
    public AuthorizationDecision decide(Capability capability, ReviewSubject subject, ArtifactState state) {
        Long departmentId = subject == null ? null : subject.departmentId();
        return evaluate(capability, subject, departmentId, state);
    }

    /**
     * Whether an artifact may exist for this person at all (P-1.5, P-7.2).
     *
     * <p>Enforced at creation rather than hidden on read. Leadership hold no review, rating
     * or plan; a row created for one would be invisible but real, and the first export or
     * migration that forgot the read filter would surface it.
     */
    public void requireReviewable(ReviewSubject subject) {
        if (subject.leadership()) {
            throw new AccessDeniedApiException(
                    "P-1.5: Leadership is never a reviewee (subject " + subject.id() + ")");
        }
    }

    // ================================================================= collection scoping

    /**
     * The {@code WHERE} clause for a list of artifacts, resolved for this request.
     *
     * <p>Pass the result to {@link SubjectScopeSpecification#subjectsIn}. Any state gate the
     * capability carries - the PIP co-sign gate, rating release - is <em>not</em> in here and
     * must be ANDed on by the feature that owns that column, because this class does not know
     * the shape of those tables. What is in here is the access predicate, identically for
     * every artifact type.
     */
    @Transactional(readOnly = true)
    public SubjectScope subjectScopeFor(Capability capability) {
        CurrentUser caller = currentUser.require();
        if (!caller.active()) {
            throw new AccessDeniedApiException("P-0.7: caller " + caller.id() + " is deactivated");
        }

        // Self appears whenever the capability admits it - an HR user is an employee too, and
        // sees their own record on the same terms as anyone (P-0.1, P-4.4).
        //
        // P-2.2 still applies to the list, but on the HR branch only: see SubjectScope, where
        // the caller's own id is a not-equal term on the department predicate. So an HR user's
        // own row reaches them as *theirs*, never as something their grant covers. The
        // difference is visible on READ_PEER_REVIEW, which has no SELF grounds at all: their
        // own row drops out of that list entirely, which is exactly right (P-3.3).
        boolean hrCaller = caller.hasRole(Role.HR);
        boolean includeSelf = capability.allows(Grounds.SELF);

        Set<Long> reportIds = capability.allows(Grounds.DIRECT_MANAGER)
                ? Set.copyOf(users.findIdsByManagerId(caller.id()))
                : Set.of();

        Set<Long> departmentIds = capability.allows(Grounds.HR_IN_SCOPE) && hrCaller
                ? hrScope.resolve().departmentIds()
                : Set.of();

        return new SubjectScope(caller.id(), includeSelf, reportIds, departmentIds);
    }

    /** Loads a reviewee, refusing an unknown id as 403 rather than 404 (P-0.5). */
    @Transactional(readOnly = true)
    public ReviewSubject subject(Long subjectId) {
        AppUser user = users.findByIdForAuthorization(subjectId)
                .orElseThrow(() -> new AccessDeniedApiException(
                        "P-0.5: no readable subject " + subjectId));
        return ReviewSubject.of(user);
    }

    // ================================================================= the ordered decision

    /**
     * <strong>The single method the P-0.6 order lives in.</strong> Every access decision in
     * Altrium passes through here, in this sequence, and nowhere else.
     */
    private AuthorizationDecision evaluate(Capability capability,
                                           ReviewSubject subject,
                                           Long departmentId,
                                           ArtifactState state) {

        // ---- Step 1: authenticated ---------------------------------------------------
        CurrentUser caller = currentUser.find().orElse(null);
        if (caller == null) {
            return AuthorizationDecision.deny("P-0.5", "caller is not a provisioned Altrium user");
        }
        if (!caller.active()) {
            // Soft delete removes the ability to act, never the row (P-0.7). Their history
            // stays readable to whoever could read it; they simply cannot act again.
            return AuthorizationDecision.deny("P-0.7", "caller " + caller.id() + " is deactivated");
        }

        if (capability.kind() == Capability.Kind.ARTIFACT && subject == null) {
            throw new IllegalArgumentException(capability + " concerns a reviewee; none was supplied");
        }

        boolean callerIsSubject = subject != null && caller.is(subject.id());

        // ---- Step 2: absolute self-blocks --------------------------------------------
        // Nothing below this point can undo either of these.

        if (capability.concernsReviewContent() && subject.leadership()) {
            // P-1.5 / P-7.2. Not "hidden from Leadership" - no such artifact exists.
            return AuthorizationDecision.deny("P-1.5", "Leadership is never a reviewee");
        }

        if (capability.concernsReviewContent() && subject.superAdmin()) {
            // P-9.5. The Super Admin is a dedicated platform account rather than a person who
            // also administers, so there is no review, rating or plan about them for anybody to
            // read - not their manager, not HR, not themselves.
            //
            // Structural rather than left to the cohort screen refusing to enrol them. That
            // refusal is the civil error a person meets; this is the guarantee. A row inserted
            // by a migration, a fixture or a future feature cannot make a Super Admin reviewable
            // without passing through here.
            return AuthorizationDecision.deny("P-9.5", "The Super Admin is never a reviewee");
        }

        // P-2.2, the own-review block. Decided here, at step 2, precisely so that the
        // explicit-grant override at step 5 cannot reach it - an HR user's grants, however
        // wide and whatever flag they carry, never apply to their own case.
        //
        // What it removes is HR *authority*, not the person's ordinary rights. An HR user is
        // still an employee (P-0.1), so they read their own final rating and manager feedback
        // on exactly the terms everyone else does (P-4.4) - the alternative would leave the
        // HR Head reviewed by Leadership under P-2.6 and never told the outcome. What they
        // cannot do is act as HR upon themselves: calibrate their own rating, co-sign their
        // own PIP, or read the peer feedback written about them, which no subject ever sees
        // (P-3.3). Those all sit behind HR_IN_SCOPE, which is what this flag withholds.
        boolean hrGroundsBlocked =
                callerIsSubject && caller.hasRole(Role.HR) && capability.concernsReviewContent();

        // ---- Step 3: role gate --------------------------------------------------------
        if (capability.grounds().isEmpty()) {
            // No actor holds this capability. Today that is only EXTEND_PIP_DEADLINE: a PIP
            // deadline is immutable once set (P-5.5), so the refusal is universal rather than
            // a matter of who is asking.
            return AuthorizationDecision.deny(capability.policy(),
                    capability + " is permitted to no actor");
        }

        // ---- Steps 4 and 5: relationship, scope, and the explicit-grant override -------
        Grounds grounds = groundsFor(capability, caller, subject, departmentId, state, hrGroundsBlocked);
        if (grounds == null) {
            return AuthorizationDecision.deny(capability.policy(),
                    "caller " + caller.id() + " holds no grounds for " + capability
                            + (subject == null ? "" : " on subject " + subject.id()));
        }

        // ---- Step 6: state gates ------------------------------------------------------
        return stateGate(capability, grounds, state);
    }

    /**
     * Steps 4 and 5. Grounds are tried in a fixed order and the first match wins, so a
     * decision always has exactly one justification - a manager who is also HR is recorded as
     * acting as the manager, which is the stronger and more specific relationship.
     */
    private Grounds groundsFor(Capability capability,
                               CurrentUser caller,
                               ReviewSubject subject,
                               Long departmentId,
                               ArtifactState state,
                               boolean hrGroundsBlocked) {

        boolean callerIsSubject = subject != null && caller.is(subject.id());

        if (capability.allows(Grounds.SELF) && callerIsSubject) {
            return Grounds.SELF;
        }

        // P-3.4. The assignment table is owned by the peer-review feature, so the fact
        // arrives as state; the rule that only an assigned peer may write lives here.
        if (capability.allows(Grounds.ASSIGNED_PEER) && state.callerIsAssignedPeer() && !callerIsSubject) {
            return Grounds.ASSIGNED_PEER;
        }

        // P-1.1: direct reports only. This is also how a Leadership member reviews the tier
        // below them, including the HR Head (P-2.6, P-7.3) - as that person's manager, which
        // is what they are. Scenario §7 needs no second mechanism.
        if (capability.allows(Grounds.DIRECT_MANAGER) && subject != null && subject.isManagedBy(caller.id())) {
            return Grounds.DIRECT_MANAGER;
        }

        // P-2.1 and P-2.3, with P-2.4 already applied: HrScopeResolver returns the granted
        // departments minus the caller's own, unless that grant carries the explicit flag.
        // Step 5 of the order is inside that resolver, and it is reached only here - after
        // the step-2 block has had its say. hrGroundsBlocked is that block: when the caller
        // is the subject, no grant reaches this line, so the override cannot restore it.
        if (capability.allows(Grounds.HR_IN_SCOPE)
                && !hrGroundsBlocked
                && caller.hasRole(Role.HR)
                && hrScope.resolve().covers(departmentId)) {
            return Grounds.HR_IN_SCOPE;
        }

        // P-7.1: aggregates only. Leadership never appears in the grounds of an artifact
        // capability, so there is nothing to drill down from.
        if (capability.allows(Grounds.LEADERSHIP) && caller.hasRole(Role.LEADERSHIP)) {
            return Grounds.LEADERSHIP;
        }

        // P-9.1 to P-9.3. Never listed on a review, rating or plan capability (P-9.4), so a
        // Super Admin asking for review content falls through to a denial like anyone else.
        if (capability.allows(Grounds.SUPER_ADMIN) && caller.hasRole(Role.SUPER_ADMIN)) {
            return Grounds.SUPER_ADMIN;
        }

        return null;
    }

    /**
     * Step 6. State gates constrain the subject only. A manager and HR must be able to see a
     * PIP before it is co-signed - somebody has to draft and check it - and a manager must be
     * able to see a rating before it is released, since they are the one setting it.
     */
    private AuthorizationDecision stateGate(Capability capability, Grounds grounds, ArtifactState state) {
        if (grounds == Grounds.SELF) {
            if (capability == Capability.READ_IMPROVEMENT_PLAN && !state.cosigned()) {
                // P-5.3. A predicate, not a UI condition: calling the endpoint directly with
                // a known id lands here and is refused.
                return AuthorizationDecision.deny("P-5.3",
                        "improvement plan is not yet co-signed and is invisible to the subject");
            }
            if (capability == Capability.READ_FINAL_RATING && !state.released()) {
                // P-4.4. A rating still under calibration is not the subject's to read.
                return AuthorizationDecision.deny("P-4.4",
                        "final rating has not been released to the subject");
            }
        }
        return AuthorizationDecision.permit(grounds, capability.policy());
    }

    /**
     * The reason is logged and discarded. What the caller receives is 403 with a body that
     * cannot distinguish "does not exist" from "not permitted" (P-0.5).
     */
    private AccessDeniedApiException refuse(Capability capability, AuthorizationDecision decision) {
        log.info("Denied {} under {}: {}", capability, decision.policy(), decision.reason());
        return new AccessDeniedApiException(decision.policy() + ": " + decision.reason());
    }
}
