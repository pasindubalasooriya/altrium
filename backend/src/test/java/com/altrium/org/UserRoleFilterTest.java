package com.altrium.org;

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
 * Filtering the user console by role, in SQL.
 *
 * <p>Added for the HR-grants screen, which needs "the HR users" to choose from. Picking them
 * out of a fetched page in the client would have meant "the HR users who happen to be on this
 * page" - correct at thirty people and wrong at three hundred, and wrong silently. Nothing may
 * be hardcoded to the size of the organisation, and a filter applied after paging is exactly
 * that mistake wearing a different hat.
 *
 * <p>The last test is the one that would have caught the client-side version: the match is
 * found on a later page, which a filter applied to page zero could never have seen.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class UserRoleFilterTest {

    private static final String USERS = "/api/admin/users";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    private String bearer(String handle) {
        return "Bearer " + OrgFixture.tokenFor(handle);
    }

    @Test
    @DisplayName("filtering by role returns only holders of that role, counted honestly")
    void roleFilterIsAppliedInTheQuery() throws Exception {
        org.user("devin-role", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.user("hana-role", Role.EMPLOYEE, Role.HR);
        org.user("rosa-role", Role.EMPLOYEE, Role.HR);
        org.user("john-role", Role.EMPLOYEE);
        org.user("elena-role", Role.EMPLOYEE, Role.MANAGER);
        org.flush();

        mvc.perform(get(USERS)
                        .param("role", "HR")
                        .header("Authorization", bearer("devin-role")))
                .andExpect(status().isOk())
                // Two HR users, and the total says two - not five with three discarded.
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    @DisplayName("roles are additive, so somebody holding two roles matches both filters")
    void additiveRolesMatchEitherFilter() throws Exception {
        org.user("devin-add", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.user("kevin-add", Role.EMPLOYEE, Role.HR, Role.MANAGER);
        org.flush();

        // The HR Head is both, and neither filter may hide him from the other (P-0.1).
        mvc.perform(get(USERS).param("role", "HR").param("search", "kevin-add")
                        .header("Authorization", bearer("devin-add")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get(USERS).param("role", "MANAGER").param("search", "kevin-add")
                        .header("Authorization", bearer("devin-add")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("the role filter combines with the others rather than replacing them")
    void roleFilterCombinesWithTheOthers() throws Exception {
        Department people = org.department("People-role");
        Department engineering = org.department("Engineering-role");
        org.user("devin-combine", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser hana = org.userIn(people, "hana-combine", Role.EMPLOYEE, Role.HR);
        org.userIn(engineering, "rosa-combine", Role.EMPLOYEE, Role.HR);
        org.flush();

        // Two HR users, one per department. Filtering by both narrows to one, rather than the
        // later predicate replacing the earlier.
        mvc.perform(get(USERS)
                        .param("role", "HR")
                        .param("departmentId", people.getId().toString())
                        .header("Authorization", bearer("devin-combine")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].fullName").value(hana.getFullName()));
    }

    @Test
    @DisplayName("a match beyond the first page is still found, which a client-side filter could not do")
    void aMatchOnALaterPageIsStillFound() throws Exception {
        org.user("devin-page", Role.EMPLOYEE, Role.SUPER_ADMIN);
        // Enough people to push the one HR user well past a first page of five.
        for (int i = 0; i < 12; i++) {
            org.user("filler-page-" + i, Role.EMPLOYEE);
        }
        org.user("zoe-page", Role.EMPLOYEE, Role.HR);
        org.flush();

        // Sorted by name, "zoe-page" is last. A filter applied to page zero of everybody would
        // return nothing here and look like "there are no HR users".
        mvc.perform(get(USERS)
                        .param("role", "HR")
                        .param("size", "5")
                        .header("Authorization", bearer("devin-page")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("P-9.1: the role filter is still Super Admin only, like the rest of the console")
    void P_9_1_roleFilterIsNotAWayInForOthers() throws Exception {
        org.user("hana-gate", Role.EMPLOYEE, Role.HR);
        org.flush();

        mvc.perform(get(USERS).param("role", "HR")
                        .header("Authorization", bearer("hana-gate")))
                .andExpect(status().isForbidden());
    }
}
