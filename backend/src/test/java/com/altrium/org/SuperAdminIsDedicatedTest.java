package com.altrium.org;

// The OrgFixture field below is named `org`, which shadows the `org` package inside this
// class, so every com.altrium.org type has to arrive by import rather than fully qualified.
import com.altrium.review.Cohort;
import com.altrium.review.ReviewCycle;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P-9.5 - the Super Admin is a dedicated platform account and is never a reviewee.
 *
 * <p>A Product Owner ruling, and a deviation from P-0.1, which says every role is additive and
 * never exclusive. The reasoning is separation of duties: this account grants the HR users their
 * departments and configures the cycles, so a reviewing role on top would let one account
 * arrange the scope and then act inside it.
 *
 * <p><b>Employee is not stripped, and the last test is why.</b> Employee is not a job in this
 * schema; it is the marker saying a caller is provisioned and active, and {@code SecurityConfig}
 * requires it on every authenticated endpoint. An account without it could not reach the
 * administration console either. What P-9.5 removes is being a reviewee, and that is enforced at
 * step 2 of the evaluation order rather than by taking the marker away - so the console keeps
 * working while every review capability about that account is refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class SuperAdminIsDedicatedTest {

    private static final String USERS = "/api/admin/users";
    private static final String REVIEWS = "/api/reviews";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    @Autowired
    private ReviewFixture reviews;

    private String bearer(String handle) {
        return "Bearer " + OrgFixture.tokenFor(handle);
    }

    // ============================================================ the role holds nothing else

    @Test
    @DisplayName("P-9.5: creating a Super Admin who is also a manager is refused with 400")
    void P_9_5_superAdminCannotAlsoManage() throws Exception {
        org.user("devin-excl", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        mvc.perform(post(USERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"asgardeoSubject":"sub-clash","email":"clash@altrium.test",
                                 "fullName":"Clash Person","departmentId":null,"managerId":null,
                                 "roles":["SUPER_ADMIN","MANAGER"]}""")
                        .header("Authorization", bearer("devin-excl")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("dedicated account")));
    }

    @Test
    @DisplayName("P-9.5: HR and Leadership are refused alongside it too, not only Manager")
    void P_9_5_theOtherReviewingRolesAreRefusedToo() throws Exception {
        org.user("devin-excl2", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser victim = org.user("victim-excl2", Role.EMPLOYEE);
        org.flush();

        for (String role : new String[] {"HR", "LEADERSHIP"}) {
            mvc.perform(put(USERS + "/" + victim.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"victim-excl2@altrium.test","fullName":"Victim",
                                     "departmentId":null,"roles":["SUPER_ADMIN","%s"]}"""
                                    .formatted(role))
                            .header("Authorization", bearer("devin-excl2")))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("a Super Admin on its own is accepted, and keeps the Employee marker")
    void superAdminAloneIsFine() throws Exception {
        org.user("devin-alone", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        mvc.perform(post(USERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"asgardeoSubject":"sub-alone","email":"alone@altrium.test",
                                 "fullName":"Alone Admin","departmentId":null,"managerId":null,
                                 "roles":["SUPER_ADMIN"]}""")
                        .header("Authorization", bearer("devin-alone")))
                .andExpect(status().isCreated())
                // Employee survives. Without it the account could not call this endpoint at all.
                .andExpect(jsonPath("$.roles").value(hasItem("EMPLOYEE")));
    }

    // ============================================================ never a reviewee

    @Test
    @DisplayName("P-9.5: a Super Admin cannot be put in a cohort")
    void P_9_5_notEnrolledInACohort() throws Exception {
        AppUser devin = org.user("devin-cohort", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();
        Cohort cohort = reviews.cohort("Spring-sa", 1);
        reviews.flush();

        mvc.perform(post("/api/admin/cohorts/" + cohort.getId() + "/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + devin.getId() + "}")
                        .header("Authorization", bearer("devin-cohort")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("never a reviewee")));
    }

    @Test
    @DisplayName("P-9.5: even placed in a cycle by other means, the record is refused to everyone")
    void P_9_5_theBlockIsStructuralNotJustConfiguration() throws Exception {
        Department engineering = org.department("Engineering-sa");
        AppUser elena = org.userIn(engineering, "elena-sa", Role.EMPLOYEE, Role.MANAGER);
        AppUser devin = org.userIn(engineering, "devin-struct", Role.EMPLOYEE, Role.SUPER_ADMIN);
        devin.setManager(elena);
        org.flush();

        // Enrolled directly through the fixture, bypassing the cohort screen's refusal. This is
        // the case the step-2 block exists for: a row that arrived some other way must not make
        // a Super Admin reviewable.
        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, devin);
        reviews.flush();

        // Their own manager is refused.
        mvc.perform(get(REVIEWS + "/" + devin.getId()).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena-sa")))
                .andExpect(status().isForbidden());

        // And so are they, on their own record. There is no artifact, not a hidden one.
        mvc.perform(get(REVIEWS + "/" + devin.getId()).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("devin-struct")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-9.5: a Super Admin cannot be assigned as somebody's peer")
    void P_9_5_notAPeerReviewer() throws Exception {
        Department engineering = org.department("Engineering-peer-sa");
        AppUser elena = org.userIn(engineering, "elena-peer-sa", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-peer-sa", Role.EMPLOYEE);
        AppUser colleague = org.userIn(engineering, "colleague-peer-sa", Role.EMPLOYEE);
        AppUser devin = org.userIn(engineering, "devin-peer-sa", Role.EMPLOYEE, Role.SUPER_ADMIN);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        mvc.perform(put(REVIEWS + "/" + john.getId() + "/peers")
                        .param("cycleId", cycle.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerIds\":[" + colleague.getId() + "," + devin.getId() + "]}")
                        .header("Authorization", bearer("elena-peer-sa")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Super Admin")));
    }

    @Test
    @DisplayName("P-9.5: and does not appear among the peer candidates offered")
    void P_9_5_notOfferedAsACandidate() throws Exception {
        Department engineering = org.department("Engineering-cand-sa");
        AppUser elena = org.userIn(engineering, "elena-cand-sa", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-cand-sa", Role.EMPLOYEE);
        AppUser devin = org.userIn(engineering, "devin-cand-sa", Role.EMPLOYEE, Role.SUPER_ADMIN);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        mvc.perform(get(REVIEWS + "/" + john.getId() + "/peer-candidates")
                        .param("cycleId", cycle.getId().toString())
                        .param("name", "cand-sa")
                        .header("Authorization", bearer("elena-cand-sa")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.fullName == '" + devin.getFullName() + "')]")
                        .isEmpty());
    }

    // ============================================================ what must keep working

    @Test
    @DisplayName("the administration console still works, which stripping Employee would have broken")
    void theConsoleStillWorks() throws Exception {
        org.user("devin-console", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        mvc.perform(get(USERS).header("Authorization", bearer("devin-console")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me").header("Authorization", bearer("devin-console")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.landing").value("/admin/users"));
    }
}
