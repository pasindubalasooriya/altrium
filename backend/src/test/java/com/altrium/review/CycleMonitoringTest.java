package com.altrium.review;

// The OrgFixture field below is named `org`, which shadows the `org` package inside this
// class, so every com.altrium.org type has to arrive by import rather than fully qualified.
import com.altrium.org.AppUser;
import com.altrium.org.Department;
import com.altrium.org.HrGrantService;
import com.altrium.org.Role;
import com.altrium.testsupport.OrgFixture;
import com.altrium.testsupport.ReviewFixture;
import com.altrium.testsupport.StubJwtDecoderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature 6 - HR cycle monitoring (P-6.3).
 *
 * <p>An aggregate is the easiest place in a system like this to leak. No forbidden row appears
 * in the response, so nothing looks wrong, and a total computed over rows the caller may not
 * see passes review comfortably. These tests assert the numbers themselves, because the number
 * is the only place the mistake would show.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class CycleMonitoringTest {

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

    private static String monitoring(ReviewCycle cycle) {
        return "/api/cycles/" + cycle.getId() + "/monitoring";
    }

    @Test
    @DisplayName("P-6.3: HR sees counts for granted departments and nothing for the rest")
    void P_6_3_monitoringIsScopedToGrantedDepartments() throws Exception {
        Department engineering = org.department("Engineering");
        Department support = org.department("Support");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser omar = org.userIn(support, "omar", Role.EMPLOYEE);
        john.setManager(elena);
        aisha.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.participant(cycle, aisha);
        reviews.participant(cycle, omar);
        reviews.selfReview(cycle, john, "shipped the migration");
        reviews.managerReview(cycle, john, elena, "strong quarter");
        reviews.flush();

        // Engineering only. Support is a department she holds no grant for, and it does not
        // appear as a zero row either - a zero would still confirm the department exists and
        // has a cycle running in it.
        mvc.perform(get(monitoring(cycle)).header("Authorization", bearer("hana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments.length()").value(1))
                .andExpect(jsonPath("$.departments[0].departmentName")
                        .value(engineering.getName()))
                .andExpect(jsonPath("$.departments[0].participants").value(2))
                .andExpect(jsonPath("$.departments[0].selfReviewsSubmitted").value(1))
                .andExpect(jsonPath("$.departments[0].managerReviewsSubmitted").value(1));
    }

    @Test
    @DisplayName("P-2.3: a plain grant over one's own department buys no monitoring")
    void P_2_3_ownDepartmentIsNotMonitoredWithoutAnExplicitGrant() throws Exception {
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser samuel = org.userIn(peopleOps, "samuel", Role.EMPLOYEE);
        org.flush();
        grants.grant(hana.getId(), peopleOps.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, samuel);
        reviews.flush();

        // She holds a grant that names the department and it still buys her nothing, which is
        // what makes this a segregation of duties rather than a formality.
        mvc.perform(get(monitoring(cycle)).header("Authorization", bearer("hana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments.length()").value(0));

        mvc.perform(get(monitoring(cycle) + "/" + peopleOps.getId())
                        .header("Authorization", bearer("hana")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-2.4/P-2.2: the HR Head monitors their own department, minus their own case")
    void P_2_4_explicitGrantMonitorsOwnDepartmentButNotOwnCase() throws Exception {
        Department peopleOps = org.department("People Operations");
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser kevin = org.userIn(peopleOps, "kevin", Role.EMPLOYEE, Role.HR);
        AppUser samuel = org.userIn(peopleOps, "samuel", Role.EMPLOYEE);
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        kevin.setManager(richard);
        org.flush();
        grants.grant(kevin.getId(), peopleOps.getId(), true, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, kevin);
        reviews.participant(cycle, samuel);
        reviews.participant(cycle, hana);
        // His own review is well advanced. None of it may reach his monitoring figures.
        reviews.selfReview(cycle, kevin, "held the function together");
        reviews.managerReview(cycle, kevin, richard, "steady stewardship");
        reviews.unreleasedRating(cycle, kevin, richard, Rating.EXCEEDS_EXPECTATIONS);
        reviews.flush();

        // Three people are under review in People Operations; he oversees two of them.
        // The explicit grant lifts the department block (P-2.4) and cannot lift the own-review
        // block (P-2.2), so his own row is excluded from the total and from every count on it.
        //
        // The rating is the sharp one. It is set and not yet released, and a monitoring figure
        // of ratingsSet = 1 would tell him a rating exists for him before P-4.4 lets him see
        // it - a disclosure with no row in the response to give it away.
        mvc.perform(get(monitoring(cycle) + "/" + peopleOps.getId())
                        .header("Authorization", bearer("kevin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments[0].participants").value(2))
                .andExpect(jsonPath("$.departments[0].selfReviewsSubmitted").value(0))
                .andExpect(jsonPath("$.departments[0].managerReviewsSubmitted").value(0))
                .andExpect(jsonPath("$.departments[0].ratingsSet").value(0));
    }

    @Test
    @DisplayName("P-6.3: a manager gets no monitoring, by name or otherwise")
    void P_6_3_managerCannotMonitor() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        // She may read John's review as his manager. Monitoring is a different capability with
        // different grounds, and holding one does not imply the other.
        mvc.perform(get(monitoring(cycle) + "/" + engineering.getId())
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isForbidden());

        mvc.perform(get(monitoring(cycle)).header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments.length()").value(0));
    }

    @Test
    @DisplayName("P-9.4: the Super Admin monitors nothing, having configured all of it")
    void P_9_4_superAdminCannotMonitor() throws Exception {
        Department engineering = org.department("Engineering");
        org.userIn(engineering, "devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        // They decide who is reviewed and when, and see none of what comes back. Counts are
        // less than content, but "three of eight rated NEEDS_IMPROVEMENT" is content enough.
        mvc.perform(get(monitoring(cycle) + "/" + engineering.getId())
                        .header("Authorization", bearer("devin")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-2.5: a grant revoked mid-session removes the department from the next report")
    void P_2_5_revokedGrantAppliesToTheNextMonitoringRequest() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        mvc.perform(get(monitoring(cycle)).header("Authorization", bearer("hana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments.length()").value(1));

        grants.revoke(hana.getId(), engineering.getId());

        // Same token, no re-login. The scope is resolved per request here exactly as it is on
        // the review endpoints, because it is the same resolver.
        mvc.perform(get(monitoring(cycle)).header("Authorization", bearer("hana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments.length()").value(0));
    }

    @Test
    @DisplayName("P-6.3: a cycle that has not opened reports as not fired")
    void P_6_3_unopenedCycleIsVisibleAsNotFired() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.configuredCycle(1, java.time.LocalDate.of(2040, 1, 1));
        reviews.flush();

        // The trade-off scenario section 15.4 accepts: a cycle the Super Admin never
        // configured properly does not open, and HR find out here rather than from an
        // employee asking why nobody reviewed them.
        mvc.perform(get(monitoring(cycle)).header("Authorization", bearer("hana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycle.fired").value(false))
                .andExpect(jsonPath("$.cycle.status").value("CONFIGURED"))
                .andExpect(jsonPath("$.departments.length()").value(0));
    }

    @Test
    @DisplayName("P-6.3: peer progress is counted without multiplying the other figures")
    void P_6_3_peerCountsDoNotDistortTheRow() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser mei = org.userIn(engineering, "mei", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.selfReview(cycle, john, "shipped the migration");
        reviews.assignPeer(cycle, john, aisha, elena);
        reviews.assignPeer(cycle, john, mei, elena);
        reviews.peerReview(cycle, john, aisha, "great to pair with");
        reviews.flush();

        // Two peers per subject, so joining them into the main row would report two
        // participants and two self-reviews for one person. Counted separately for that
        // reason, and asserted here because the failure is silent arithmetic, not an error.
        mvc.perform(get(monitoring(cycle)).header("Authorization", bearer("hana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments[0].participants").value(1))
                .andExpect(jsonPath("$.departments[0].selfReviewsSubmitted").value(1))
                .andExpect(jsonPath("$.departments[0].peerAssignmentsMade").value(2))
                .andExpect(jsonPath("$.departments[0].peerReviewsSubmitted").value(1));
    }
}
