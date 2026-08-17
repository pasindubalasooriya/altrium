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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature 5 - cycle and cohort configuration over HTTP (P-6.1, P-6.2, P-9.3).
 *
 * <p>Every denial here is a direct call with a minted JWT. The Super Admin endpoints carry no
 * {@code @PreAuthorize}, so if the capability check in {@link CycleService} were ever removed,
 * these would fail rather than quietly keep passing on the strength of a hidden menu item.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class CycleConfigurationTest {

    private static final String CYCLES = "/api/admin/cycles";
    private static final String COHORTS = "/api/admin/cohorts";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    @Autowired
    private ReviewFixture reviews;

    @Autowired
    private CycleParticipantRepository participants;

    private String bearer(String handle) {
        return "Bearer " + OrgFixture.tokenFor(handle);
    }

    // ------------------------------------------------------------------ who may configure

    @Test
    @DisplayName("P-6.1: the Super Admin configures a cycle, which is not open yet")
    void P_6_1_superAdminConfiguresACycle() throws Exception {
        org.user("devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        mvc.perform(post(CYCLES).header("Authorization", bearer("devin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"financialYear":2031,"quadrimesterNo":2,
                                 "startDate":"2031-05-01","endDate":"2031-08-31"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("FY2031 Q2"))
                // Configuring is a promise about a date, not an act of opening. The sweep is
                // what opens it, which is the whole point of "no per-review manual setup".
                .andExpect(jsonPath("$.status").value("CONFIGURED"))
                .andExpect(jsonPath("$.openedAt").doesNotExist());
    }

    @Test
    @DisplayName("P-6.1: HR cannot configure a cycle, however wide their grants")
    void P_6_1_hrCannotConfigureACycle() throws Exception {
        Department peopleOps = org.department("People Operations");
        org.userIn(peopleOps, "kevin", Role.EMPLOYEE, Role.HR);
        org.flush();

        // HR run cycles operationally and still do not own the configuration. Deciding who is
        // reviewed and when is the Super Admin's, and an HR user who could set it could put
        // their own department outside the window they are judged in.
        mvc.perform(post(CYCLES).header("Authorization", bearer("kevin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"financialYear":2032,"quadrimesterNo":1,
                                 "startDate":"2032-01-01","endDate":"2032-04-30"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-6.1: a manager cannot configure cohorts")
    void P_6_1_managerCannotConfigureCohorts() throws Exception {
        Department engineering = org.department("Engineering");
        org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        org.flush();

        mvc.perform(post(COHORTS).header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Elena's people","quadrimesterNo":1}"""))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ the date lock

    @Test
    @DisplayName("P-6.2: the start date moves freely before the cycle opens")
    void P_6_2_datesMoveBeforeOpening() throws Exception {
        org.user("devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();
        ReviewCycle cycle = reviews.configuredCycle(1, LocalDate.of(2033, 1, 1));
        reviews.flush();

        mvc.perform(put(CYCLES + "/" + cycle.getId() + "/dates")
                        .header("Authorization", bearer("devin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2033-02-01","endDate":"2033-05-31"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2033-02-01"));
    }

    @Test
    @DisplayName("P-6.2: once opened, the dates are fixed - 409, not 403")
    void P_6_2_datesAreLockedOnceOpened() throws Exception {
        org.user("devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();
        ReviewCycle cycle = reviews.openCycle();
        reviews.flush();

        // 409 rather than 403 on purpose. The Super Admin has every right to configure cycles;
        // it is this cycle's state that forbids the write. Answering 403 would tell them they
        // lack a permission they hold, and send them looking for it.
        mvc.perform(put(CYCLES + "/" + cycle.getId() + "/dates")
                        .header("Authorization", bearer("devin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2034-02-01","endDate":"2034-05-31"}"""))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("P-6.1: two cycles cannot occupy one quadrimester")
    void P_6_1_oneCyclePerQuadrimester() throws Exception {
        org.user("devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        String body = """
                {"financialYear":2035,"quadrimesterNo":3,
                 "startDate":"2035-09-01","endDate":"2035-12-31"}""";

        mvc.perform(post(CYCLES).header("Authorization", bearer("devin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // Two would make "the open cycle" ambiguous, and every artifact is keyed to exactly
        // one of them.
        mvc.perform(post(CYCLES).header("Authorization", bearer("devin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ cohorts

    @Test
    @DisplayName("P-6.1: opening by hand runs the same intake the sweep does")
    void P_6_1_manualOpenRunsTheSameIntake() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser devin = org.userIn(engineering, "devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        Cohort q1 = reviews.cohort("Q1 cohort", 1);
        reviews.member(q1, john);
        ReviewCycle cycle = reviews.configuredCycle(1, LocalDate.of(2036, 1, 1));
        reviews.flush();

        mvc.perform(post(CYCLES + "/" + cycle.getId() + "/open")
                        .header("Authorization", bearer("devin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        // Same intake, so the same exclusions apply. A manual path with its own copy would be
        // the one where P-0.7 and P-1.5 were eventually forgotten.
        assertThat(participants.existsByCycleIdAndSubjectId(cycle.getId(), john.getId())).isTrue();
        assertThat(participants.existsByCycleIdAndSubjectId(cycle.getId(), devin.getId())).isFalse();
    }

    @Test
    @DisplayName("P-1.5: Leadership cannot be put in a cohort at all")
    void P_1_5_leadershipCannotJoinACohort() throws Exception {
        org.user("devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        org.flush();
        Cohort q1 = reviews.cohort("Q1 cohort", 1);
        reviews.flush();

        // Refused here, at configuration, rather than silently skipped every quarter at
        // intake. A Leadership member sitting in a cohort would read as an intention the
        // system quietly declines to carry out, which is worse than one clear refusal.
        mvc.perform(post(COHORTS + "/" + q1.getId() + "/members")
                        .header("Authorization", bearer("devin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + richard.getId() + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Scenario section 4: adding to a second cohort moves the employee, never duplicates them")
    void section_4_anEmployeeBelongsToExactlyOneCohort() throws Exception {
        org.user("devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        Cohort q1 = reviews.cohort("Q1 cohort", 1);
        Cohort q2 = reviews.cohort("Q2 cohort", 2);
        reviews.member(q1, john);
        reviews.flush();

        // "Assessed once per year in a fixed quadrimester" only holds if this is a move. Two
        // rows would review him twice in one year, which is the thing cohorts exist to prevent.
        mvc.perform(post(COHORTS + "/" + q2.getId() + "/members")
                        .header("Authorization", bearer("devin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + john.getId() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cohortId").value(q2.getId()));
    }

    // ------------------------------------------------------------------ the blocked path

    @Test
    @DisplayName("Section 15.4: removing somebody from a cohort mid-cycle is refused, not guessed")
    void section_15_4_removalAfterOpeningIsRefused() throws Exception {
        org.user("devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        Cohort q1 = reviews.cohort("Q1 cohort", 1);
        reviews.member(q1, john);
        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.selfReview(cycle, john, "shipped the migration");
        reviews.flush();

        // The Product Owner has not said what becomes of the self-review he has already
        // written, or of peer feedback written about him. Discarding it and leaving a review
        // running for somebody no longer in the cohort are different products, so the system
        // refuses rather than picking one by accident. 409: the Super Admin has the
        // permission, and it is the open cycle that forbids the write.
        mvc.perform(delete(COHORTS + "/members/" + john.getId())
                        .header("Authorization", bearer("devin")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Section 15.4: removal is fine before the cycle opens")
    void section_15_4_removalBeforeOpeningIsAllowed() throws Exception {
        org.user("devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        Cohort q1 = reviews.cohort("Q1 cohort", 1);
        reviews.member(q1, john);
        reviews.flush();

        // Nothing is in flight, so there is no open question to protect. Only the mid-cycle
        // case is blocked, which keeps the refusal narrow enough to be honest.
        mvc.perform(delete(COHORTS + "/members/" + john.getId())
                        .header("Authorization", bearer("devin")))
                .andExpect(status().isNoContent());
    }
}
