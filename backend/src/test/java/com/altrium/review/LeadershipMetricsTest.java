package com.altrium.review;

// The OrgFixture field below is named `org`, which shadows the `org` package inside this
// class, so every com.altrium.org type has to arrive by import rather than fully qualified.
import com.altrium.org.AppUser;
import com.altrium.org.Department;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P-7.1 - Leadership receive aggregate metrics, and nothing that names a person.
 *
 * <p>The denial tests here matter more than the happy path. {@code READ_AGGREGATE_METRICS} names
 * exactly one ground, so a manager who can read their whole team's ratings individually, an HR
 * user who can calibrate them, and a Super Admin who administers the platform are all refused
 * this endpoint. Being able to see more detail elsewhere is not a reason to be given the summary.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class LeadershipMetricsTest {

    private static final String METRICS = "/api/leadership/metrics";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    @Autowired
    private ReviewFixture reviews;

    /** A cycle with two engineers rated and one unrated, plus a manager and an HR user. */
    private record Scene(ReviewCycle cycle, Department department, AppUser leader, AppUser manager,
                         AppUser hr, AppUser superAdmin, AppUser rated) {
    }

    private Scene scene() {
        Department engineering = org.department("Engineering-metrics");

        AppUser leader = org.user("richard-metrics", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser manager = org.userIn(engineering, "elena-metrics", Role.EMPLOYEE, Role.MANAGER);
        AppUser hr = org.userIn(engineering, "hana-metrics", Role.EMPLOYEE, Role.HR);
        AppUser admin = org.userIn(engineering, "devin-metrics", Role.EMPLOYEE, Role.SUPER_ADMIN);

        AppUser jane = org.userIn(engineering, "jane-metrics", Role.EMPLOYEE);
        AppUser john = org.userIn(engineering, "john-metrics", Role.EMPLOYEE);
        AppUser mei = org.userIn(engineering, "mei-metrics", Role.EMPLOYEE);
        jane.setManager(manager);
        john.setManager(manager);
        mei.setManager(manager);

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, jane);
        reviews.participant(cycle, john);
        reviews.participant(cycle, mei);

        reviews.selfReview(cycle, jane, "Shipped the migration.");
        reviews.selfReview(cycle, john, "Onboarded two engineers.");
        reviews.managerReview(cycle, jane, manager, "Strong quarter.");

        reviews.releasedRating(cycle, jane, manager, Rating.EXCEEDS_EXPECTATIONS);
        reviews.unreleasedRating(cycle, john, manager, Rating.MEETS_EXPECTATIONS);
        // Mei is a participant with no rating, so ratingsSet must not equal participants.

        org.flush();
        return new Scene(cycle, engineering, leader, manager, hr, admin, jane);
    }

    @Test
    @DisplayName("P-7.1: Leadership receive the cycle totals")
    void P_7_1_leadershipReceiveTotals() throws Exception {
        Scene scene = scene();

        mvc.perform(get(METRICS)
                        .param("cycleId", scene.cycle().getId().toString())
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("richard-metrics")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycle.cycleId").value(scene.cycle().getId()))
                .andExpect(jsonPath("$.cycle.status").value("OPEN"))
                .andExpect(jsonPath("$.departments[0].departmentId").value(scene.department().getId()))
                .andExpect(jsonPath("$.departments[0].participants").value(3))
                .andExpect(jsonPath("$.departments[0].selfReviewsSubmitted").value(2))
                .andExpect(jsonPath("$.departments[0].managerReviewsSubmitted").value(1))
                .andExpect(jsonPath("$.departments[0].ratingsSet").value(2))
                .andExpect(jsonPath("$.departments[0].ratingsReleased").value(1));
    }

    @Test
    @DisplayName("P-7.1: the distribution carries every scale value, zeroes included")
    void P_7_1_distributionIsCompleteAndSumsToRatingsSet() throws Exception {
        Scene scene = scene();

        mvc.perform(get(METRICS)
                        .param("cycleId", scene.cycle().getId().toString())
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("richard-metrics")))
                .andExpect(status().isOk())
                // Two ratings were set, one of each; nobody needed improvement. The zero is
                // present rather than omitted, so the client is told rather than left to guess
                // whether a missing key means none or not permitted.
                .andExpect(jsonPath("$.ratingDistribution.EXCEEDS_EXPECTATIONS").value(1))
                .andExpect(jsonPath("$.ratingDistribution.MEETS_EXPECTATIONS").value(1))
                .andExpect(jsonPath("$.ratingDistribution.NEEDS_IMPROVEMENT").value(0));
    }

    @Test
    @DisplayName("P-7.1: no person is named anywhere in the response, so there is nothing to open")
    void P_7_1_responseNamesNobody() throws Exception {
        Scene scene = scene();

        // The strongest form of the rule: not "the client does not render a link", but that
        // there is nothing in the payload a link could be built from.
        mvc.perform(get(METRICS)
                        .param("cycleId", scene.cycle().getId().toString())
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("richard-metrics")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("jane-metrics"))))
                .andExpect(content().string(not(containsString("Jane"))))
                .andExpect(content().string(not(containsString("subjectId"))));
    }

    @Test
    @DisplayName("P-7.1: a manager is refused, though they read their own team's ratings")
    void P_7_1_managerCannotReadAggregateMetrics() throws Exception {
        Scene scene = scene();

        mvc.perform(get(METRICS)
                        .param("cycleId", scene.cycle().getId().toString())
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("elena-metrics")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-7.1: an HR user is refused - they monitor their grants, not the organisation")
    void P_7_1_hrCannotReadAggregateMetrics() throws Exception {
        Scene scene = scene();

        mvc.perform(get(METRICS)
                        .param("cycleId", scene.cycle().getId().toString())
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("hana-metrics")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-9.4: the Super Admin is refused, aggregate or not")
    void P_9_4_superAdminCannotReadAggregateMetrics() throws Exception {
        Scene scene = scene();

        mvc.perform(get(METRICS)
                        .param("cycleId", scene.cycle().getId().toString())
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("devin-metrics")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-7.1: an ordinary employee is refused")
    void P_7_1_employeeCannotReadAggregateMetrics() throws Exception {
        Scene scene = scene();

        mvc.perform(get(METRICS)
                        .param("cycleId", scene.cycle().getId().toString())
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("john-metrics")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a token claiming LEADERSHIP for someone the database says is an employee is refused")
    void tokenRoleClaimGrantsNothing() throws Exception {
        Scene scene = scene();

        // Authorities come from user_role, never from the token. The claim is decoration.
        mvc.perform(get(METRICS)
                        .param("cycleId", scene.cycle().getId().toString())
                        .header("Authorization", "Bearer "
                                + OrgFixture.tokenClaiming("john-metrics", Role.LEADERSHIP)))
                .andExpect(status().isForbidden());
    }
}
