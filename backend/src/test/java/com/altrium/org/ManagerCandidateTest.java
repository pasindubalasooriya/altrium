package com.altrium.org;

// The OrgFixture field below is named `org`, which shadows the `org` package inside this
// class, so every com.altrium.org type has to arrive by import rather than fully qualified.
import com.altrium.testsupport.OrgFixture;
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
 * "Who could be this person's manager", answered in SQL.
 *
 * <p>Added for the reporting-line picker, which previously asked the Super Admin to type a
 * numeric id. The list it now offers has to be the same set the write accepts, or it offers a
 * choice the server then refuses - so every refusal {@code OrgService.assignManager} makes is
 * carried in the {@code WHERE} clause here.
 *
 * <p>The interesting one is {@link #indirectReportIsNotOffered()}. A version of this filter
 * that excluded only direct reports would pass every other test in the file and would still
 * offer a grandchild, which is a loop the write rejects with 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class ManagerCandidateTest {

    private static final String USERS = "/api/admin/users";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    private String bearer(String handle) {
        return "Bearer " + OrgFixture.tokenFor(handle);
    }

    /**
     * A four-deep chain plus an unrelated colleague, all sharing one search suffix so the
     * assertions describe this test's people rather than the seeded organisation.
     *
     * <p>{@code top -> mid -> low -> deep}, with {@code other} off to one side.
     */
    private AppUser[] chain(String suffix) {
        AppUser top = org.user("mc-a-top" + suffix);
        AppUser mid = org.user("mc-b-mid" + suffix);
        AppUser low = org.user("mc-c-low" + suffix);
        AppUser deep = org.user("mc-d-deep" + suffix);
        AppUser other = org.user("mc-e-other" + suffix);

        mid.setManager(top);
        low.setManager(mid);
        deep.setManager(low);

        org.user("mc-z-devin" + suffix, Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();
        return new AppUser[] {top, mid, low, deep, other};
    }

    @Test
    @DisplayName("P-1.4: a person is not offered as their own manager")
    void P_1_4_theUserIsNotOfferedAsTheirOwnManager() throws Exception {
        AppUser[] people = chain("-self");
        AppUser mid = people[1];

        mvc.perform(get(USERS)
                        .param("managerCandidateFor", String.valueOf(mid.getId()))
                        .param("search", "-self")
                        .header("Authorization", bearer("mc-z-devin-self")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + mid.getId() + ")]").isEmpty());
    }

    @Test
    @DisplayName("P-1.4: a direct report is not offered")
    void P_1_4_aDirectReportIsNotOffered() throws Exception {
        AppUser[] people = chain("-direct");
        AppUser mid = people[1];
        AppUser low = people[2];

        mvc.perform(get(USERS)
                        .param("managerCandidateFor", String.valueOf(mid.getId()))
                        .param("search", "-direct")
                        .header("Authorization", bearer("mc-z-devin-direct")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + low.getId() + ")]").isEmpty());
    }

    /**
     * The test the one-level implementation fails.
     *
     * <p>{@code deep} reports to {@code low}, who reports to {@code mid}. Making {@code deep}
     * the manager of {@code mid} closes a three-node loop, and the walk has to go all the way
     * down to see it.
     */
    @Test
    @DisplayName("P-1.4: an indirect report is not offered either - the walk is transitive")
    void indirectReportIsNotOffered() throws Exception {
        AppUser[] people = chain("-deep");
        AppUser mid = people[1];
        AppUser deep = people[3];

        mvc.perform(get(USERS)
                        .param("managerCandidateFor", String.valueOf(mid.getId()))
                        .param("search", "-deep")
                        .header("Authorization", bearer("mc-z-devin-deep")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + deep.getId() + ")]").isEmpty());
    }

    /**
     * The filter excludes the branch beneath the person, not the organisation around them.
     * Without this, an implementation that returned nothing at all would pass the three tests
     * above.
     */
    @Test
    @DisplayName("P-1.4: the person's own manager and an unrelated colleague are both offered")
    void peopleWhoFormNoLoopAreOffered() throws Exception {
        AppUser[] people = chain("-ok");
        AppUser top = people[0];
        AppUser mid = people[1];
        AppUser other = people[4];

        mvc.perform(get(USERS)
                        .param("managerCandidateFor", String.valueOf(mid.getId()))
                        .param("search", "-ok")
                        .header("Authorization", bearer("mc-z-devin-ok")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + top.getId() + ")]").isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.id == " + other.getId() + ")]").isNotEmpty());
    }

    /** {@code assignManager} refuses a deactivated manager, so the list must not offer one. */
    @Test
    @DisplayName("P-0.7: a deactivated person is not offered as a manager")
    void P_0_7_aDeactivatedUserIsNotOffered() throws Exception {
        AppUser[] people = chain("-gone");
        AppUser mid = people[1];
        AppUser gone = org.deactivated("mc-f-gone-gone");
        org.flush();

        mvc.perform(get(USERS)
                        .param("managerCandidateFor", String.valueOf(mid.getId()))
                        .param("search", "-gone")
                        .header("Authorization", bearer("mc-z-devin-gone")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + gone.getId() + ")]").isEmpty());
    }

    /**
     * The one place this list is deliberately narrower than the write.
     *
     * <p>{@code assignManager} still accepts the Super Admin. Offering them would be offering a
     * mistake: a Super Admin holding a reporting line gains {@code DIRECT_MANAGER} grounds and
     * with them {@code WRITE_MANAGER_REVIEW}, which is the review access P-9.4 denies the role.
     * This test pins the console side of that; the write side is a separate change.
     */
    @Test
    @DisplayName("P-9.5: the Super Admin is not offered as a manager")
    void P_9_5_theSuperAdminIsNotOffered() throws Exception {
        AppUser[] people = chain("-sa");
        AppUser mid = people[1];

        mvc.perform(get(USERS)
                        .param("managerCandidateFor", String.valueOf(mid.getId()))
                        .param("search", "mc-z-devin-sa")
                        .header("Authorization", bearer("mc-z-devin-sa")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    /**
     * The count has to describe the candidates, not the organisation with the ineligible rows
     * dropped afterwards. A client-side filter would pass every test above this one and fail
     * here, because it would report five and then render three.
     */
    @Test
    @DisplayName("the total counts candidates, not everybody with the rest discarded")
    void searchNarrowsWithinTheCandidateSetNotTheOrganisation() throws Exception {
        AppUser[] people = chain("-count");
        AppUser mid = people[1];
        org.deactivated("mc-f-gone-count");
        org.flush();

        // Seven people carry the suffix: the four in the chain, the unrelated colleague, the
        // Super Admin and the deactivated one. Three are candidates for mid - top, other, and
        // nobody else: low and deep are beneath them, mid is themselves, and the last two are
        // excluded outright.
        mvc.perform(get(USERS)
                        .param("managerCandidateFor", String.valueOf(mid.getId()))
                        .param("search", "-count")
                        .header("Authorization", bearer("mc-z-devin-count")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    /** The filter is opt-in. Without the parameter the endpoint is what it always was. */
    @Test
    @DisplayName("omitting the parameter leaves the list unfiltered")
    void absentParameterChangesNothing() throws Exception {
        AppUser[] people = chain("-omit");
        AppUser mid = people[1];

        mvc.perform(get(USERS)
                        .param("search", "-omit")
                        .header("Authorization", bearer("mc-z-devin-omit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.content[?(@.id == " + mid.getId() + ")]").isNotEmpty());
    }
}
