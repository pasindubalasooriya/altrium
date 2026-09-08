package com.altrium.plan;

import com.altrium.org.AppUser;
import com.altrium.org.AppUserRepository;
import com.altrium.org.Department;
import com.altrium.org.DepartmentRepository;
import com.altrium.org.OrgService;
import com.altrium.org.Role;
import com.altrium.testsupport.OrgFixture;
import com.altrium.testsupport.StubJwtDecoderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.EnumSet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The employee's own improvement plan, read with <strong>no test transaction</strong>.
 *
 * <p>The third class of its kind, after {@code UserListingOutsideTransactionTest} and
 * {@code ReviewRecordOutsideTransactionTest}, and it exists because the same bug reached the
 * browser a third time. {@code GET /api/plans/improvement/me} returned 500 on John's plan
 * screen: the controller converted the entity after the service transaction had closed, and
 * {@code ImprovementPlanView.of} reads {@code plan.getUser().getFullName()} and
 * {@code plan.getOpenedBy().getFullName()} - lazy proxies on a dead session. The employee saw
 * an empty tab; nothing in the console said why.
 *
 * <p>Every other improvement-plan test passed throughout, and would have gone on passing. They
 * are {@code @Transactional}, which holds a Hibernate session open for the whole test method,
 * so the proxies still resolve and the mapping site looks correct. Only a request made without
 * one can tell the difference, which is the entire reason this file has no annotation.
 *
 * <p>It sits in {@code com.altrium.plan} rather than beside the other two in
 * {@code com.altrium.web.plan} because {@link ImprovementPlan#cosign} is package-private -
 * only {@code PlanService} is meant to co-sign a plan - and the read being tested is gated on
 * a plan that has been co-signed (P-5.3).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
class OwnImprovementPlanOutsideTransactionTest {

    private static final String MY_PLAN = "/api/plans/improvement/me";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgService org;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private DepartmentRepository departments;

    @Autowired
    private ImprovementPlanRepository plans;

    private Long departmentId;
    private Long managerId;
    private Long subjectId;
    private Long hrId;
    private Long planId;

    @BeforeEach
    void setUp() {
        Department department = org.createDepartment("Lazy-Pip-Dept-" + System.nanoTime());
        departmentId = department.getId();

        AppUser manager = org.createUser(
                OrgFixture.subjectOf("lazy-pip-mgr"), "lazy-pip-mgr@altrium.test",
                "Lazy Pip Manager", departmentId, null, EnumSet.of(Role.MANAGER));
        managerId = manager.getId();

        AppUser hr = org.createUser(
                OrgFixture.subjectOf("lazy-pip-hr"), "lazy-pip-hr@altrium.test",
                "Lazy Pip Hr", departmentId, null, EnumSet.of(Role.HR));
        hrId = hr.getId();

        AppUser subject = org.createUser(
                OrgFixture.subjectOf("lazy-pip-sub"), "lazy-pip-sub@altrium.test",
                "Lazy Pip Subject", departmentId, managerId, EnumSet.noneOf(Role.class));
        subjectId = subject.getId();

        // Co-signed, because an employee may not read their plan before that (P-5.3) and an
        // uncosigned one would answer "no plan" without ever touching the proxies.
        ImprovementPlan plan = new ImprovementPlan(
                subject, manager, LocalDate.now().plusMonths(2), "Sustained improvement required");
        plan.cosign(hr);
        planId = plans.save(plan).getId();
    }

    @AfterEach
    void tearDown() {
        // Nothing rolls back here. Null-guarded, because a setUp that fails part way through
        // still runs this, and throwing would leave the rest of the rows behind.
        if (planId != null) {
            plans.findById(planId).ifPresent(plans::delete);
        }
        if (subjectId != null) {
            users.findById(subjectId).ifPresent(users::delete);
        }
        if (hrId != null) {
            users.findById(hrId).ifPresent(users::delete);
        }
        if (managerId != null) {
            users.findById(managerId).ifPresent(users::delete);
        }
        if (departmentId != null) {
            departments.findById(departmentId).ifPresent(departments::delete);
        }
    }

    @Test
    @DisplayName("P-5.3: the employee reads their co-signed plan without a LazyInitializationException")
    void P_5_3_subjectReadsOwnCosignedPlan() throws Exception {
        mvc.perform(get(MY_PLAN)
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("lazy-pip-sub")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPlan").value(true))
                // The three fields that blew up, asserted by name rather than settling for a
                // 200: the subject's name, the opener's name, and the goal collection - each
                // one a lazy load the old mapping site performed on a closed session.
                .andExpect(jsonPath("$.plan.userName").value("Lazy Pip Subject"))
                .andExpect(jsonPath("$.plan.openedBy").value("Lazy Pip Manager"))
                .andExpect(jsonPath("$.plan.cosignedBy").value("Lazy Pip Hr"))
                .andExpect(jsonPath("$.plan.goals").isArray());
    }

    /**
     * The gate still holds outside a transaction. If the fix had been made by widening the
     * transaction around the controller rather than by moving the mapping into the service,
     * this would keep passing while the one above changed meaning.
     */
    @Test
    @DisplayName("P-5.3: somebody with no plan gets the same shape, not an error")
    void P_5_3_noPlanIsNotAnError() throws Exception {
        mvc.perform(get(MY_PLAN)
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("lazy-pip-mgr")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPlan").value(false));
    }
}
