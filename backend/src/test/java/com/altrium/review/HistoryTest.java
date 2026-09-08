package com.altrium.review;

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
 * Feature 16 - history and carry-over, over HTTP.
 *
 * <p>Called with a minted JWT rather than through a screen, like every other denial test here:
 * a test that drives the UI proves a tab is hidden, not that the history is refused.
 *
 * <p>The carry-over half of the feature has no test of its own in this class, deliberately.
 * It is not code that runs at a cycle boundary - the development plan is simply not keyed to a
 * cycle (P-5.9) and the improvement plan resumes it on close (P-5.14), both already proven in
 * {@code ImprovementPlanTest} and {@code DevelopmentPlanTest}. What was missing was the read,
 * and that is what this covers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class HistoryTest {

    private static final String HISTORY = "/api/history";

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

    // ------------------------------------------------------------------ the subject's own

    @Test
    @DisplayName("P-4.4: a withheld rating reads exactly like one that was never set")
    void P_4_4_withheldAndNeverSetAreIndistinguishable() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle last = reviews.openCycle();
        reviews.participant(last, john);
        reviews.releasedRating(last, john, jane, Rating.MEETS_EXPECTATIONS);
        reviews.managerReview(last, john, jane, "A solid year.");

        ReviewCycle current = reviews.openCycle();
        reviews.participant(current, john);
        reviews.unreleasedRating(current, john, jane, Rating.EXCEEDS_EXPECTATIONS);
        reviews.managerReview(current, john, jane, "Not shared yet.");
        reviews.flush();

        // Both cycles appear, because John knows perfectly well he was reviewed in each. What
        // differs is what they carry. The current cycle has a rating decided and a review
        // written, and shows neither - it is byte-for-byte the response a cycle where nobody
        // had done anything yet would produce. That indistinguishability is the requirement;
        // hiding the whole cycle would not add to it, and would make his own timeline lie
        // about a cycle he took part in.
        mvc.perform(get(HISTORY + "/me").header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].cycleId").value(current.getId()))
                .andExpect(jsonPath("$[0].rating").doesNotExist())
                .andExpect(jsonPath("$[0].releasedAt").doesNotExist())
                .andExpect(jsonPath("$[0].managerFeedback").doesNotExist())
                .andExpect(jsonPath("$[1].cycleId").value(last.getId()))
                .andExpect(jsonPath("$[1].rating").value("MEETS_EXPECTATIONS"))
                .andExpect(jsonPath("$[1].managerFeedback").value("A solid year."))
                .andExpect(jsonPath("$[1].releasedAt").exists());
    }

    @Test
    @DisplayName("P-3.7: the manager's words are withheld with the rating, not separately")
    void P_3_7_feedbackTravelsWithTheRating() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        // A submitted manager review with no rating set at all. There is nothing to release,
        // so the words stay with the manager: the cycle appears in his timeline, because he
        // knows he was reviewed, and carries neither the rating nor the feedback.
        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.managerReview(cycle, john, jane, "Written but the rating is not set.");
        reviews.flush();

        mvc.perform(get(HISTORY + "/me").header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rating").doesNotExist())
                .andExpect(jsonPath("$[0].managerFeedback").doesNotExist());
    }

    @Test
    @DisplayName("Section 5.9: cycles come back newest first")
    void section_5_9_newestCycleFirst() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        // The fixture's financial year increases per call, so `older` really is older.
        ReviewCycle older = reviews.openCycle();
        reviews.participant(older, john);
        reviews.releasedRating(older, john, jane, Rating.NEEDS_IMPROVEMENT);

        ReviewCycle newer = reviews.openCycle();
        reviews.participant(newer, john);
        reviews.releasedRating(newer, john, jane, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        mvc.perform(get(HISTORY + "/me").header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].cycleId").value(newer.getId()))
                .andExpect(jsonPath("$[0].rating").value("MEETS_EXPECTATIONS"))
                .andExpect(jsonPath("$[1].cycleId").value(older.getId()))
                .andExpect(jsonPath("$[1].rating").value("NEEDS_IMPROVEMENT"));
    }

    @Test
    @DisplayName("P-0.5: somebody never reviewed gets an empty list, not a 404")
    void P_0_5_neverReviewedIsAnEmptyHistory() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser mei = org.userIn(engineering, "mei", Role.EMPLOYEE);
        org.flush();

        mvc.perform(get(HISTORY + "/me").header("Authorization", bearer("mei")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ------------------------------------------------------------------ other people's

    @Test
    @DisplayName("P-1.2: the manager sees every cycle, including one still with HR")
    void P_1_2_theManagerSeesUnreleasedCyclesToo() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle last = reviews.openCycle();
        reviews.participant(last, john);
        reviews.releasedRating(last, john, jane, Rating.MEETS_EXPECTATIONS);

        ReviewCycle current = reviews.openCycle();
        reviews.participant(current, john);
        reviews.unreleasedRating(current, john, jane, Rating.EXCEEDS_EXPECTATIONS);
        reviews.managerReview(current, john, jane, "Not shared yet.");
        reviews.flush();

        // Two entries where John saw one, and the unreleased rating is visible: somebody has
        // to be able to see what they set before it is shared.
        mvc.perform(get(HISTORY + "/" + john.getId()).header("Authorization", bearer("jane")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].rating").value("EXCEEDS_EXPECTATIONS"))
                .andExpect(jsonPath("$[0].releasedAt").doesNotExist())
                .andExpect(jsonPath("$[0].managerFeedback").value("Not shared yet."));
    }

    @Test
    @DisplayName("P-1.1: a manager cannot read another manager's report's history")
    void P_1_1_nonReportHistoryIsRefused() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser tom = org.userIn(engineering, "tom", Role.EMPLOYEE, Role.MANAGER);
        AppUser omar = org.userIn(engineering, "omar", Role.EMPLOYEE);
        omar.setManager(tom);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, omar);
        reviews.releasedRating(cycle, omar, tom, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        mvc.perform(get(HISTORY + "/" + omar.getId()).header("Authorization", bearer("jane")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-2.1: HR read history inside their grants and nowhere else")
    void P_2_1_hrHistoryIsScopedToGrants() throws Exception {
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
        reviews.releasedRating(cycle, nadia, grace, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        mvc.perform(get(HISTORY + "/" + john.getId()).header("Authorization", bearer("hana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get(HISTORY + "/" + nadia.getId()).header("Authorization", bearer("hana")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-2.2: a granted HR Head reads their own history as an employee, not as HR")
    void P_2_2_hrHeadOwnHistoryIsTheEmployeeView() throws Exception {
        Department peopleOps = org.department("People Operations");
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser kevin = org.userIn(peopleOps, "kevin", Role.EMPLOYEE, Role.HR, Role.MANAGER);
        kevin.setManager(richard);
        org.flush();

        // The explicit grant lifts the own-department block and never the own-record one, so
        // Kevin arrives at his own timeline on SELF grounds and sees exactly what any employee
        // sees. His own department being in his grants buys him nothing here: the rating his
        // own HR function is still holding is withheld from him like anybody else's.
        grants.grant(kevin.getId(), peopleOps.getId(), true, null, g -> g.getId());

        ReviewCycle last = reviews.openCycle();
        reviews.participant(last, kevin);
        reviews.releasedRating(last, kevin, richard, Rating.MEETS_EXPECTATIONS);

        ReviewCycle current = reviews.openCycle();
        reviews.participant(current, kevin);
        reviews.unreleasedRating(current, kevin, richard, Rating.EXCEEDS_EXPECTATIONS);
        reviews.flush();

        mvc.perform(get(HISTORY + "/me").header("Authorization", bearer("kevin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].cycleId").value(current.getId()))
                .andExpect(jsonPath("$[0].rating").doesNotExist())
                .andExpect(jsonPath("$[1].cycleId").value(last.getId()))
                .andExpect(jsonPath("$[1].rating").value("MEETS_EXPECTATIONS"));

        // The id route is not refused - SELF is a real ground on the roster capability, and an
        // employee reading their own record by id is ordinary. What P-2.2 does is withhold his
        // HR grounds, so the route answers him as the employee he is rather than as the HR user
        // he also is: same two cycles, same withheld rating. Were the block missing, his
        // explicit grant over his own department would make this the HR view and hand him the
        // rating his own function is still holding.
        mvc.perform(get(HISTORY + "/" + kevin.getId()).header("Authorization", bearer("kevin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].cycleId").value(current.getId()))
                .andExpect(jsonPath("$[0].rating").doesNotExist());
    }

    @Test
    @DisplayName("P-9.4: the Super Admin reads nobody's history")
    void P_9_4_superAdminReadsNoHistory() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser devin = org.user("devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.releasedRating(cycle, john, jane, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        mvc.perform(get(HISTORY + "/" + john.getId()).header("Authorization", bearer("devin")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-7.1: Leadership hold aggregate grounds and cannot open a timeline")
    void P_7_1_leadershipCannotDrillIntoAHistory() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser marcus = org.user("marcus", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.releasedRating(cycle, john, jane, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        mvc.perform(get(HISTORY + "/" + john.getId()).header("Authorization", bearer("marcus")))
                .andExpect(status().isForbidden());
    }
}
