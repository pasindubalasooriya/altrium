package com.altrium.org.seed;

import com.altrium.org.AppUser;
import com.altrium.org.AppUserRepository;
import com.altrium.org.HrDepartmentGrantRepository;
import com.altrium.org.HrGrantService;
import com.altrium.org.OrgService;
import com.altrium.org.Role;
import com.altrium.testsupport.StubJwtDecoderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seed organisation is test infrastructure, so it needs to be correct for the same
 * reason the code does: every denial test written from here on is only as trustworthy as the
 * structure it runs against. A seed that quietly stopped producing a deactivated user, or an
 * HR user inside their own department, would turn several later tests into ones that pass
 * because there is nothing to catch.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class SeedDataTest {

    @Autowired
    private OrgService org;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private HrGrantService hrGrants;

    @Autowired
    private HrDepartmentGrantRepository grantRepository;

    private SeedData seed;

    @BeforeEach
    void setUp() {
        seed = new SeedData(org, users, hrGrants, grantRepository);
        seed.run(null);
    }

    @Test
    @DisplayName("seeds 31 people across 4 departments")
    void seedsWholeOrganisation() {
        assertThat(users.count()).isEqualTo(31);
        assertThat(org.listDepartments()).hasSize(4);
    }

    @Test
    @DisplayName("running the seed twice does not duplicate the organisation")
    void seedIsIdempotent() {
        // A tester re-running the app must not end up with two of everybody. The guard is a
        // check for a known subject, so it survives a restart rather than only a fresh schema.
        seed.run(null);
        assertThat(users.count()).isEqualTo(31);
    }

    @Test
    @DisplayName("P-7.2: Leadership sit above the structure - no manager, no department")
    void P_7_2_leadershipHaveNoManagerOrDepartment() {
        List<AppUser> leadership = users.findAll().stream()
                .filter(u -> u.getRoles().contains(Role.LEADERSHIP))
                .toList();

        assertThat(leadership).hasSize(3);
        assertThat(leadership).allSatisfy(chief -> {
            assertThat(chief.getManager()).isNull();
            assertThat(chief.getDepartment()).isNull();
        });
    }

    @Test
    @DisplayName("P-2.6: the HR Head reports to Leadership, not to another HR user")
    void P_2_6_hrHeadReportsToLeadership() {
        // This is what keeps the own-review block absolute without leaving the HR Head
        // unreviewed: someone outside HR has to conduct their review.
        AppUser kevin = users.findByEmail("kevin@altrium.test").orElseThrow();

        assertThat(kevin.getRoles()).contains(Role.HR);
        assertThat(kevin.getManager()).isNotNull();
        assertThat(kevin.getManager().getRoles()).contains(Role.LEADERSHIP);
    }

    @Test
    @DisplayName("ordinary HR sit inside the department they would otherwise oversee")
    void ordinaryHrSitInsideTheirOwnDepartment() {
        // Without this the self-exclusion rules (P-2.2, P-2.3) would have nothing to bite on
        // and their denial tests would pass vacuously.
        AppUser hana = users.findByEmail("hana@altrium.test").orElseThrow();

        assertThat(hana.getRoles()).contains(Role.HR);
        assertThat(hana.getDepartment().getName()).isEqualTo("People Operations");
    }

    @Test
    @DisplayName("P-0.7: the seed includes a deactivated user whose row still exists")
    void P_0_7_includesDeactivatedUser() {
        AppUser tara = users.findByEmail("tara@altrium.test").orElseThrow();

        assertThat(tara.isActive()).isFalse();
        assertThat(tara.getManager()).isNotNull();
    }

    @Test
    @DisplayName("P-9.5: the Super Admin is a dedicated account, outside the hierarchy")
    void P_9_5_superAdminIsADedicatedAccount() {
        // A Product Owner ruling, replacing the earlier seeding in which Devin was an ordinary
        // engineer who also administered. No department and no manager, because both exist to
        // place a reviewee - a department is the unit of HR scoping, a manager is who reviews
        // you - and this account is never reviewed.
        AppUser devin = users.findByEmail("devin@altrium.test").orElseThrow();

        assertThat(devin.getRoles()).containsExactlyInAnyOrder(Role.SUPER_ADMIN, Role.EMPLOYEE);
        assertThat(devin.getManager()).isNull();
        assertThat(devin.getDepartment()).isNull();
    }

    @Test
    @DisplayName("P-9.5: Employee survives on it, because that marker is what admits any caller")
    void P_9_5_theEmployeeMarkerSurvives() {
        // Employee is not a job in this schema. SecurityConfig requires the authority on every
        // authenticated endpoint, so an account without it could not reach the administration
        // console it exists to use. What P-9.5 removes is being a reviewee, enforced at step 2
        // of the evaluation order rather than by taking this marker away.
        AppUser devin = users.findByEmail("devin@altrium.test").orElseThrow();

        assertThat(devin.getRoles()).contains(Role.EMPLOYEE);
        assertThat(devin.getRoles()).doesNotContain(Role.MANAGER, Role.HR, Role.LEADERSHIP);
    }

    @Test
    @DisplayName("P-1.1: the hierarchy is deep enough to expose skip-level bugs")
    void P_1_1_hierarchyHasSkipLevels() {
        // John -> Jane -> Elena -> Priya. A manager query that accidentally walked the chain
        // would hand Elena access to John, so the seed must contain the case.
        AppUser john = users.findByEmail("john@altrium.test").orElseThrow();
        AppUser jane = john.getManager();
        AppUser elena = jane.getManager();

        assertThat(jane.getFullName()).isEqualTo("Jane Okafor");
        assertThat(elena.getFullName()).isEqualTo("Elena Vasquez");
        assertThat(org.directReports(elena.getId()))
                .extracting(AppUser::getFullName)
                .doesNotContain("John Alvarez");
    }
}
