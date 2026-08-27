package com.altrium.plan;

// The OrgFixture field below is named `org`, which shadows the `org` package inside this
// class, so every com.altrium.org type has to arrive by import rather than fully qualified.
import com.altrium.org.AppUser;
import com.altrium.org.Department;
import com.altrium.org.HrGrantService;
import com.altrium.org.Role;
import com.altrium.testsupport.OrgFixture;
import com.altrium.testsupport.PlanFixture;
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

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature 16 - improvement plans.
 *
 * <p>The heaviest feature and the one with the most seams. Three actors hold strictly separate
 * powers: the manager opens the plan and judges it, HR co-sign and witness it and can do
 * neither of the manager's jobs, and the employee sees it only once HR have signed. Most of
 * what follows is those seams being tested from the wrong side.
 *
 * <p>Almost everything here goes through the API rather than a fixture, because a fixture that
 * assembled the co-sign state by hand would prove nothing about the endpoints meant to guard it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class ImprovementPlanTest {

    private static final String PIPS = "/api/plans/improvement";
    private static final String PDPS = "/api/plans/development";

    private static final String CLAUSE =
            "Sustained improvement required by the deadline, or the role is at risk.";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    @Autowired
    private PlanFixture planFixture;

    @Autowired
    private HrGrantService grants;

    /**
     * Submits a drafted development goal and has the employee agree to it (P-5.9).
     *
     * <p>A goal that is only drafted is invisible to the employee, so a test that reads their
     * plan back would find nothing - which is correct behaviour, and not what these tests are
     * about.
     */
    private void agree(String goalJson, String manager, String employee) throws Exception {
        long goalId = ((Number) JsonPath.read(goalJson, "$.id")).longValue();
        mvc.perform(post(PDPS + "/goals/" + goalId + "/submission")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk());
        mvc.perform(post(PDPS + "/goals/" + goalId + "/agreement")
                        .header("Authorization", bearer(employee)))
                .andExpect(status().isOk());
    }

    private String bearer(String handle) {
        return "Bearer " + OrgFixture.tokenFor(handle);
    }

    private static LocalDate future() {
        return LocalDate.now().plusMonths(3);
    }

    /** Opens a plan through the API and returns its id, the way a client would. */
    private long openPlan(String manager, AppUser subject, String clause) throws Exception {
        String json = mvc.perform(post(PIPS + "/" + subject.getId())
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consequenceClause\":" + quote(clause)
                                + ",\"deadline\":\"" + future() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "\\\"") + "\"";
    }

    // ================================================================ opening and exclusivity

    @Test
    @DisplayName("P-5.7: opening an improvement plan suspends the development plan")
    void P_5_7_openingSuspendsTheDevelopmentPlan() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        String elenaGoal = mvc.perform(post(PDPS + "/" + john.getId() + "/goals")
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Lead a migration end to end","targetDate":"2027-06-30"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        agree(elenaGoal, "elena", "john");

        openPlan("elena", john, CLAUSE);

        // Both halves in one transaction. An employee on an improvement plan whose development
        // plan is still running is exactly the state P-5.7 forbids, and a two-step version
        // would pass through it every single time.
        mvc.perform(get(PDPS + "/me").header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.active").value(false))
                // The goals are untouched. Suspension is not deletion.
                .andExpect(jsonPath("$.goals.length()").value(1));
    }

    @Test
    @DisplayName("P-5.7: a suspended development plan accepts no new goals")
    void P_5_7_suspendedPlanAcceptsNoGoals() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        openPlan("elena", john, CLAUSE);

        // 409: he holds WRITE_DEVELOPMENT_PLAN and it is the suspended plan that refuses.
        // Accepting goals here would defeat the point of suspending it.
        mvc.perform(post(PDPS + "/" + john.getId() + "/goals")
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Something unrelated"}"""))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("P-5.7: a second improvement plan cannot be opened for the same person")
    void P_5_7_onlyOneImprovementPlanAtATime() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        openPlan("elena", john, CLAUSE);

        mvc.perform(post(PIPS + "/" + john.getId())
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consequenceClause\":" + quote(CLAUSE)
                                + ",\"deadline\":\"" + future() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("P-1.2: only the employee's own manager opens a plan for them")
    void P_1_2_onlyTheDirectManagerOpensAPlan() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser tom = org.userIn(engineering, "tom", Role.EMPLOYEE, Role.MANAGER);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        String body = "{\"consequenceClause\":" + quote(CLAUSE)
                + ",\"deadline\":\"" + future() + "\"}";

        // Another manager in the same department.
        mvc.perform(post(PIPS + "/" + john.getId()).header("Authorization", bearer("tom"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        // And HR, who co-sign these and still do not start them. Opening a PIP is a management
        // judgment; HR's role is to check it, which they could not do if they had written it.
        mvc.perform(post(PIPS + "/" + john.getId()).header("Authorization", bearer("hana"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    // ================================================================ the co-sign gate

    @Test
    @DisplayName("P-5.3: the plan is invisible to the employee until HR co-sign it")
    void P_5_3_invisibleUntilCosigned() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        long planId = openPlan("elena", john, CLAUSE);

        // Identical to the response somebody with no plan at all receives. Telling the two
        // apart would disclose that a plan had been drafted about him, which is what the gate
        // withholds.
        mvc.perform(get(PIPS + "/me").header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPlan").value(false))
                .andExpect(jsonPath("$.plan").doesNotExist());

        // And the direct route is refused, not merely unrendered. A hidden screen is not a
        // gate; this is a state gate inside AuthorizationService.
        mvc.perform(get(PIPS + "/" + john.getId() + "/active")
                        .header("Authorization", bearer("john")))
                .andExpect(status().isForbidden());

        // The manager and HR see it before co-signing, because somebody has to draft it and
        // somebody has to review it before signing. The gate constrains the subject only.
        mvc.perform(get(PIPS + "/" + john.getId() + "/active")
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cosigned").value(false));

        mvc.perform(get(PIPS + "/" + john.getId() + "/active")
                        .header("Authorization", bearer("hana")))
                .andExpect(status().isOk());

        mvc.perform(post(PIPS + "/" + planId + "/cosign").header("Authorization", bearer("hana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cosignedBy").value("hana"));

        // Now it is his, and it carries the clause he is being held to.
        mvc.perform(get(PIPS + "/me").header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPlan").value(true))
                .andExpect(jsonPath("$.plan.consequenceClause").value(CLAUSE))
                .andExpect(jsonPath("$.plan.deadline").isNotEmpty());
    }

    @Test
    @DisplayName("P-5.4: a manager can neither co-sign nor record the witness")
    void P_5_4_theManagerDoesNeitherFormality() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        long planId = openPlan("elena", john, CLAUSE);

        // She opened the plan, she writes its goals, she judges the outcome. She cannot
        // certify her own plan - that separation is the entire point of the two formality
        // objects, and a manager who could do both would be running an unreviewed process.
        mvc.perform(post(PIPS + "/" + planId + "/cosign").header("Authorization", bearer("elena")))
                .andExpect(status().isForbidden());

        mvc.perform(post(PIPS + "/" + planId + "/witness")
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"witnessName":"a colleague who was in the room"}"""))
                .andExpect(status().isForbidden());

        // The subject cannot sign off their own plan either, which would be stranger still.
        mvc.perform(post(PIPS + "/" + planId + "/cosign").header("Authorization", bearer("john")))
                .andExpect(status().isForbidden());

        mvc.perform(post(PIPS + "/" + planId + "/witness")
                        .header("Authorization", bearer("hana"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"witnessName":"Priya Raman, People Operations"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.witnessName").value("Priya Raman, People Operations"));
    }

    @Test
    @DisplayName("P-5.6: a plan with no consequence clause cannot be co-signed")
    void P_5_6_consequenceClauseIsRequiredBeforeCosigning() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        long planId = openPlan("elena", john, null);

        // A plan whose consequences are unstated is a warning nobody agreed to, and co-signing
        // one would certify exactly that.
        mvc.perform(post(PIPS + "/" + planId + "/cosign").header("Authorization", bearer("hana")))
                .andExpect(status().isBadRequest());

        mvc.perform(put(PIPS + "/" + planId + "/consequence-clause")
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consequenceClause\":" + quote(CLAUSE) + "}"))
                .andExpect(status().isOk());

        mvc.perform(post(PIPS + "/" + planId + "/cosign").header("Authorization", bearer("hana")))
                .andExpect(status().isOk());

        // And once signed the clause is fixed: it is what he accepted, so changing it
        // afterwards would make the signature meaningless.
        mvc.perform(put(PIPS + "/" + planId + "/consequence-clause")
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consequenceClause":"actually rather more serious than that"}"""))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("P-5.3: the employee writes nothing on their own improvement plan")
    void P_5_3_theEmployeeWritesNothing() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        long planId = openPlan("elena", john, CLAUSE);

        // The difference between the two instruments. A development plan is written with the
        // employee and they hold WRITE_DEVELOPMENT_PLAN; an improvement plan is put to them,
        // and WRITE_IMPROVEMENT_PLAN has no SELF grounds at all. An employee who could edit
        // their own consequence clause would be able to soften it.
        mvc.perform(put(PIPS + "/" + planId + "/consequence-clause")
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consequenceClause":"nothing much will happen"}"""))
                .andExpect(status().isForbidden());

        mvc.perform(post(PIPS + "/" + planId + "/goals")
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"an easier goal"}"""))
                .andExpect(status().isForbidden());
    }

    // ================================================================ the immutable deadline

    @Test
    @DisplayName("P-5.5: nobody extends a PIP deadline - not the manager, not HR, not the Super Admin")
    void P_5_5_theDeadlineMovesForNobody() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        org.userIn(engineering, "devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        long planId = openPlan("elena", john, CLAUSE);
        String body = "{\"deadline\":\"" + future().plusMonths(6) + "\"}";

        // EXTEND_PIP_DEADLINE names no actor, so this endpoint exists in order to be refused.
        // Leaving it unbuilt would have been a rule nobody enforced, and the first person who
        // needed one would have added it without the rule.
        for (String actor : new String[]{"elena", "hana", "devin", "john"}) {
            mvc.perform(put(PIPS + "/" + planId + "/deadline")
                            .header("Authorization", bearer(actor))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("P-5.5: a goal date on an improvement plan is a PIP deadline and does not move")
    void P_5_5_improvementGoalDatesDoNotMoveEither() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        long planId = openPlan("elena", john, CLAUSE);
        String goalJson = mvc.perform(post(PIPS + "/" + planId + "/goals")
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Close the review backlog\",\"targetDate\":\""
                                + future().minusMonths(1) + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long goalId = ((Number) JsonPath.read(goalJson, "$.id")).longValue();

        // The same endpoint that moves a development goal's date freely refuses here, because
        // the goal belongs to an improvement plan. One answer to "may this date move?", routed
        // through the capability that names nobody - so the refusal cites the same rule as the
        // plan-level deadline rather than inventing a second one.
        mvc.perform(put(PDPS + "/goals/" + goalId + "/target-date")
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetDate\":\"" + future().plusMonths(6) + "\"}"))
                .andExpect(status().isForbidden());
    }

    // ================================================================ closing, and carry-over

    @Test
    @DisplayName("Section 5 step 9: passing resumes the same development plan, goals intact")
    void section_5_9_passingResumesTheSamePlanWithProgressIntact() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        // A development goal, half done, before any of this starts.
        String pdpGoal = mvc.perform(post(PDPS + "/" + john.getId() + "/goals")
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Lead a migration end to end",
                                 "detail":"design signed off","targetDate":"2027-06-30"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long pdpGoalId = ((Number) JsonPath.read(pdpGoal, "$.id")).longValue();
        agree(pdpGoal, "elena", "john");

        long planId = openPlan("elena", john, CLAUSE);
        mvc.perform(post(PIPS + "/" + planId + "/cosign").header("Authorization", bearer("hana")))
                .andExpect(status().isOk());

        String pipGoal = mvc.perform(post(PIPS + "/" + planId + "/goals")
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Clear the review backlog"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long pipGoalId = ((Number) JsonPath.read(pipGoal, "$.id")).longValue();

        // Passing with goals outstanding would make the goals decorative.
        mvc.perform(post(PIPS + "/" + planId + "/pass").header("Authorization", bearer("elena")))
                .andExpect(status().isConflict());

        // Approval is APPROVE_GOAL on both plan types, so P-5.2 needed no second implementation.
        mvc.perform(post(PDPS + "/goals/" + pipGoalId + "/approval")
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk());

        mvc.perform(post(PIPS + "/" + planId + "/pass").header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"));

        // THE CARRY-OVER PILLAR. The same development plan row comes back, with the goal and
        // the progress note written before any of this started. Nothing was copied and nothing
        // recreated - which only works because the plan was never keyed to a cycle.
        mvc.perform(get(PDPS + "/me").header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.suspendedAt").doesNotExist())
                .andExpect(jsonPath("$.goals.length()").value(1))
                .andExpect(jsonPath("$.goals[0].id").value(pdpGoalId))
                .andExpect(jsonPath("$.goals[0].detail").value("design signed off"));

        // And a new plan may now be opened, because the old one is closed.
        mvc.perform(post(PIPS + "/" + john.getId())
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consequenceClause\":" + quote(CLAUSE)
                                + ",\"deadline\":\"" + future() + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("P-5.3: a plan nobody co-signed cannot be passed or failed")
    void P_5_3_anUnseenPlanCannotBeClosed() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        long planId = openPlan("elena", john, CLAUSE);

        // He has never seen it, because HR never signed it. Recording an outcome for something
        // somebody was never told about would be the worst version of this feature.
        mvc.perform(post(PIPS + "/" + planId + "/pass").header("Authorization", bearer("elena")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("P-5.5: a plan cannot be failed before its deadline has passed")
    void P_5_5_failWaitsForTheDeadline() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        long planId = openPlan("elena", john, CLAUSE);
        mvc.perform(post(PIPS + "/" + planId + "/cosign").header("Authorization", bearer("hana")))
                .andExpect(status().isOk());

        // "Deadlines missed, plan failed" read literally: a plan cannot be failed while there
        // is still time to meet it. That is the protection the fixed deadline exists to give,
        // and it would be worth very little if the deadline could simply be ignored.
        mvc.perform(post(PIPS + "/" + planId + "/fail").header("Authorization", bearer("elena")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("P-5.7: failing also resumes the development plan")
    void P_5_7_failingAlsoResumesTheDevelopmentPlan() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        // The only thing here a fixture builds rather than the API: a deadline already in the
        // past. Opening one is refused, since a deadline nobody can extend and that has already
        // gone would make the plan unmeetable on the day it was written.
        planFixture.suspendedPlan(john);
        ImprovementPlan overdue = planFixture.overdueCosignedPlan(
                john, elena, hana, LocalDate.now().minusDays(1));
        planFixture.flush();

        mvc.perform(post(PIPS + "/" + overdue.getId() + "/fail")
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        // A judgment call rather than a rule from the documents: section 5 step 9 only says a
        // PASSED plan resumes the development plan. Leaving it suspended would leave him with
        // no active plan at all, contradicting section 8's universal development plan. A failed
        // PIP records an outcome; it does not end somebody's development.
        mvc.perform(get(PDPS + "/me").header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ================================================================ who else sees it

    @Test
    @DisplayName("P-2.1: HR read a plan in scope; P-9.4: the Super Admin reads none")
    void P_2_1_hrReadsInScopeAndTheSuperAdminReadsNothing() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        Department support = org.department("Support");
        org.userIn(engineering, "devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser rosa = org.userIn(peopleOps, "rosa", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());
        grants.grant(rosa.getId(), support.getId(), false, null, g -> g.getId());

        long planId = openPlan("elena", john, CLAUSE);

        mvc.perform(get(PIPS + "/" + john.getId() + "/active")
                        .header("Authorization", bearer("hana")))
                .andExpect(status().isOk());

        // Rosa is HR with a real grant, over the wrong department.
        mvc.perform(get(PIPS + "/" + john.getId() + "/active")
                        .header("Authorization", bearer("rosa")))
                .andExpect(status().isForbidden());

        mvc.perform(get(PIPS + "/" + john.getId() + "/active")
                        .header("Authorization", bearer("devin")))
                .andExpect(status().isForbidden());

        mvc.perform(post(PIPS + "/" + planId + "/cosign").header("Authorization", bearer("devin")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-1.5: Leadership are never put on an improvement plan")
    void P_1_5_leadershipHoldNoImprovementPlan() throws Exception {
        AppUser board = org.user("board", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        richard.setManager(board);
        org.flush();

        // Refused at creation, not hidden on read. Their manager is asking, which would be
        // grounds for anybody else; it is who Richard is that refuses.
        mvc.perform(post(PIPS + "/" + richard.getId())
                        .header("Authorization", bearer("board"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consequenceClause\":" + quote(CLAUSE)
                                + ",\"deadline\":\"" + future() + "\"}"))
                .andExpect(status().isForbidden());
    }
}
