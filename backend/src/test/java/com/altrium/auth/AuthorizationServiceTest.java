package com.altrium.auth;

import com.altrium.org.AppUser;
import com.altrium.org.AppUserRepository;
import com.altrium.org.Department;
import com.altrium.org.HrGrantService;
import com.altrium.org.Role;
import com.altrium.testsupport.Acting;
import com.altrium.testsupport.OrgFixture;
import com.altrium.testsupport.StubJwtDecoderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The denial suite for the central authorization component.
 *
 * <p>These call {@link AuthorizationService} directly rather than through an endpoint,
 * because at this point there are no review endpoints — the component is deliberately built
 * before the features that use it. Each feature adds its own {@code MockMvc} denial tests on
 * top; what is proved here is that the rules and their <em>ordering</em> are right, which is
 * the part no per-feature test can establish on its own.
 *
 * <p>The organisation under test, built fresh per test:
 * <pre>
 *   Richard (Leadership) ── Kevin (HR Head, People Ops, explicit grant over People Ops)
 *                        └─ Elena (Manager, Engineering) ── John, Aisha (Engineering)
 *   Hana (HR, People Ops, granted Engineering only)
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class AuthorizationServiceTest {

    @Autowired
    private AuthorizationService authorization;

    @Autowired
    private OrgFixture org;

    @Autowired
    private Acting acting;

    @Autowired
    private HrGrantService grants;

    @Autowired
    private AppUserRepository users;

    @AfterEach
    void clearContext() {
        acting.clear();
    }

    // ------------------------------------------------------------------ P-1 relationship

    @Test
    @DisplayName("P-1.2: a manager may read their direct report's self-review")
    void P_1_2_managerReadsDirectReportsReview() {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        acting.as(elena);

        assertThat(authorization.requireForUser(Capability.READ_SELF_REVIEW, john.getId()))
                .isEqualTo(Grounds.DIRECT_MANAGER);
    }

    @Test
    @DisplayName("P-1.2: a manager cannot read a non-report's review")
    void P_1_2_managerCannotReadNonReportReview() {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser tom = org.userIn(engineering, "tom", Role.EMPLOYEE, Role.MANAGER);
        AppUser omar = org.userIn(engineering, "omar", Role.EMPLOYEE);
        omar.setManager(tom);
        org.flush();

        // Same department, same seniority, adjacent team. Nothing about being a manager
        // grants anything; only being *this person's* manager does.
        acting.as(elena);

        assertDenied(() -> authorization.requireForUser(Capability.READ_SELF_REVIEW, omar.getId()));
    }

    @Test
    @DisplayName("P-1.1: a skip-level manager is not a manager — the relationship is never transitive")
    void P_1_1_skipLevelManagerIsDenied() {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        jane.setManager(elena);
        john.setManager(jane);
        org.flush();

        // Elena is John's manager's manager. If this passed, authority would flow all the way
        // up every chain and the whole relationship model would collapse into seniority.
        acting.as(elena);

        assertDenied(() -> authorization.requireForUser(Capability.READ_MANAGER_REVIEW, john.getId()));
    }

    @Test
    @DisplayName("P-1.5: no artifact may exist for a Leadership member, even for their own manager")
    void P_1_5_leadershipIsNeverAReviewee() {
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser priya = org.user("priya", Role.EMPLOYEE, Role.LEADERSHIP);
        richard.setManager(priya);
        org.flush();

        acting.as(priya);

        // Denied at step 2, before any relationship is even considered — so this cannot be
        // undone by a reporting line, a role or a grant.
        assertDenied(() -> authorization.requireForUser(Capability.WRITE_MANAGER_REVIEW, richard.getId()));
        assertThatThrownBy(() -> authorization.requireReviewable(authorization.subject(richard.getId())))
                .isInstanceOf(AccessDeniedApiException.class)
                .hasMessageContaining("P-1.5");
    }

    // ------------------------------------------------------------------ P-3 visibility

    @Test
    @DisplayName("P-3.3: the subject has no grounds for peer reviews about themselves")
    void P_3_3_subjectCannotReadPeerReviewsAboutThemselves() {
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        acting.as(john);

        // Anonymity is structural: SELF is not among READ_PEER_REVIEW's grounds at all, so
        // there is no filter to forget and no aggregate to leak through.
        assertThat(Capability.READ_PEER_REVIEW.allows(Grounds.SELF)).isFalse();
        assertDenied(() -> authorization.requireForUser(Capability.READ_PEER_REVIEW, john.getId()));
    }

    @Test
    @DisplayName("P-3.3: the subject is excluded from peer-review lists, so the count cannot leak either")
    void P_3_3_subjectScopeForPeerReviewsExcludesThemselves() {
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        acting.as(john);

        SubjectScope scope = authorization.subjectScopeFor(Capability.READ_PEER_REVIEW);

        // An empty scope yields a false predicate — an empty page *and* a zero count, rather
        // than a full count with the rows filtered out afterwards.
        assertThat(scope.isEmpty()).isTrue();
        assertThat(page(scope)).isEmpty();
    }

    @Test
    @DisplayName("P-3.4: only an assigned peer may write a peer review")
    void P_3_4_onlyAssignedPeerMayWrite() {
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        org.flush();

        acting.as(aisha);

        assertThat(authorization.require(Capability.WRITE_PEER_REVIEW,
                authorization.subject(john.getId()), ArtifactState.assignedPeer()))
                .isEqualTo(Grounds.ASSIGNED_PEER);

        // The same colleague, not assigned to this subject this cycle.
        assertDenied(() -> authorization.requireForUser(Capability.WRITE_PEER_REVIEW, john.getId()));
    }

    @Test
    @DisplayName("P-3.1: the subject writes their own self-review; their manager may not write it for them")
    void P_3_1_selfReviewIsWrittenByTheSubjectAlone() {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        acting.as(john);
        assertThat(authorization.requireForUser(Capability.WRITE_SELF_REVIEW, john.getId()))
                .isEqualTo(Grounds.SELF);

        acting.as(elena);
        assertDenied(() -> authorization.requireForUser(Capability.WRITE_SELF_REVIEW, john.getId()));
    }

    // ------------------------------------------------------------------ P-2 HR scoping

    @Nested
    @DisplayName("HR scoping — the ordering-critical block")
    class HrScoping {

        @Test
        @DisplayName("P-2.1: HR may act in a granted department")
        void P_2_1_hrActsInGrantedDepartment() {
            Department engineering = org.department("Engineering");
            Department peopleOps = org.department("People Operations");
            AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
            AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
            org.flush();
            grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

            acting.as(hana);

            assertThat(authorization.requireForUser(Capability.CALIBRATE_RATING, john.getId()))
                    .isEqualTo(Grounds.HR_IN_SCOPE);
        }

        @Test
        @DisplayName("P-2.1: HR is denied in a department they were never granted")
        void P_2_1_hrDeniedOutsideGrantedDepartments() {
            Department engineering = org.department("Engineering");
            Department sales = org.department("Sales");
            Department peopleOps = org.department("People Operations");
            AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
            AppUser nadia = org.userIn(sales, "nadia", Role.EMPLOYEE);
            org.flush();
            grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

            acting.as(hana);

            assertDenied(() -> authorization.requireForUser(Capability.READ_SELF_REVIEW, nadia.getId()));
        }

        @Test
        @DisplayName("P-2.3: a plain grant over HR's own department buys them nothing")
        void P_2_3_ownDepartmentBlockedWithoutExplicitGrant() {
            Department peopleOps = org.department("People Operations");
            AppUser rosa = org.userIn(peopleOps, "rosa", Role.EMPLOYEE, Role.HR);
            AppUser samuel = org.userIn(peopleOps, "samuel", Role.EMPLOYEE);
            org.flush();
            grants.grant(rosa.getId(), peopleOps.getId(), false, null, g -> g.getId());

            acting.as(rosa);

            // The grant row exists. Without the explicit flag it does not lift the block, so
            // nobody oversees reviews in the department they work in.
            assertDenied(() -> authorization.requireForUser(Capability.READ_MANAGER_REVIEW, samuel.getId()));
        }

        @Test
        @DisplayName("P-2.4: the explicit grant lifts the own-department block for the HR Head")
        void P_2_4_explicitGrantLiftsOwnDepartmentBlock() {
            Department peopleOps = org.department("People Operations");
            AppUser kevin = org.userIn(peopleOps, "kevin", Role.EMPLOYEE, Role.HR);
            AppUser samuel = org.userIn(peopleOps, "samuel", Role.EMPLOYEE);
            org.flush();
            grants.grant(kevin.getId(), peopleOps.getId(), true, null, g -> g.getId());

            acting.as(kevin);

            assertThat(authorization.requireForUser(Capability.READ_MANAGER_REVIEW, samuel.getId()))
                    .isEqualTo(Grounds.HR_IN_SCOPE);
        }

        @Test
        @DisplayName("P-0.6/P-2.2: an explicit grant still does not reach the HR Head's own review")
        void P_0_6_explicitGrantCannotReachOwnReview() {
            Department peopleOps = org.department("People Operations");
            AppUser kevin = org.userIn(peopleOps, "kevin", Role.EMPLOYEE, Role.HR);
            org.flush();
            grants.grant(kevin.getId(), peopleOps.getId(), true, null, g -> g.getId());

            acting.as(kevin);

            // The rule-ordering test, and the single most important assertion in the suite.
            // Kevin's explicit grant covers his own department, and he is in it — so a model
            // that checked the override before the self-block would permit every one of
            // these. The self-block is decided at step 2 and the override at step 5.
            assertDenied(() -> authorization.requireForUser(Capability.READ_MANAGER_REVIEW, kevin.getId()));
            assertDenied(() -> authorization.requireForUser(Capability.READ_PEER_REVIEW, kevin.getId()));
            assertDenied(() -> authorization.requireForUser(Capability.CALIBRATE_RATING, kevin.getId()));
            assertDenied(() -> authorization.requireForUser(Capability.READ_DEVELOPMENT_PLAN, kevin.getId()));
            assertDenied(() -> authorization.requireForUser(Capability.COSIGN_IMPROVEMENT_PLAN, kevin.getId()));
        }

        @Test
        @DisplayName("P-2.2: the HR Head's own row is absent from a list their grant covers")
        void P_2_2_ownRowExcludedFromScopedList() {
            Department peopleOps = org.department("People Operations");
            AppUser kevin = org.userIn(peopleOps, "kevin", Role.EMPLOYEE, Role.HR);
            AppUser samuel = org.userIn(peopleOps, "samuel", Role.EMPLOYEE);
            AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
            org.flush();
            grants.grant(kevin.getId(), peopleOps.getId(), true, null, g -> g.getId());

            acting.as(kevin);

            Page<AppUser> visible = page(authorization.subjectScopeFor(Capability.READ_MANAGER_REVIEW));

            assertThat(visible).extracting(AppUser::getId)
                    .containsExactlyInAnyOrder(samuel.getId(), hana.getId())
                    .doesNotContain(kevin.getId());
            // The count agrees with the rows. Filtering in Java would have made it 3.
            assertThat(visible.getTotalElements()).isEqualTo(2);
        }

        @Test
        @DisplayName("P-2.5: revoking a grant bites on the very next request, with no re-login")
        void P_2_5_revokedGrantAppliesImmediately() {
            Department engineering = org.department("Engineering");
            Department peopleOps = org.department("People Operations");
            AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
            AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
            org.flush();
            grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

            acting.as(hana);
            assertThat(authorization.requireForUser(Capability.READ_SELF_REVIEW, john.getId()))
                    .isEqualTo(Grounds.HR_IN_SCOPE);

            grants.revoke(hana.getId(), engineering.getId());

            // Same person, same token, next request. If grants were cached at login this
            // would still pass, and a revoked HR user would keep their reach until their
            // token happened to expire.
            acting.nextRequestAs(hana);
            assertDenied(() -> authorization.requireForUser(Capability.READ_SELF_REVIEW, john.getId()));
        }

        @Test
        @DisplayName("P-5.1: HR reads a development plan but cannot write one")
        void P_5_1_hrIsReadOnlyOnDevelopmentPlans() {
            Department engineering = org.department("Engineering");
            Department peopleOps = org.department("People Operations");
            AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
            AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
            org.flush();
            grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

            acting.as(hana);

            assertThat(authorization.requireForUser(Capability.READ_DEVELOPMENT_PLAN, john.getId()))
                    .isEqualTo(Grounds.HR_IN_SCOPE);
            assertDenied(() -> authorization.requireForUser(Capability.WRITE_DEVELOPMENT_PLAN, john.getId()));
            assertDenied(() -> authorization.requireForUser(Capability.APPROVE_GOAL, john.getId()));
        }

        @Test
        @DisplayName("P-6.3: HR monitors granted departments only")
        void P_6_3_cycleMonitoringIsScopedToGrants() {
            Department engineering = org.department("Engineering");
            Department sales = org.department("Sales");
            Department peopleOps = org.department("People Operations");
            AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
            org.flush();
            grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

            acting.as(hana);

            assertThat(authorization.requireForDepartment(Capability.MONITOR_CYCLE, engineering.getId()))
                    .isEqualTo(Grounds.HR_IN_SCOPE);
            assertDenied(() -> authorization.requireForDepartment(Capability.MONITOR_CYCLE, sales.getId()));
        }
    }

    // ------------------------------------------------------------------ P-4 ratings

    @Test
    @DisplayName("P-4.1: the manager sets the final rating; HR does not")
    void P_4_1_onlyTheManagerSetsTheFinalRating() {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        acting.as(elena);
        assertThat(authorization.requireForUser(Capability.SET_FINAL_RATING, john.getId()))
                .isEqualTo(Grounds.DIRECT_MANAGER);

        // HR normalises what the manager decided (P-4.3); they do not decide it.
        acting.as(hana);
        assertDenied(() -> authorization.requireForUser(Capability.SET_FINAL_RATING, john.getId()));
        assertThat(authorization.requireForUser(Capability.CALIBRATE_RATING, john.getId()))
                .isEqualTo(Grounds.HR_IN_SCOPE);
    }

    @Test
    @DisplayName("P-4.4: the subject reads their rating only once it is released")
    void P_4_4_subjectSeesRatingOnlyOnceReleased() {
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        acting.as(john);
        ReviewSubject subject = authorization.subject(john.getId());

        // Mid-calibration, the rating is not yet theirs to read.
        assertDenied(() -> authorization.require(Capability.READ_FINAL_RATING, subject,
                ArtifactState.released(false)));
        assertThat(authorization.require(Capability.READ_FINAL_RATING, subject,
                ArtifactState.released(true))).isEqualTo(Grounds.SELF);
    }

    // ------------------------------------------------------------------ P-5 plans

    @Test
    @DisplayName("P-5.3: a PIP is invisible to the subject until it is co-signed")
    void P_5_3_pipIsInvisibleBeforeCosign() {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        acting.as(john);
        ReviewSubject subject = authorization.subject(john.getId());
        assertDenied(() -> authorization.require(Capability.READ_IMPROVEMENT_PLAN, subject,
                ArtifactState.cosigned(false)));
        assertThat(authorization.require(Capability.READ_IMPROVEMENT_PLAN, subject,
                ArtifactState.cosigned(true))).isEqualTo(Grounds.SELF);

        // The gate binds the subject only — the manager drafting it must be able to see it.
        acting.as(elena);
        assertThat(authorization.require(Capability.READ_IMPROVEMENT_PLAN,
                authorization.subject(john.getId()), ArtifactState.cosigned(false)))
                .isEqualTo(Grounds.DIRECT_MANAGER);
    }

    @Test
    @DisplayName("P-5.4: the manager who opened a PIP can neither co-sign it nor record the witness")
    void P_5_4_managerCannotCosignOrWitness() {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        acting.as(elena);

        assertThat(authorization.requireForUser(Capability.OPEN_IMPROVEMENT_PLAN, john.getId()))
                .isEqualTo(Grounds.DIRECT_MANAGER);
        // Opening and formalising are separate hands. That separation is the point of the
        // co-signature and the witness.
        assertDenied(() -> authorization.requireForUser(Capability.COSIGN_IMPROVEMENT_PLAN, john.getId()));
        assertDenied(() -> authorization.requireForUser(Capability.RECORD_WITNESS, john.getId()));
    }

    @Test
    @DisplayName("P-5.5: no actor may extend a PIP deadline")
    void P_5_5_pipDeadlinesAreImmutableForEveryone() {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser devin = org.userIn(engineering, "devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        // Modelled as a capability nobody holds, rather than an endpoint nobody wrote —
        // so it is enforced rather than merely unimplemented.
        for (AppUser actor : List.of(elena, hana, devin, john)) {
            acting.as(actor);
            assertDenied(() -> authorization.requireForUser(Capability.EXTEND_PIP_DEADLINE, john.getId()));
        }
        assertThat(Capability.EXTEND_PIP_DEADLINE.grounds()).isEmpty();
    }

    // ------------------------------------------------------------------ P-7 / P-9 roles

    @Test
    @DisplayName("P-7.1: Leadership gets aggregates, never an individual review")
    void P_7_1_leadershipGetsAggregatesOnly() {
        Department engineering = org.department("Engineering");
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        acting.as(richard);

        assertThat(authorization.requireGlobal(Capability.READ_AGGREGATE_METRICS))
                .isEqualTo(Grounds.LEADERSHIP);
        assertDenied(() -> authorization.requireForUser(Capability.READ_SELF_REVIEW, john.getId()));
        assertDenied(() -> authorization.requireForUser(Capability.READ_FINAL_RATING, john.getId()));
    }

    @Test
    @DisplayName("P-7.3/P-2.6: Leadership reviews the HR Head — as their manager, not as Leadership")
    void P_7_3_leadershipReviewsTheHrHeadAsTheirManager() {
        Department peopleOps = org.department("People Operations");
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser kevin = org.userIn(peopleOps, "kevin", Role.EMPLOYEE, Role.HR, Role.MANAGER);
        kevin.setManager(richard);
        org.flush();

        acting.as(richard);

        // This is how P-2.2 stays absolute without leaving the HR Head unreviewed, and it
        // needs no separate mechanism: Richard is Kevin's manager, so P-1.1 already covers it.
        assertThat(authorization.requireForUser(Capability.WRITE_MANAGER_REVIEW, kevin.getId()))
                .isEqualTo(Grounds.DIRECT_MANAGER);
        assertThat(authorization.requireForUser(Capability.SET_FINAL_RATING, kevin.getId()))
                .isEqualTo(Grounds.DIRECT_MANAGER);
    }

    @Test
    @DisplayName("P-9.4: the Super Admin administers the platform and reads no review content")
    void P_9_4_superAdminReadsNoReviewContent() {
        Department engineering = org.department("Engineering");
        AppUser devin = org.userIn(engineering, "devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        acting.as(devin);

        assertThat(authorization.requireGlobal(Capability.MANAGE_ORG)).isEqualTo(Grounds.SUPER_ADMIN);
        assertThat(authorization.requireGlobal(Capability.CONFIGURE_CYCLE)).isEqualTo(Grounds.SUPER_ADMIN);

        // They grant HR their departments; reading reviews on top would make the role
        // omnipotent and leave nobody able to check them.
        assertDenied(() -> authorization.requireForUser(Capability.READ_SELF_REVIEW, john.getId()));
        assertDenied(() -> authorization.requireForUser(Capability.READ_FINAL_RATING, john.getId()));
        assertDenied(() -> authorization.requireForUser(Capability.READ_IMPROVEMENT_PLAN, john.getId()));

        // Their scoped list contains exactly one person: themselves. Administering the
        // platform is a role, not a place in the hierarchy — Devin is an ordinary engineer
        // with his own self-review (P-0.1), and the Super Admin role adds nobody else to it.
        assertThat(page(authorization.subjectScopeFor(Capability.READ_SELF_REVIEW)))
                .extracting(AppUser::getId)
                .containsExactly(devin.getId());
        // And it adds nothing at all where the caller is not themselves a subject.
        assertThat(page(authorization.subjectScopeFor(Capability.READ_PEER_REVIEW))).isEmpty();
    }

    // ------------------------------------------------------------------ P-0 foundations

    @Test
    @DisplayName("P-0.5: an unknown subject id is refused, not reported as missing")
    void P_0_5_unknownSubjectIsRefusedNotReportedMissing() {
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        acting.as(john);

        // 404 for ids that do not exist and 403 for ids that do would let a caller map the
        // organisation one identifier at a time.
        assertDenied(() -> authorization.requireForUser(Capability.READ_SELF_REVIEW, 987_654_321L));
    }

    @Test
    @DisplayName("P-0.5: a valid token for an unprovisioned subject holds nothing")
    void P_0_5_unprovisionedCallerIsDenied() {
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        acting.asUnprovisioned();

        assertDenied(() -> authorization.requireForUser(Capability.READ_SELF_REVIEW, john.getId()));
        assertDenied(() -> authorization.requireGlobal(Capability.READ_AGGREGATE_METRICS));
    }

    @Test
    @DisplayName("P-0.7: a deactivated caller can act on nothing, while their row survives")
    void P_0_7_deactivatedCallerCannotAct() {
        Department engineering = org.department("Engineering");
        AppUser tara = org.userIn(engineering, "tara", Role.EMPLOYEE, Role.MANAGER);
        tara.setActive(false);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(tara);
        org.flush();

        acting.as(tara);

        assertDenied(() -> authorization.requireForUser(Capability.READ_SELF_REVIEW, john.getId()));
        assertDenied(() -> authorization.requireForUser(Capability.WRITE_SELF_REVIEW, tara.getId()));
        // The row is still there — the history and carry-over pillar depends on it.
        assertThat(users.findById(tara.getId())).isPresent();
    }

    @Test
    @DisplayName("P-0.7: a deactivated report stays readable to the manager who could always read them")
    void P_0_7_deactivatedSubjectRemainsReadable() {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser tara = org.userIn(engineering, "tara", Role.EMPLOYEE);
        tara.setManager(elena);
        tara.setActive(false);
        org.flush();

        acting.as(elena);

        // Soft delete removes the ability to act, not the record. Last cycle's review must
        // still be readable or the history pillar is lost.
        assertThat(authorization.requireForUser(Capability.READ_MANAGER_REVIEW, tara.getId()))
                .isEqualTo(Grounds.DIRECT_MANAGER);
    }

    @Test
    @DisplayName("P-0.3: a manager's list carries their report ids, and the count matches the rows")
    void P_0_3_managerScopeIsExactlyTheirReports() {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser tom = org.userIn(engineering, "tom", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser omar = org.userIn(engineering, "omar", Role.EMPLOYEE);
        john.setManager(elena);
        aisha.setManager(elena);
        omar.setManager(tom);
        org.flush();

        acting.as(elena);

        SubjectScope scope = authorization.subjectScopeFor(Capability.READ_SELF_REVIEW);
        assertThat(scope.directReportIds()).containsExactlyInAnyOrder(john.getId(), aisha.getId());

        Page<AppUser> visible = page(scope);
        assertThat(visible).extracting(AppUser::getId)
                // Elena's own row is here because a manager is also an employee with a
                // self-review (P-0.1); Tom's report is not, and neither is Tom.
                .containsExactlyInAnyOrder(elena.getId(), john.getId(), aisha.getId());
        assertThat(visible.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("P-0.6: the same rule decides the list and the single record")
    void P_0_6_listAndDetailAgree() {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser tom = org.userIn(engineering, "tom", Role.EMPLOYEE, Role.MANAGER);
        AppUser omar = org.userIn(engineering, "omar", Role.EMPLOYEE);
        omar.setManager(tom);
        org.flush();

        acting.as(elena);

        // The two halves of the API must not disagree: a row absent from the list must also
        // be refused when its id is used directly, or the id becomes the way around the list.
        assertThat(page(authorization.subjectScopeFor(Capability.READ_SELF_REVIEW)))
                .extracting(AppUser::getId).doesNotContain(omar.getId());
        assertDenied(() -> authorization.requireForUser(Capability.READ_SELF_REVIEW, omar.getId()));
    }

    // ------------------------------------------------------------------ helpers

    /** Applies a scope to the people table, where the subject is the row itself. */
    private Page<AppUser> page(SubjectScope scope) {
        return users.findAll(SubjectScopeSpecification.subjectsIn(scope, null), PageRequest.of(0, 50));
    }

    private void assertDenied(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(AccessDeniedApiException.class);
    }

    @Test
    @DisplayName("P-9.4/P-7.1: no review capability is reachable by the Super Admin or Leadership")
    void P_9_4_noReviewCapabilityListsSuperAdminOrLeadership() {
        // Structural rather than behavioural, so it holds for capabilities added later too —
        // which is what a per-capability test cannot do. If someone adds a review capability
        // and reaches for SUPER_ADMIN to make an admin screen work, this fails.
        for (Capability capability : Capability.values()) {
            if (capability.concernsReviewContent()) {
                assertThat(capability.grounds())
                        .as("%s must be reachable by neither the Super Admin nor Leadership", capability)
                        .doesNotContain(Grounds.SUPER_ADMIN, Grounds.LEADERSHIP);
            }
        }
    }
}
