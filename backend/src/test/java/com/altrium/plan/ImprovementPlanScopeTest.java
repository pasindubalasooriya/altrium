package com.altrium.plan;

// The OrgFixture field below is named `org`, which shadows the `org` package inside this
// class, so every com.altrium.org type has to arrive by import rather than fully qualified.
import com.altrium.org.AppUser;
import com.altrium.org.Department;
import com.altrium.org.HrGrantService;
import com.altrium.org.Role;
import com.altrium.review.Rating;
import com.altrium.testsupport.OrgFixture;
import com.altrium.testsupport.ReviewFixture;
import com.altrium.testsupport.StubJwtDecoderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HR queue of running improvement plans, added because HR had no way to <em>find</em> a
 * plan awaiting their co-signature: every other route starts from a person, and the review
 * list only names people under review in a cycle.
 *
 * <p>Its scope comes from {@code COSIGN_IMPROVEMENT_PLAN}, whose only ground is
 * {@code HR_IN_SCOPE}, so the list is exactly "plans you could act on" without a rule being
 * written for it. The two tests that matter most are the last two: the plan is visible to HR
 * <em>before</em> co-signature, while being invisible to its subject, and an HR user never
 * finds their own plan in it however explicit their grant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class ImprovementPlanScopeTest {

    private static final String PLANS = "/api/plans/improvement";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    @Autowired
    private ReviewFixture reviews;

    @Autowired
    private HrGrantService grants;

    private String bearer(String handle) {
        return "Bearer " + OrgFixture.tokenFor(handle);
    }

    /** Opens a plan for `subject` as their manager, so the service writes it, not a fixture. */
    private void openPlanFor(String managerHandle, AppUser subject) throws Exception {
        // Scenario section 5 step 7: a plan follows a completed review. Seeded, because these
        // tests are about who can see the plan afterwards, not about how the rating got there.
        reviews.releasedRating(reviews.openCycle(), subject,
                subject.getManager() == null ? subject : subject.getManager(),
                Rating.NEEDS_IMPROVEMENT);
        reviews.flush();

        mvc.perform(post(PLANS + "/" + subject.getId())
                        .header("Authorization", bearer(managerHandle))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consequenceClause":"Sustained improvement required.",
                                 "deadline":"%s"}
                                """.formatted(LocalDate.now().plusMonths(3))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("P-2.1: HR see running plans in a granted department, and not in others")
    void P_2_1_hrSeePlansOnlyInGrantedDepartments() throws Exception {
        Department engineering = org.department("Engineering-scope");
        Department sales = org.department("Sales-scope");
        Department people = org.department("People-scope");

        AppUser elena = org.userIn(engineering, "elena-scope", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-scope", Role.EMPLOYEE);
        john.setManager(elena);

        AppUser grace = org.userIn(sales, "grace-scope", Role.EMPLOYEE, Role.MANAGER);
        AppUser ben = org.userIn(sales, "ben-scope", Role.EMPLOYEE);
        ben.setManager(grace);

        AppUser hana = org.userIn(people, "hana-scope", Role.EMPLOYEE, Role.HR);
        org.flush();

        openPlanFor("elena-scope", john);
        openPlanFor("grace-scope", ben);

        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        mvc.perform(get(PLANS).header("Authorization", bearer("hana-scope")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userName").value(john.getFullName()))
                .andExpect(content().string(not(containsString(ben.getFullName()))));
    }

    @Test
    @DisplayName("P-5.3: HR see a plan before co-signature, which is the whole point of the queue")
    void P_5_3_hrSeeUncosignedPlansTheSubjectCannot() throws Exception {
        Department engineering = org.department("Engineering-uncosigned");
        Department people = org.department("People-uncosigned");
        AppUser elena = org.userIn(engineering, "elena-unc", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-unc", Role.EMPLOYEE);
        john.setManager(elena);
        AppUser hana = org.userIn(people, "hana-unc", Role.EMPLOYEE, Role.HR);
        org.flush();

        openPlanFor("elena-unc", john);
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        // HR must see it in order to review and co-sign it. A plan they cannot see is one they
        // cannot review, and the co-signature would become a rubber stamp.
        mvc.perform(get(PLANS).header("Authorization", bearer("hana-unc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].cosigned").value(false));

        // The subject sees nothing at all until it is co-signed, through their own route.
        mvc.perform(get(PLANS + "/me").header("Authorization", bearer("john-unc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPlan").value(false));
    }

    @Test
    @DisplayName("P-2.2: an HR Head with an explicit grant never finds their own plan in the queue")
    void P_2_2_hrOwnPlanIsAbsentFromTheirOwnQueue() throws Exception {
        Department people = org.department("People-own");
        AppUser richard = org.user("richard-own", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser kevin = org.userIn(people, "kevin-own", Role.EMPLOYEE, Role.HR, Role.MANAGER);
        kevin.setManager(richard);
        AppUser samuel = org.userIn(people, "samuel-own", Role.EMPLOYEE);
        samuel.setManager(kevin);
        org.flush();

        // Kevin's own plan, opened by his manager, who is Leadership.
        openPlanFor("richard-own", kevin);
        // And one for somebody else in the same department.
        openPlanFor("kevin-own", samuel);

        grants.grant(kevin.getId(), people.getId(), true, null, g -> g.getId());

        // The explicit grant lifts the own-department block, so Samuel's plan is his to
        // oversee. It does not lift the own-review block, and nothing ever does.
        mvc.perform(get(PLANS).header("Authorization", bearer("kevin-own")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userName").value(samuel.getFullName()))
                // Asserted on the subject specifically, not on the whole body: Kevin's name
                // legitimately appears as the person who opened Samuel's plan, and it is his
                // own plan - the one where he is the subject - that must be absent.
                .andExpect(jsonPath("$[?(@.userName == '" + kevin.getFullName() + "')]").isEmpty());
    }

    @Test
    @DisplayName("P-2.3: an HR user on a plain grant sees nothing in their own department")
    void P_2_3_plainGrantDoesNotReachTheirOwnDepartment() throws Exception {
        Department people = org.department("People-plain");
        AppUser kevin = org.userIn(people, "kevin-plain", Role.EMPLOYEE, Role.MANAGER);
        AppUser hana = org.userIn(people, "hana-plain", Role.EMPLOYEE, Role.HR);
        AppUser samuel = org.userIn(people, "samuel-plain", Role.EMPLOYEE);
        samuel.setManager(kevin);
        org.flush();

        openPlanFor("kevin-plain", samuel);
        grants.grant(hana.getId(), people.getId(), false, null, g -> g.getId());

        mvc.perform(get(PLANS).header("Authorization", bearer("hana-plain")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("a manager gets an empty queue, not a denial - they have no HR scope")
    void managerGetsAnEmptyQueueRatherThanADenial() throws Exception {
        Department engineering = org.department("Engineering-mgr");
        AppUser elena = org.userIn(engineering, "elena-mgr", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-mgr", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        openPlanFor("elena-mgr", john);

        // Having no scope is not the same as being refused, and it is the same shape the
        // scoped review list takes for somebody who may see nothing. Elena still reaches her
        // own report's plan through the person, as she already did.
        mvc.perform(get(PLANS).header("Authorization", bearer("elena-mgr")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("P-9.4: the Super Admin gets nothing, having no HR scope and no review access")
    void P_9_4_superAdminGetsNothing() throws Exception {
        Department engineering = org.department("Engineering-admin");
        AppUser elena = org.userIn(engineering, "elena-admin", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-admin", Role.EMPLOYEE);
        john.setManager(elena);
        org.userIn(engineering, "devin-admin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        openPlanFor("elena-admin", john);

        mvc.perform(get(PLANS).header("Authorization", bearer("devin-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
