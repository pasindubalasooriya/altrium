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
    private ReviewFixture reviews;

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

    /**
     * Gives the subject a completed review, which an improvement plan now requires.
     *
     * <p>Scenario section 5 puts the PIP at step 7, after the rating is shared at step 6, so
     * every test that opens a plan needs an employee who has been through one. Seeded rather
     * than driven through the API because these tests are about the plan, not about how the
     * rating got there. {@code openCycle()} allocates a fresh period per call, so this is safe
     * to call from tests that already have a cycle of their own.
     */
    private void completeAReview(AppUser subject, AppUser manager) {
        reviews.releasedRating(reviews.openCycle(), subject, manager, Rating.NEEDS_IMPROVEMENT);
        reviews.flush();
    }

    /** Opens a plan through the API and returns its id, the way a client would. */
    private long openPlan(String manager, AppUser subject, String clause) throws Exception {
        // The precondition the feature now carries. Every test below wants a plan that opened,
        // not a test of the gate itself - that has its own test.
        completeAReview(subject, subject.getManager() == null ? subject : subject.getManager());

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
        //
        // Read through the manager, because the row is the truth and she is entitled to it.
        // What John sees before the co-signature is the next test.
        mvc.perform(get(PDPS + "/" + john.getId()).header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.active").value(false))
                // The goals are untouched. Suspension is not deletion.
                .andExpect(jsonPath("$.goals.length()").value(1));
    }

    /**
     * The co-sign gate, closed from the side it used to leak through.
     *
     * <p>Suspension has one cause, so telling the employee their plan is on hold tells them an
     * improvement plan exists - the fact P-5.3 withholds until HR sign it. The plan screen was
     * announcing it and then linking to a page that denied any plan existed, which is both a
     * disclosure and a contradiction the employee could see.
     */
    @Test
    @DisplayName("P-5.3: the employee is not told their plan is suspended until HR co-sign")
    void P_5_3_suspensionIsWithheldUntilCosignature() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana-susp", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena-susp", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-susp", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        long planId = openPlan("elena-susp", john, CLAUSE);

        // Before the co-signature: John's plan reads exactly as it did the day before it was
        // opened. Not "suspended", not a hint - the same answer as somebody with no plan at all.
        mvc.perform(get(PDPS + "/me").header("Authorization", bearer("john-susp")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.suspendedAt").doesNotExist());

        // And the improvement plan itself is withheld, which is the gate this is protecting.
        mvc.perform(get(PIPS + "/me").header("Authorization", bearer("john-susp")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPlan").value(false));

        mvc.perform(post(PIPS + "/" + planId + "/cosign")
                        .header("Authorization", bearer("hana-susp")))
                .andExpect(status().isOk());

        // After it, both halves arrive together. The suspension and the plan that explains it
        // become visible in the same moment, so the console can never say one without the other.
        mvc.perform(get(PDPS + "/me").header("Authorization", bearer("john-susp")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.active").value(false));

        mvc.perform(get(PIPS + "/me").header("Authorization", bearer("john-susp")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPlan").value(true));
    }

    /**
     * Scenario section 5 step 7: the improvement plan is where a completed review routes, not
     * something that runs beside one. Until the employee has been told an outcome there is
     * nothing for a plan to correct.
     */
    @Test
    @DisplayName("section 5: an improvement plan cannot open before the rating is shared")
    void section_5_improvementPlanFollowsACompletedReview() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena-nofr", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-nofr", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        // 409: Elena holds OPEN_IMPROVEMENT_PLAN on her own report throughout. It is the
        // absence of a completed review that refuses, not her standing to ask.
        mvc.perform(post(PIPS + "/" + john.getId())
                        .header("Authorization", bearer("elena-nofr"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consequenceClause\":" + quote(CLAUSE)
                                + ",\"deadline\":\"" + future() + "\"}"))
                .andExpect(status().isConflict());
    }

    /**
     * A rating that is set but not released is a decision the employee has not been given, so
     * it does not open the door either. This is the test that separates "the review happened"
     * from "the review finished".
     */
    @Test
    @DisplayName("section 5: an unreleased rating is not a completed review")
    void section_5_unreleasedRatingDoesNotOpenTheDoor() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena-unrel", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-unrel", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        reviews.unreleasedRating(
                reviews.openCycle(), john, elena, Rating.NEEDS_IMPROVEMENT);
        reviews.flush();

        mvc.perform(post(PIPS + "/" + john.getId())
                        .header("Authorization", bearer("elena-unrel"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consequenceClause\":" + quote(CLAUSE)
                                + ",\"deadline\":\"" + future() + "\"}"))
                .andExpect(status().isConflict());
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

    // ================================================================ progress on a PIP goal

    /**
     * Adds a goal to an improvement plan and returns its id.
     */
    private long addImprovementGoal(String manager, long planId, String title) throws Exception {
        String json = mvc.perform(post(PIPS + "/" + planId + "/goals")
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":" + quote(title) + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    /**
     * A PIP runs for months and is judged at the end. Without this the only writing on the plan
     * is the goal set on day one and the pass or fail at the close, and nothing in between
     * explains the outcome.
     */
    @Test
    @DisplayName("P-5.9: the manager records progress on an improvement goal")
    void P_5_9_managerRecordsProgressOnAnImprovementGoal() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena-pipprog", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-pipprog", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        long planId = openPlan("elena-pipprog", john, CLAUSE);
        long goalId = addImprovementGoal("elena-pipprog", planId, "Close tickets within the SLA");

        // The agreement gate is a development concept. An improvement goal has no agreement to
        // wait for, so this must not be refused for the want of one.
        mvc.perform(put(PDPS + "/goals/" + goalId + "/progress")
                        .header("Authorization", bearer("elena-pipprog"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"Two weeks in, the backlog is down but response time is not"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressNote").value(
                        "Two weeks in, the backlog is down but response time is not"));
    }

    /**
     * The line that makes a PIP a PIP.
     *
     * <p>{@code REPORT_GOAL_PROGRESS} carries {@code SELF} and would have let John write on his
     * own plan. Progress on an improvement goal therefore takes
     * {@code WRITE_IMPROVEMENT_PLAN} instead, which is {@code DIRECT_MANAGER} alone - an
     * improvement plan is put to somebody rather than agreed with them, and an employee who
     * could annotate their own would be arguing with the record rather than meeting it.
     */
    @Test
    @DisplayName("P-5.3: the employee cannot record progress on their own improvement goal")
    void P_5_3_employeeCannotWriteProgressOnTheirOwnImprovementGoal() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena-pipself", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-pipself", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        long planId = openPlan("elena-pipself", john, CLAUSE);
        long goalId = addImprovementGoal("elena-pipself", planId, "Close tickets within the SLA");

        mvc.perform(put(PDPS + "/goals/" + goalId + "/progress")
                        .header("Authorization", bearer("john-pipself"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"I think this is unfair"}"""))
                .andExpect(status().isForbidden());
    }

    /**
     * The employee's own development goals are untouched by the change above. Narrowing the PIP
     * branch must not narrow the PDP one, which is the whole collaboration P-5.9 preserves.
     */
    @Test
    @DisplayName("P-5.9: the employee still records progress on their own development goal")
    void P_5_9_employeeStillWritesProgressOnADevelopmentGoal() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena-pdpprog", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-pdpprog", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        String goal = mvc.perform(post(PDPS + "/" + john.getId() + "/goals")
                        .header("Authorization", bearer("elena-pdpprog"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Lead a design review"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        agree(goal, "elena-pdpprog", "john-pdpprog");
        long goalId = ((Number) JsonPath.read(goal, "$.id")).longValue();

        mvc.perform(put(PDPS + "/goals/" + goalId + "/progress")
                        .header("Authorization", bearer("john-pdpprog"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"Ran my first one this sprint"}"""))
                .andExpect(status().isOk());
    }

    /**
     * The manager marks an improvement goal complete, and closing the plan puts the employee
     * back on their development plan with everything intact (P-5.2, P-5.7).
     */
    @Test
    @DisplayName("P-5.2: the manager completes an improvement goal, and passing restores the PDP")
    void P_5_2_managerCompletesAnImprovementGoalThenPasses() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana-pipdone", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena-pipdone", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-pipdone", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        long planId = openPlan("elena-pipdone", john, CLAUSE);
        long goalId = addImprovementGoal("elena-pipdone", planId, "Close tickets within the SLA");

        // A plan the employee has never seen cannot be passed or failed, so HR co-sign first.
        mvc.perform(post(PIPS + "/" + planId + "/cosign")
                        .header("Authorization", bearer("hana-pipdone")))
                .andExpect(status().isOk());

        mvc.perform(post(PDPS + "/goals/" + goalId + "/approval")
                        .header("Authorization", bearer("elena-pipdone")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"));

        mvc.perform(post(PIPS + "/" + planId + "/pass")
                        .header("Authorization", bearer("elena-pipdone")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"));

        // Back on the development track without anybody doing anything else.
        mvc.perform(get(PDPS + "/me").header("Authorization", bearer("john-pipdone")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.active").value(true));
    }
}
