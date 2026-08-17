package com.altrium.review;

// The OrgFixture field below is named `org`, which shadows the `org` package inside this
// class, so every com.altrium.org type has to arrive by import rather than fully qualified.
import com.altrium.org.AppUser;
import com.altrium.org.AppUserRepository;
import com.altrium.org.Department;
import com.altrium.org.DepartmentRepository;
import com.altrium.org.Role;
import com.altrium.testsupport.Acting;
import com.altrium.testsupport.OrgFixture;
import com.altrium.testsupport.ReviewFixture;
import com.altrium.testsupport.StubJwtDecoderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 5 - the cycle-opening sweep (P-6.4).
 *
 * <p>These run at the service level rather than over HTTP, because the sweep has no endpoint
 * and no caller. That absence is the thing under test: every method here runs with the security
 * context cleared, so a check that quietly depended on a logged-in user would fail rather than
 * pass for the wrong reason.
 *
 * <p>The date is passed in rather than read from the clock, so "a cycle dated last month opens
 * on the next sweep" is a test somebody can actually write.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class CycleSweepTest {

    @Autowired
    private CycleService cycles;

    @Autowired
    private CycleParticipantRepository participants;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private DepartmentRepository departments;

    @Autowired
    private OrgFixture org;

    @Autowired
    private ReviewFixture reviews;

    @Autowired
    private Acting acting;

    @AfterEach
    void clearContext() {
        acting.clear();
    }

    @Test
    @DisplayName("P-6.4: the sweep opens a due cycle and takes its cohort in, with no caller")
    void P_6_4_sweepOpensDueCycleAsSystemPrincipal() {
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        org.flush();

        Cohort q2 = reviews.cohort("Q2 cohort", 2);
        reviews.member(q2, john);
        reviews.member(q2, aisha);
        ReviewCycle cycle = reviews.configuredCycle(2, LocalDate.of(2026, 5, 1));
        reviews.flush();

        // No security context at all. The sweep acts as the system principal: it bypasses the
        // caller checks because there is nobody to check, and is still bound by everything
        // below them.
        acting.clear();
        CycleService.SweepResult result = cycles.sweepDueCycles(LocalDate.of(2026, 5, 1));

        assertThat(result.cyclesOpened()).contains(cycle.label());
        assertThat(cycle.isOpened()).isTrue();
        assertThat(cycle.getStatus()).isEqualTo(CycleStatus.OPEN);
        assertThat(participants.existsByCycleIdAndSubjectId(cycle.getId(), john.getId())).isTrue();
        assertThat(participants.existsByCycleIdAndSubjectId(cycle.getId(), aisha.getId())).isTrue();
    }

    @Test
    @DisplayName("P-6.4: running the sweep twice opens the cycle once")
    void P_6_4_sweepIsIdempotent() {
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        Cohort q1 = reviews.cohort("Q1 cohort", 1);
        reviews.member(q1, john);
        ReviewCycle cycle = reviews.configuredCycle(1, LocalDate.of(2026, 1, 1));
        reviews.flush();

        acting.clear();
        LocalDate today = LocalDate.of(2026, 1, 5);

        CycleService.SweepResult first = cycles.sweepDueCycles(today);
        assertThat(first.cyclesOpened()).contains(cycle.label());
        assertThat(first.participantsAdded()).isEqualTo(1);

        // The second run finds nothing, because the selection predicate carries
        // opened_at IS NULL. There is no "already swept today" bookkeeping to fall out of
        // step - idempotency is a property of the query, not of a flag somebody maintains.
        CycleService.SweepResult second = cycles.sweepDueCycles(today);
        assertThat(second.cyclesOpened()).doesNotContain(cycle.label());
        assertThat(second.participantsAdded()).isZero();
    }

    @Test
    @DisplayName("P-6.4: a cycle whose date passed unswept opens on the next run")
    void P_6_4_pastDatedCycleOpensOnTheNextSweep() {
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        Cohort q3 = reviews.cohort("Q3 cohort", 3);
        reviews.member(q3, john);
        ReviewCycle cycle = reviews.configuredCycle(3, LocalDate.of(2026, 9, 1));
        reviews.flush();

        acting.clear();

        // Nothing ran on the day itself - the server was down, or the job failed.
        // Six weeks later the cycle is still due, because the predicate is "arrived or
        // passed", never "equals today". Written the other way, a missed run would skip the
        // cycle forever and nobody would find out until the quarter ended.
        CycleService.SweepResult result = cycles.sweepDueCycles(LocalDate.of(2026, 10, 15));

        assertThat(result.cyclesOpened()).contains(cycle.label());
        assertThat(participants.existsByCycleIdAndSubjectId(cycle.getId(), john.getId())).isTrue();
    }

    @Test
    @DisplayName("P-6.4: a cycle whose date has not arrived is left alone")
    void P_6_4_futureCycleIsNotOpened() {
        ReviewCycle cycle = reviews.configuredCycle(1, LocalDate.of(2026, 6, 1));
        reviews.flush();

        acting.clear();
        CycleService.SweepResult result = cycles.sweepDueCycles(LocalDate.of(2026, 5, 31));

        assertThat(result.cyclesOpened()).doesNotContain(cycle.label());
        assertThat(cycle.isOpened()).isFalse();
        assertThat(cycle.getStatus()).isEqualTo(CycleStatus.CONFIGURED);
    }

    @Test
    @DisplayName("P-0.7/P-1.5: intake skips deactivated employees and Leadership")
    void P_0_7_intakeSkipsDeactivatedAndLeadership() {
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser gone = org.deactivated("gone", Role.EMPLOYEE);
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        org.flush();

        Cohort q2 = reviews.cohort("Q2 cohort", 2);
        reviews.member(q2, john);
        reviews.member(q2, gone);
        reviews.member(q2, richard);
        ReviewCycle cycle = reviews.configuredCycle(2, LocalDate.of(2026, 5, 1));
        reviews.flush();

        acting.clear();
        CycleService.SweepResult result = cycles.sweepDueCycles(LocalDate.of(2026, 5, 1));

        // Both exclusions are in the intake query, so they hold for an unattended job as much
        // as for a person. P-1.5 in particular is enforced at creation and not on read: no
        // artifact for a Leadership member is ever brought into being at two in the morning
        // and then hidden afterwards.
        assertThat(result.participantsAdded()).isEqualTo(1);
        assertThat(participants.existsByCycleIdAndSubjectId(cycle.getId(), john.getId())).isTrue();
        assertThat(participants.existsByCycleIdAndSubjectId(cycle.getId(), gone.getId())).isFalse();
        assertThat(participants.existsByCycleIdAndSubjectId(cycle.getId(), richard.getId())).isFalse();
    }

    @Test
    @DisplayName("P-6.4: a cohort detached from the quadrimester is not swept in")
    void P_6_4_detachedCohortIsSkipped() {
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser omar = org.userIn(engineering, "omar", Role.EMPLOYEE);
        org.flush();

        Cohort attached = reviews.cohort("Q1 attached", 1);
        Cohort detached = reviews.cohort("Q1 detached", null);
        reviews.member(attached, john);
        reviews.member(detached, omar);
        ReviewCycle cycle = reviews.configuredCycle(1, LocalDate.of(2026, 1, 1));
        reviews.flush();

        acting.clear();
        cycles.sweepDueCycles(LocalDate.of(2026, 1, 1));

        // Detaching is how the Super Admin takes a group out of the rotation (scenario
        // section 4). It needs no second flag: a cohort attached to no quadrimester simply
        // matches nothing the sweep looks for.
        assertThat(participants.existsByCycleIdAndSubjectId(cycle.getId(), john.getId())).isTrue();
        assertThat(participants.existsByCycleIdAndSubjectId(cycle.getId(), omar.getId())).isFalse();
    }

    @Test
    @DisplayName("P-6.4: a cycle with no cohort attached opens empty rather than failing")
    void P_6_4_cycleWithNoCohortOpensEmpty() {
        ReviewCycle cycle = reviews.configuredCycle(2, LocalDate.of(2026, 5, 1));
        reviews.flush();

        acting.clear();
        CycleService.SweepResult result = cycles.sweepDueCycles(LocalDate.of(2026, 5, 1));

        // The misconfiguration scenario section 15.4 accepts. An open cycle with nobody in it
        // is visible to HR through monitoring and can be fixed; a sweep that aborted would be
        // silent, and would also strand every other cycle due the same morning.
        assertThat(result.cyclesOpened()).contains(cycle.label());
        assertThat(cycle.isOpened()).isTrue();
        assertThat(result.participantsAdded()).isZero();
    }

    @Test
    @DisplayName("P-6.4: intake snapshots the department the employee was in at the time")
    void P_6_4_intakeSnapshotsTheDepartment() {
        Department engineering = org.department("Engineering");
        Department support = org.department("Support");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        Cohort q1 = reviews.cohort("Q1 cohort", 1);
        reviews.member(q1, john);
        ReviewCycle cycle = reviews.configuredCycle(1, LocalDate.of(2026, 1, 1));
        reviews.flush();

        acting.clear();
        cycles.sweepDueCycles(LocalDate.of(2026, 1, 1));

        // He transfers after intake. The participant row must not follow him: HR scoping asks
        // which department the review WAS in, and an HR user who legitimately oversaw it does
        // not lose it because somebody moved desks in March.
        //
        // Reloaded rather than mutated in place: org.flush() clears the persistence context,
        // so the reference above is detached and a setter on it would change nothing.
        users.findById(john.getId()).orElseThrow()
                .setDepartment(departments.findById(support.getId()).orElseThrow());
        org.flush();

        CycleParticipant participant = participants
                .findByCycleIdAndSubjectId(cycle.getId(), john.getId()).orElseThrow();
        assertThat(participant.getDepartment().getId()).isEqualTo(engineering.getId());
    }
}
