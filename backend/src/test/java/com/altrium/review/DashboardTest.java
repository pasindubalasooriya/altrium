package com.altrium.review;

import com.altrium.org.AppUser;
import com.altrium.org.Department;
import com.altrium.org.HrGrantService;
import com.altrium.org.Role;
import com.altrium.testsupport.OrgFixture;
import com.altrium.testsupport.ReviewFixture;
import com.altrium.testsupport.StubJwtDecoderConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature 18 - the manager's and HR's dashboards, over HTTP.
 *
 * <p>An aggregate is the easiest place in this system to leak somebody quietly. No row appears,
 * no name is returned, and a count that includes one person too many looks exactly like a count
 * that does not. So what these prove is mostly arithmetic: that a total is the number of people
 * the caller may open, and never one more.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class DashboardTest {

    private static final String DASHBOARD = "/api/dashboard";

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

    // ------------------------------------------------------------------ the manager

    @Test
    @DisplayName("P-1.1: a manager's totals count their direct reports and nobody else")
    void P_1_1_managerTotalsAreTheirReports() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser omar = org.userIn(engineering, "omar", Role.EMPLOYEE);
        john.setManager(jane);
        aisha.setManager(jane);
        omar.setManager(elena);
        jane.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        for (AppUser person : new AppUser[]{jane, john, aisha, omar}) {
            reviews.participant(cycle, person);
        }
        reviews.selfReview(cycle, john, "A year of work.");
        reviews.releasedRating(cycle, john, jane, Rating.MEETS_EXPECTATIONS);
        reviews.unreleasedRating(cycle, aisha, jane, Rating.EXCEEDS_EXPECTATIONS);
        // Elena's report, in the same cycle and the same department, and none of Jane's business.
        reviews.selfReview(cycle, omar, "Also a year of work.");
        reviews.releasedRating(cycle, omar, elena, Rating.NEEDS_IMPROVEMENT);
        reviews.flush();

        mvc.perform(get(DASHBOARD).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("jane")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeIsEmpty").value(false))
                // Two, not three: Omar is not hers. Not four either - Jane is a participant in
                // this cycle herself, and a manager is not one of their own reports.
                .andExpect(jsonPath("$.participants").value(2))
                .andExpect(jsonPath("$.selfReviewsSubmitted").value(1))
                .andExpect(jsonPath("$.ratingsSet").value(2))
                .andExpect(jsonPath("$.ratingsReleased").value(1))
                // Omar's NEEDS_IMPROVEMENT is in the same cycle and must not appear anywhere in
                // Jane's distribution, which is the aggregate version of the same leak.
                .andExpect(jsonPath("$.ratingDistribution.MEETS_EXPECTATIONS").value(1))
                .andExpect(jsonPath("$.ratingDistribution.EXCEEDS_EXPECTATIONS").value(1))
                .andExpect(jsonPath("$.ratingDistribution.NEEDS_IMPROVEMENT").doesNotExist());
    }

    @Test
    @DisplayName("A rating nobody was given is absent, not zero")
    void unusedRatingsAreAbsentFromTheDistribution() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.releasedRating(cycle, john, jane, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        mvc.perform(get(DASHBOARD).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("jane")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratingDistribution.MEETS_EXPECTATIONS").value(1))
                .andExpect(jsonPath("$.ratingDistribution.NEEDS_IMPROVEMENT").doesNotExist())
                .andExpect(jsonPath("$.ratingDistribution.EXCEEDS_EXPECTATIONS").doesNotExist());
    }

    @Test
    @DisplayName("P-3.2: peer submissions are counted once each, not once per pair")
    void peerSubmissionsAreNotMultipliedByTheJoin() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser diego = org.userIn(engineering, "diego", Role.EMPLOYEE);
        john.setManager(jane);
        aisha.setManager(jane);
        diego.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        for (AppUser person : new AppUser[]{john, aisha, diego}) {
            reviews.participant(cycle, person);
        }
        reviews.selfReview(cycle, john, "Written.");
        reviews.peerReview(cycle, john, aisha, "Good to work with.");
        reviews.peerReview(cycle, john, diego, "Reliable.");
        reviews.flush();

        // Two peer reviews and one self-review. If the peer table were joined into the totals
        // query, John's single self-review would be counted once per peer row and report 2.
        mvc.perform(get(DASHBOARD).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("jane")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants").value(3))
                .andExpect(jsonPath("$.peerReviewsSubmitted").value(2))
                .andExpect(jsonPath("$.selfReviewsSubmitted").value(1));
    }

    // ------------------------------------------------------------------ HR

    @Test
    @DisplayName("P-2.1: HR totals cover granted departments and exclude the rest")
    void P_2_1_hrTotalsAreScopedToGrants() throws Exception {
        Department engineering = org.department("Engineering");
        Department sales = org.department("Sales");
        AppUser hana = org.userIn(org.department("People Operations"), "hana", Role.EMPLOYEE, Role.HR);
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser grace = org.userIn(sales, "grace", Role.EMPLOYEE, Role.MANAGER);
        AppUser nadia = org.userIn(sales, "nadia", Role.EMPLOYEE);
        john.setManager(jane);
        nadia.setManager(grace);
        org.flush();

        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.participant(cycle, nadia);
        reviews.releasedRating(cycle, john, jane, Rating.MEETS_EXPECTATIONS);
        reviews.releasedRating(cycle, nadia, grace, Rating.NEEDS_IMPROVEMENT);
        reviews.flush();

        mvc.perform(get(DASHBOARD).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("hana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants").value(1))
                .andExpect(jsonPath("$.ratingDistribution.MEETS_EXPECTATIONS").value(1))
                .andExpect(jsonPath("$.ratingDistribution.NEEDS_IMPROVEMENT").doesNotExist());
    }

    @Test
    @DisplayName("P-2.2: a granted HR Head's own row is excluded from their own totals")
    void P_2_2_ownRowIsExcludedFromTheTotals() throws Exception {
        Department peopleOps = org.department("People Operations");
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser kevin = org.userIn(peopleOps, "kevin", Role.EMPLOYEE, Role.HR, Role.MANAGER);
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE);
        AppUser samuel = org.userIn(peopleOps, "samuel", Role.EMPLOYEE);
        kevin.setManager(richard);
        hana.setManager(kevin);
        samuel.setManager(kevin);
        org.flush();

        grants.grant(kevin.getId(), peopleOps.getId(), true, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        for (AppUser person : new AppUser[]{kevin, hana, samuel}) {
            reviews.participant(cycle, person);
        }
        reviews.releasedRating(cycle, kevin, richard, Rating.EXCEEDS_EXPECTATIONS);
        reviews.releasedRating(cycle, hana, kevin, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        // Three people are under review in his department and he oversees two of them. His own
        // EXCEEDS_EXPECTATIONS must not appear in the distribution he is looking at: a
        // department of three where one bar is unaccounted for is a short step from reading it.
        mvc.perform(get(DASHBOARD).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("kevin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants").value(2))
                .andExpect(jsonPath("$.ratingDistribution.MEETS_EXPECTATIONS").value(1))
                .andExpect(jsonPath("$.ratingDistribution.EXCEEDS_EXPECTATIONS").doesNotExist());
    }

    /**
     * The dashboard's totals and HR's cycle monitoring reach the same rows down two different
     * code paths - a hand-written aggregate here, the ordinary scoped query there. Nothing but
     * this test stops them drifting into two screens that quietly disagree about how many people
     * are under review.
     */
    @Test
    @DisplayName("The dashboard's participant total equals cycle monitoring's, for the same HR user")
    void dashboardAgreesWithCycleMonitoring() throws Exception {
        Department engineering = org.department("Engineering");
        Department sales = org.department("Sales");
        AppUser hana = org.userIn(org.department("People Operations"), "hana", Role.EMPLOYEE, Role.HR);
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser grace = org.userIn(sales, "grace", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser nadia = org.userIn(sales, "nadia", Role.EMPLOYEE);
        john.setManager(jane);
        aisha.setManager(jane);
        nadia.setManager(grace);
        org.flush();

        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());
        grants.grant(hana.getId(), sales.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        for (AppUser person : new AppUser[]{john, aisha, nadia}) {
            reviews.participant(cycle, person);
        }
        reviews.selfReview(cycle, john, "Written.");
        reviews.flush();

        // Read through the endpoint rather than the service, so both numbers are produced for
        // the same caller by the same request path a screen would use.
        String monitoringJson = mvc
                .perform(get("/api/cycles/" + cycle.getId() + "/monitoring")
                        .header("Authorization", bearer("hana")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Integer> perDepartment = JsonPath.read(monitoringJson, "$.departments[*].participants");
        int fromMonitoring = perDepartment.stream().mapToInt(Integer::intValue).sum();

        mvc.perform(get(DASHBOARD).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("hana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants").value(fromMonitoring));
    }

    // ------------------------------------------------------------------ everybody else

    @Test
    @DisplayName("P-7.1: Leadership get an empty dashboard here, not a denial and not the company")
    void P_7_1_leadershipGetAnEmptyDashboard() throws Exception {
        Department engineering = org.department("Engineering");
        org.user("marcus", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.releasedRating(cycle, john, jane, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        // Their metrics are the leadership endpoint, which is unscoped by department. Answering
        // here with the organisation's totals would be a second, unscoped route to the same
        // numbers, and the day one of them gained a department breakdown they would disagree.
        mvc.perform(get(DASHBOARD).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("marcus")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeIsEmpty").value(true))
                .andExpect(jsonPath("$.participants").value(0))
                .andExpect(jsonPath("$.ratingDistribution").isEmpty());
    }

    @Test
    @DisplayName("Section 6: an employee has no dashboard, and gets an empty one rather than theirs")
    void employeeHasNoDashboard() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.releasedRating(cycle, john, jane, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        // His own SELF ground is removed before the query runs, so his own rating is not here
        // either. A one-person dashboard of yourself is a rating readout by another name.
        mvc.perform(get(DASHBOARD).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeIsEmpty").value(true))
                .andExpect(jsonPath("$.participants").value(0))
                .andExpect(jsonPath("$.ratingDistribution").isEmpty());
    }

    @Test
    @DisplayName("P-9.4: the Super Admin's dashboard is empty, like every other count")
    void P_9_4_superAdminGetsNoCounts() throws Exception {
        Department engineering = org.department("Engineering");
        org.user("devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.releasedRating(cycle, john, jane, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        mvc.perform(get(DASHBOARD).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("devin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeIsEmpty").value(true))
                .andExpect(jsonPath("$.participants").value(0));
    }

    @Test
    @DisplayName("An unknown cycle is a 404, reachable by anybody, disclosing nobody")
    void unknownCycleIsNotFound() throws Exception {
        Department engineering = org.department("Engineering");
        org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        org.flush();

        mvc.perform(get(DASHBOARD).param("cycleId", "999999")
                        .header("Authorization", bearer("jane")))
                .andExpect(status().isNotFound());
    }
}
