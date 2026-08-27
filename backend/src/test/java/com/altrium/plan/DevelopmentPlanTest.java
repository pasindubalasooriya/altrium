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

    /** Drafts a goal and returns its id, so later calls can address it the way a client would. */
    private long draftGoal(String manager, AppUser owner, String body) throws Exception {
        String json = mvc.perform(post(PLANS + "/" + owner.getId() + "/goals")
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    /**
     * A goal in the state most of these tests mean by "a goal on somebody's plan": drafted by
     * the manager, submitted, and agreed by the employee (P-5.9).
     *
     * <p>Tests that care about the intermediate states drive the three steps themselves.
     */
    private long agreedGoal(String manager, String employee, AppUser owner, String body)
            throws Exception {
        long goalId = draftGoal(manager, owner, body);
        mvc.perform(post(PLANS + "/goals/" + goalId + "/submission")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk());
        mvc.perform(post(PLANS + "/goals/" + goalId + "/agreement")
                        .header("Authorization", bearer(employee)))
                .andExpect(status().isOk());
        return goalId;
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
    @DisplayName("P-5.9: the manager writes the goals; the employee agrees to them")
    void P_5_9_managerWritesAndEmployeeAgrees() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        agreedGoal("elena", "john", john, """
                {"title":"Lead a migration end to end","targetDate":"2026-12-31"}""");
        agreedGoal("elena", "john", john, """
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

        long goalId = agreedGoal("elena", "john", john, """
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

        long goalId = agreedGoal("elena", "john", john, """
                {"title":"Lead a migration end to end","targetDate":"2026-09-30"}""");

        // He can say how it is going, through the progress endpoint - which writes its own
        // field and leaves the goal he agreed to exactly as it was (P-5.9). He cannot quietly
        // give himself another quarter: an employee who could reschedule their own deadlines
        // would make the date decorative (P-5.5 names mgr(S) for the dates specifically).
        mvc.perform(put(PLANS + "/goals/" + goalId + "/progress")
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"design done, build started"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressNote").value("design done, build started"))
                .andExpect(jsonPath("$.title").value("Lead a migration end to end"))
                .andExpect(jsonPath("$.targetDate").value("2026-09-30"));

        // And rewording the goal itself is refused outright: it is not his to reword, agreed
        // or otherwise (P-5.9).
        mvc.perform(put(PLANS + "/goals/" + goalId)
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"something easier"}"""))
                .andExpect(status().isForbidden());

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

        long goalId = agreedGoal("elena", "john", john, """
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

        long goalId = agreedGoal("elena", "john", john, """
                {"title":"Lead a migration end to end","targetDate":"2026-09-30"}""");
        mvc.perform(post(PLANS + "/goals/" + goalId + "/approval")
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk());

        // 409 for the manager: she holds WRITE_DEVELOPMENT_PLAN and it is the record that
        // refuses. Deleting signed-off progress would quietly rewrite the history the
        // carry-over pillar rests on.
        mvc.perform(delete(PLANS + "/goals/" + goalId).header("Authorization", bearer("elena")))
                .andExpect(status().isConflict());

        // Rewording is refused too, and by the agreement rather than the approval - the goal
        // stopped being hers to reword the moment John agreed to it (P-5.9). Both are 409:
        // she holds the capability throughout and it is the record's state that says no.
        mvc.perform(put(PLANS + "/goals/" + goalId)
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"actually it was rather bigger than that"}"""))
                .andExpect(status().isConflict());

        // 403 for John, on a different footing entirely: it is not that the record refuses,
        // it is that rewording a goal was never his to do.
        mvc.perform(put(PLANS + "/goals/" + goalId)
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"actually it was rather bigger than that"}"""))
                .andExpect(status().isForbidden());

        // Reopening is the same authority that approved it, so the two cannot drift apart.
        mvc.perform(delete(PLANS + "/goals/" + goalId + "/approval")
                        .header("Authorization", bearer("john")))
                .andExpect(status().isForbidden());

        mvc.perform(delete(PLANS + "/goals/" + goalId + "/approval")
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.approvedBy").doesNotExist());

        // Removing it is the manager's too now (P-5.9). Reopened, so the approval no longer
        // stands in the way.
        mvc.perform(delete(PLANS + "/goals/" + goalId).header("Authorization", bearer("elena")))
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

        long goalId = agreedGoal("tom", "omar", omar, """
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

        agreedGoal("elena", "john", john, """
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

    // ================================================================ P-5.9: the agreement

    @Test
    @DisplayName("P-5.9: the employee cannot write a goal on their own plan")
    void P_5_9_theEmployeeDoesNotWriteTheirOwnGoals() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        // The reversal itself. Under section 8 as written this was allowed; the Product Owner
        // has given the goals to the manager, and the employee's part is agreement and
        // progress instead.
        mvc.perform(post(PLANS + "/" + john.getId() + "/goals")
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"a goal I set for myself\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-5.9: an HR user cannot write goals on their own plan either")
    void P_5_9_anHrUserDoesNotWriteTheirOwnGoals() throws Exception {
        Department people = org.department("People");
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser kevin = org.userIn(people, "kevin", Role.EMPLOYEE, Role.HR, Role.MANAGER);
        kevin.setManager(richard);
        org.flush();
        grants.grant(kevin.getId(), people.getId(), true, null, g -> g.getId());

        // Holding HR, an explicit grant over his own department, and a manager role of his own
        // changes nothing: on his own plan he is the employee, and the goals are his manager's.
        mvc.perform(post(PLANS + "/" + kevin.getId() + "/goals")
                        .header("Authorization", bearer("kevin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"a goal I set for myself\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-5.9: a drafted goal is invisible to the employee until it is submitted")
    void P_5_9_draftsAreInvisibleToTheEmployee() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        long goalId = draftGoal("elena", john, "{\"title\":\"Still thinking about this one\"}");

        // Not "pending", not counted, not there. A goal still being drafted is a manager's
        // unfinished thought about somebody, not something they have been asked to accept.
        mvc.perform(get(PLANS + "/me").header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goals.length()").value(0));

        // Elena sees it, because somebody has to be able to see what they are writing.
        mvc.perform(get(PLANS + "/" + john.getId()).header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goals.length()").value(1))
                .andExpect(jsonPath("$.goals[0].agreement").value("DRAFT"));

        mvc.perform(post(PLANS + "/goals/" + goalId + "/submission")
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agreement").value("PENDING"));

        mvc.perform(get(PLANS + "/me").header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goals.length()").value(1))
                .andExpect(jsonPath("$.goals[0].agreement").value("PENDING"));
    }

    @Test
    @DisplayName("P-5.9: only the employee agrees - not their manager, not HR")
    void P_5_9_nobodyAgreesOnTheEmployeesBehalf() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser hana = org.userIn(org.department("People"), "hana", Role.EMPLOYEE, Role.HR);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        long goalId = draftGoal("elena", john, "{\"title\":\"Lead a migration end to end\"}");
        mvc.perform(post(PLANS + "/goals/" + goalId + "/submission")
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk());

        // The manager who wrote it cannot agree to it on his behalf, which would make the
        // agreement a formality she performs on herself.
        mvc.perform(post(PLANS + "/goals/" + goalId + "/agreement")
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isForbidden());

        // Nor can HR, who read the plan and write nothing to it.
        mvc.perform(post(PLANS + "/goals/" + goalId + "/agreement")
                        .header("Authorization", bearer("hana")))
                .andExpect(status().isForbidden());

        mvc.perform(post(PLANS + "/goals/" + goalId + "/agreement")
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agreement").value("AGREED"))
                .andExpect(jsonPath("$.agreedAt").isNotEmpty());

        // Once is enough. A second agreement is a 409, not a denial: he holds the capability.
        mvc.perform(post(PLANS + "/goals/" + goalId + "/agreement")
                        .header("Authorization", bearer("john")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("P-5.9: the wording is fixed at agreement, and the date still moves")
    void P_5_9_agreementFixesTheWordingButNotTheDate() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        long goalId = draftGoal("elena", john,
                "{\"title\":\"Lead a migration\",\"targetDate\":\"2026-09-30\"}");

        // While it is still a draft it is hers to reword freely.
        mvc.perform(put(PLANS + "/goals/" + goalId)
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Lead the payments migration\"}"))
                .andExpect(status().isOk());

        mvc.perform(post(PLANS + "/goals/" + goalId + "/submission")
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk());
        mvc.perform(post(PLANS + "/goals/" + goalId + "/agreement")
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk());

        // And afterwards it is not. 409 rather than 403: she still holds the capability, and it
        // is the agreement that refuses. A goal whose wording could change after it was agreed
        // is not one that was agreed to.
        mvc.perform(put(PLANS + "/goals/" + goalId)
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Lead payments, and billing too\"}"))
                .andExpect(status().isConflict());

        // The date is the exception, and section 8 asks for it: dates move as priorities change.
        mvc.perform(put(PLANS + "/goals/" + goalId + "/target-date")
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetDate\":\"2026-12-31\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetDate").value("2026-12-31"))
                .andExpect(jsonPath("$.title").value("Lead the payments migration"));
    }

    @Test
    @DisplayName("P-5.9: progress needs agreement first, and never overwrites the goal")
    void P_5_9_progressWaitsForAgreement() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        long goalId = draftGoal("elena", john,
                "{\"title\":\"Lead a migration\",\"detail\":\"the manager's words\"}");
        mvc.perform(post(PLANS + "/goals/" + goalId + "/submission")
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk());

        // Pending, not agreed. 409: he holds the capability and it is the state that refuses.
        mvc.perform(put(PLANS + "/goals/" + goalId + "/progress")
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"getting on with it\"}"))
                .andExpect(status().isConflict());

        mvc.perform(post(PLANS + "/goals/" + goalId + "/agreement")
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk());

        mvc.perform(put(PLANS + "/goals/" + goalId + "/progress")
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"design done, build started\"}"))
                .andExpect(status().isOk())
                // The goal he agreed to is untouched. Sharing one field with the manager's
                // wording would let progress quietly restate the goal.
                .andExpect(jsonPath("$.progressNote").value("design done, build started"))
                .andExpect(jsonPath("$.detail").value("the manager's words"))
                .andExpect(jsonPath("$.title").value("Lead a migration"));

        // The manager may record it too - as often as not she writes it up after a
        // conversation with the employee.
        mvc.perform(put(PLANS + "/goals/" + goalId + "/progress")
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"agreed in our one to one: on track\"}"))
                .andExpect(status().isOk());
    }
}
