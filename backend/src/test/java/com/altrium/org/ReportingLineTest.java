package com.altrium.org;

import com.altrium.config.ValidationApiException;
import com.altrium.testsupport.OrgFixture;
import com.altrium.testsupport.StubJwtDecoderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature 2 - reporting lines (P-1.4) and soft delete (P-0.7).
 *
 * <p>The loop check is not tidiness. Every direct-reports query and every chain walk in the
 * authorization layer assumes the reporting graph is acyclic; a cycle makes traversal
 * non-terminating, so a single bad assignment can hang the server on an ordinary request.
 */
@SpringBootTest
@ActiveProfiles("test")
// Needed even though this test never sends a request: the resource server cannot build a
// JwtDecoder without an issuer, so without the stub the whole context fails to start.
@Import(StubJwtDecoderConfig.class)
@Transactional
class ReportingLineTest {

    @Autowired
    private OrgService org;

    @Autowired
    private OrgFixture fixture;

    @Autowired
    private AppUserRepository users;

    @Test
    @DisplayName("P-1.4: a user cannot report to themselves")
    void P_1_4_selfManagementRejected() {
        AppUser solo = fixture.user("solo", Role.EMPLOYEE);
        fixture.flush();

        assertThatThrownBy(() -> org.setManager(solo.getId(), solo.getId()))
                .isInstanceOf(ValidationApiException.class)
                .hasMessageContaining("cannot report to themselves");
    }

    @Test
    @DisplayName("P-1.4: a two-person cycle is rejected")
    void P_1_4_directCycleRejected() {
        AppUser ana = fixture.user("ana", Role.EMPLOYEE);
        AppUser ben = fixture.user("ben", Role.EMPLOYEE, Role.MANAGER);
        fixture.flush();

        org.setManager(ana.getId(), ben.getId());

        // Ben now reporting to Ana would close the loop.
        assertThatThrownBy(() -> org.setManager(ben.getId(), ana.getId()))
                .isInstanceOf(ValidationApiException.class)
                .hasMessageContaining("reporting loop");
    }

    @Test
    @DisplayName("P-1.4: a longer cycle further up the chain is rejected")
    void P_1_4_indirectCycleRejected() {
        AppUser junior = fixture.user("junior", Role.EMPLOYEE);
        AppUser lead = fixture.user("lead", Role.EMPLOYEE, Role.MANAGER);
        AppUser head = fixture.user("head", Role.EMPLOYEE, Role.MANAGER);
        fixture.flush();

        org.setManager(junior.getId(), lead.getId());
        org.setManager(lead.getId(), head.getId());

        // head -> junior would make the chain junior -> lead -> head -> junior. A check that
        // only looked one level up would miss this.
        assertThatThrownBy(() -> org.setManager(head.getId(), junior.getId()))
                .isInstanceOf(ValidationApiException.class)
                .hasMessageContaining("reporting loop");
    }

    @Test
    @DisplayName("a legitimate deep chain is still allowed")
    void deepChainAllowed() {
        AppUser a = fixture.user("a", Role.EMPLOYEE);
        AppUser b = fixture.user("b", Role.EMPLOYEE, Role.MANAGER);
        AppUser c = fixture.user("c", Role.EMPLOYEE, Role.MANAGER);
        fixture.flush();

        org.setManager(a.getId(), b.getId());
        org.setManager(b.getId(), c.getId());

        assertThat(users.findById(a.getId()).orElseThrow().getManager().getId()).isEqualTo(b.getId());
        assertThat(users.findById(b.getId()).orElseThrow().getManager().getId()).isEqualTo(c.getId());
    }

    @Test
    @DisplayName("P-1.1: direct reports are direct only, never transitive")
    void P_1_1_directReportsAreNotTransitive() {
        AppUser grandchild = fixture.user("grandchild", Role.EMPLOYEE);
        AppUser child = fixture.user("child", Role.EMPLOYEE, Role.MANAGER);
        AppUser top = fixture.user("top", Role.EMPLOYEE, Role.MANAGER);
        fixture.flush();

        org.setManager(grandchild.getId(), child.getId());
        org.setManager(child.getId(), top.getId());

        // The skip-level report must not appear. If it did, a manager would silently gain
        // access to everyone beneath them rather than their own reports (P-1.2).
        assertThat(org.directReports(top.getId()))
                .extracting(AppUser::getId)
                .containsExactly(child.getId())
                .doesNotContain(grandchild.getId());
    }

    @Test
    @DisplayName("a deactivated user cannot be assigned as someone's manager")
    void deactivatedManagerRejected() {
        AppUser gone = fixture.deactivated("gone", Role.EMPLOYEE, Role.MANAGER);
        AppUser staying = fixture.user("staying", Role.EMPLOYEE);
        fixture.flush();

        assertThatThrownBy(() -> org.setManager(staying.getId(), gone.getId()))
                .isInstanceOf(ValidationApiException.class)
                .hasMessageContaining("deactivated");
    }

    @Test
    @DisplayName("P-0.7: deactivating keeps the row and reports the dangling reports")
    void P_0_7_deactivationIsSoft() {
        AppUser boss = fixture.user("boss", Role.EMPLOYEE, Role.MANAGER);
        AppUser report = fixture.user("report", Role.EMPLOYEE);
        fixture.flush();

        org.setManager(report.getId(), boss.getId());

        OrgService.DeactivationResult result = org.deactivate(boss.getId());

        assertThat(result.user().isActive()).isFalse();
        // The row survives - deleting it would orphan every artifact they authored and
        // destroy the history the carry-over feature depends on.
        assertThat(users.findById(boss.getId())).isPresent();
        // Reports are left dangling on purpose rather than silently re-pointed: guessing a
        // new manager would rewrite the org chart as a side effect of a deactivation.
        assertThat(result.danglingReports()).isEqualTo(1);
    }

    @Test
    @DisplayName("P-0.1: every user gets EMPLOYEE even if it is not requested")
    void P_0_1_employeeRoleIsAlwaysPresent() {
        AppUser created = org.createUser(
                "sub-hr-only", "hr-only@altrium.test", "HR Only",
                null, null, java.util.EnumSet.of(Role.HR));
        fixture.flush();

        assertThat(users.findById(created.getId()).orElseThrow().getRoles())
                .contains(Role.EMPLOYEE, Role.HR);
    }
}
