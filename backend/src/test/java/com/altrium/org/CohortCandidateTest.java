package com.altrium.org;

// The OrgFixture field below is named `org`, which shadows the `org` package inside this
// class, so every com.altrium.org type has to arrive by import rather than fully qualified.
import com.altrium.review.Cohort;
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
 * "Who is not in a cohort yet", answered in SQL.
 *
 * <p>Added for the cohort screen, which offers a list of people to add and previously offered a
 * name search across everybody - so an administrator could pick somebody already placed, and the
 * only feedback was the add silently moving them out of the cohort they were in.
 *
 * <p>The predicate is in the {@code WHERE} clause for the same reason the role filter is. A
 * client that fetched a page and dropped the already-placed rows would mean "the unassigned
 * people who happen to be on this page", which at a hundred employees mostly reports an empty
 * list rather than an error. The fourth test is the one that would catch that version.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class CohortCandidateTest {

    private static final String USERS = "/api/admin/users";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    @Autowired
    private ReviewFixture reviews;

    private String bearer(String handle) {
        return "Bearer " + OrgFixture.tokenFor(handle);
    }

    @Test
    @DisplayName("inCohort=false returns only people not yet placed, counted honestly")
    void unassignedUsersOnly() throws Exception {
        org.user("devin-cand", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser placed = org.user("placed-cand", Role.EMPLOYEE);
        org.user("free-a-cand", Role.EMPLOYEE);
        org.user("free-b-cand", Role.EMPLOYEE);
        org.flush();

        Cohort cohort = reviews.cohort("Spring-cand", 1);
        reviews.member(cohort, placed);
        reviews.flush();

        mvc.perform(get(USERS)
                        .param("inCohort", "false")
                        .param("search", "-cand")
                        .header("Authorization", bearer("devin-cand")))
                .andExpect(status().isOk())
                // Devin and the two free people. The placed one is gone, and the total says so
                // rather than saying four with one discarded afterwards.
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[?(@.fullName == '" + placed.getFullName() + "')]")
                        .isEmpty());
    }

    @Test
    @DisplayName("inCohort=true is the other half of the same predicate")
    void placedUsersOnly() throws Exception {
        org.user("devin-in", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser placed = org.user("placed-in", Role.EMPLOYEE);
        org.user("free-in", Role.EMPLOYEE);
        org.flush();

        Cohort cohort = reviews.cohort("Spring-in", 2);
        reviews.member(cohort, placed);
        reviews.flush();

        mvc.perform(get(USERS)
                        .param("inCohort", "true")
                        .param("search", "-in")
                        .header("Authorization", bearer("devin-in")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].fullName").value(placed.getFullName()));
    }

    @Test
    @DisplayName("omitting the parameter leaves the list unfiltered, as it was before")
    void absentParameterChangesNothing() throws Exception {
        org.user("devin-omit", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser placed = org.user("placed-omit", Role.EMPLOYEE);
        org.user("free-omit", Role.EMPLOYEE);
        org.flush();

        Cohort cohort = reviews.cohort("Spring-omit", 3);
        reviews.member(cohort, placed);
        reviews.flush();

        mvc.perform(get(USERS)
                        .param("search", "-omit")
                        .header("Authorization", bearer("devin-omit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    @DisplayName("an unassigned person beyond the first page is still found")
    void aMatchOnALaterPageIsStillFound() throws Exception {
        org.user("devin-admin-c", Role.EMPLOYEE, Role.SUPER_ADMIN);
        Cohort cohort = reviews.cohort("Spring-pgc", 1);

        // Twelve people, all already placed, sorted ahead of the one who is not.
        for (int i = 0; i < 12; i++) {
            AppUser filler = org.user("filler-pgc-" + i, Role.EMPLOYEE);
            org.flush();
            reviews.member(cohort, filler);
        }
        AppUser free = org.user("zoe-pgc", Role.EMPLOYEE);
        org.flush();
        reviews.flush();

        // Sorted by name, "zoe" is last. Fetching page zero of everybody and dropping the placed
        // rows would return nothing here, and would look like "everybody is already in a cohort".
        mvc.perform(get(USERS)
                        .param("inCohort", "false")
                        .param("search", "pgc")
                        .param("size", "5")
                        .header("Authorization", bearer("devin-admin-c")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].fullName").value(free.getFullName()));
    }

    @Test
    @DisplayName("P-9.1: the new filter is still Super Admin only, like the rest of the console")
    void P_9_1_theFilterIsNotAWayIn() throws Exception {
        org.user("hana-cand-gate", Role.EMPLOYEE, Role.HR);
        org.flush();

        mvc.perform(get(USERS).param("inCohort", "false")
                        .header("Authorization", bearer("hana-cand-gate")))
                .andExpect(status().isForbidden());
    }
}
