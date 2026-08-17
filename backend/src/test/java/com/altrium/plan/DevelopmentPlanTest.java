package com.altrium.plan;

// The OrgFixture field below is named `org`, which shadows the `org` package inside this
// class, so every com.altrium.org type has to arrive by import rather than fully qualified.
import com.altrium.org.AppUser;
import com.altrium.org.Department;
import com.altrium.org.HrGrantService;
import com.altrium.org.Role;
import com.altrium.testsupport.OrgFixture;
import com.altrium.testsupport.StubJwtDecoderConfig;
import com.jayway.jsonpath.JsonPath;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature 15 - development plans.
 *
 * <p>The distinctive shape here is that <em>three different people</em> hold write access to
 * one goal and each may do a different thing to it. Most of these tests are about the seams
 * between those three: an employee editing their own text but not their own deadline, a manager
 * approving but HR never writing at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class DevelopmentPlanTest {

    private static final String PLANS = "/api/plans/development";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    @Autowired
    private HrGrantService grants;

    private String bearer(String handle) {
        return "Bearer " + OrgFixture.tokenFor(handle);
    }

    /** Creates a goal and returns its id, so later calls can address it the way a client would. */
    private long addGoal(String actor, AppUser owner, String body) throws Exception {
        String json = mvc.perform(post(PLANS + "/" + owner.getId() + "/goals")
                        .header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    // ================================================================ universal

    @Test
    @DisplayName("Section 8: every employee has a plan, materialised the first time anybody looks")
    void section_8_everyEmployeeHasAPlanOnFirstSight() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        // Nobody has written a goal and no row exists yet. He still has a plan, because the
        // scenario says he has one from the moment he joined - an empty plan, not a 404.
        mvc.perform(get(PLANS + "/me").header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.goals.length()").value(0));

        // And his manager sees the same thing rather than being told there is nothing there.
        mvc.perform(get(PLANS + "/" + john.getId()).header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value("john"));
    }

    @Test
    @DisplayName("P-7.2: Leadership hold no development plan, their own included")
    void P_7_2_leadershipHoldNoPlan() throws Exception {
        AppUser board = org.user("board", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        richard.setManager(board);
        org.flush();

        // Not hidden: never brought into existence. Every plan capability is an artifact
        // capability, and those are refused for a Leadership subject at step 2, so this path
        // never reaches the code that would have created the row.
        mvc.perform(get(PLANS + "/me").header("Authorization", bearer("richard")))
                .andExpect(status().isForbidden());

        mvc.perform(get(PLANS + "/" + richard.getId()).header("Authorization", bearer("board")))
                .andExpect(status().isForbidden());
    }

    // ================================================================ who writes

    @Test
    @DisplayName("P-5.1: the employee and their manager both write goals")
    void P_5_1_employeeAndManagerBothWrite() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        addGoal("john", john, """
                {"title":"Lead a migration end to end","targetDate":"2026-12-31"}""");
        addGoal("elena", john, """
                {"title":"Mentor a junior through onboarding","targetDate":"2026-10-01"}""");

        // Ordered by target date, so the response reads as a plan rather than as the order
        // somebody happened to type them in.
        mvc.perform(get(PLANS + "/me").header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goals.length()").value(2))
                .andExpect(jsonPath("$.goals[0].title")
                        .value("Mentor a junior through onboarding"));
    }

    @Test
    @DisplayName("P-5.1: HR read a plan in scope and can write nothing to it")
    void P_5_1_hrIsReadOnlyOnPlans() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        long goalId = addGoal("john", john, """
                {"title":"Lead a migration end to end","targetDate":"2026-12-31"}""");

        mvc.perform(get(PLANS + "/" + john.getId()).header("Authorization", bearer("hana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goals.length()").value(1));

        // Development is between the employee and their manager; HR oversee that it is
        // happening. READ_DEVELOPMENT_PLAN lists HR_IN_SCOPE and WRITE_DEVELOPMENT_PLAN does
        // not, so the same grant that opens the read closes the write.
        mvc.perform(post(PLANS + "/" + john.getId() + "/goals")
                        .header("Authorization", bearer("hana"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"HR would like to suggest something"}"""))
                .andExpect(status().isForbidden());

        mvc.perform(put(PLANS + "/goals/" + goalId)
                        .header("Authorization", bearer("hana"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"reworded by HR"}"""))
                .andExpect(status().isForbidden());

        mvc.perform(post(PLANS + "/goals/" + goalId + "/approval")
                        .header("Authorization", bearer("hana")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-1.2: a manager reads and writes only their own reports' plans")
    void P_1_2_managerIsConfinedToTheirReports() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser tom = org.userIn(engineering, "tom", Role.EMPLOYEE, Role.MANAGER);
        AppUser omar = org.userIn(engineering, "omar", Role.EMPLOYEE);
        omar.setManager(tom);
        org.flush();

        mvc.perform(get(PLANS + "/" + omar.getId()).header("Authorization", bearer("elena")))
                .andExpect(status().isForbidden());

        mvc.perform(post(PLANS + "/" + omar.getId() + "/goals")
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"a goal for somebody else's report"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-9.4: the Super Admin reads no plan")
    void P_9_4_superAdminReadsNoPlan() throws Exception {
        Department engineering = org.department("Engineering");
        org.userIn(engineering, "devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        mvc.perform(get(PLANS + "/" + john.getId()).header("Authorization", bearer("devin")))
                .andExpect(status().isForbidden());
    }

    // ================================================================ dates and approval

    @Test
    @DisplayName("P-5.5: the target date moves, and only the manager moves it")
    void P_5_5_onlyTheManagerMovesTheTargetDate() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        long goalId = addGoal("john", john, """
                {"title":"Lead a migration end to end","targetDate":"2026-09-30"}""");

        // He can say how it is going. He cannot quietly give himself another quarter - an
        // employee who could reschedule their own deadlines would make the date decorative
        // (P-5.5 names mgr(S) for the dates specifically).
        mvc.perform(put(PLANS + "/goals/" + goalId)
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Lead a migration end to end","detail":"design done, build started"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail").value("design done, build started"))
                .andExpect(jsonPath("$.targetDate").value("2026-09-30"));

        mvc.perform(put(PLANS + "/goals/" + goalId + "/target-date")
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetDate":"2027-06-30"}"""))
                .andExpect(status().isForbidden());

        // And it does move, which is the whole contrast with a PIP deadline that no actor may
        // extend. A development goal that slipped because the quarter went differently is one
        // to reschedule, not a failure to record.
        mvc.perform(put(PLANS + "/goals/" + goalId + "/target-date")
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetDate":"2027-03-31"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetDate").value("2027-03-31"));
    }

    @Test
    @DisplayName("P-5.2: only the manager approves, and the approval names them")
    void P_5_2_onlyTheManagerApprovesAndIsRecorded() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        long goalId = addGoal("john", john, """
                {"title":"Lead a migration end to end","targetDate":"2026-09-30"}""");

        // Self-approval is the thing a development plan is meant not to be.
        mvc.perform(post(PLANS + "/goals/" + goalId + "/approval")
                        .header("Authorization", bearer("john")))
                .andExpect(status().isForbidden());

        mvc.perform(post(PLANS + "/goals/" + goalId + "/approval")
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                // A completed goal with no approver would be one that completed itself, which
                // the database also refuses.
                .andExpect(jsonPath("$.approvedBy").value("elena"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
    }

    @Test
    @DisplayName("P-5.2: an approved goal is frozen until the manager reopens it")
    void P_5_2_approvedGoalIsFrozenUntilReopened() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        long goalId = addGoal("john", john, """
                {"title":"Lead a migration end to end","targetDate":"2026-09-30"}""");
        mvc.perform(post(PLANS + "/goals/" + goalId + "/approval")
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk());

        // 409 throughout: he holds WRITE_DEVELOPMENT_PLAN and it is the approved record that
        // refuses. Rewriting or deleting signed-off progress would quietly rewrite the history
        // the carry-over pillar rests on.
        mvc.perform(put(PLANS + "/goals/" + goalId)
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"actually it was rather bigger than that"}"""))
                .andExpect(status().isConflict());

        mvc.perform(delete(PLANS + "/goals/" + goalId).header("Authorization", bearer("john")))
                .andExpect(status().isConflict());

        // Reopening is the same authority that approved it, so the two cannot drift apart.
        mvc.perform(delete(PLANS + "/goals/" + goalId + "/approval")
                        .header("Authorization", bearer("john")))
                .andExpect(status().isForbidden());

        mvc.perform(delete(PLANS + "/goals/" + goalId + "/approval")
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.approvedBy").doesNotExist());

        mvc.perform(delete(PLANS + "/goals/" + goalId).header("Authorization", bearer("john")))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("P-0.4: a goal id lifted from somebody else's plan is refused")
    void P_0_4_goalIdFromAnotherPersonIsRefused() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser tom = org.userIn(engineering, "tom", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser omar = org.userIn(engineering, "omar", Role.EMPLOYEE);
        john.setManager(elena);
        omar.setManager(tom);
        org.flush();

        long goalId = addGoal("omar", omar, """
                {"title":"Something private to Tom's team"}""");

        // Goals are addressed by their own id, so this is the shape a real attempt would take.
        // The decision is made about whose plan the goal belongs to, not about the id.
        mvc.perform(put(PLANS + "/goals/" + goalId)
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"mine now"}"""))
                .andExpect(status().isForbidden());

        mvc.perform(post(PLANS + "/goals/" + goalId + "/approval")
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isForbidden());

        // An id that exists nowhere is refused identically, so the two cannot be told apart.
        mvc.perform(put(PLANS + "/goals/987654321")
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"fishing"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-5.1: the plan endures, so it is not keyed to a cycle")
    void P_5_1_thePlanIsNotPerCycle() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        addGoal("john", john, """
                {"title":"Lead a migration end to end","targetDate":"2026-09-30"}""");

        // No cycle id appears anywhere in this feature - not in a path, not in a query
        // parameter, not on the row. One plan per person, which is what makes "a passed
        // improvement plan resumes the suspended development plan with its goals and progress
        // intact" a resumption rather than a copy. A copy is where progress gets lost.
        mvc.perform(get(PLANS + "/me").header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goals.length()").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
