package com.altrium.org;

import com.altrium.testsupport.OrgFixture;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature 2 — who may manage the organisation (P-9.1).
 *
 * <p>These are the denial tests that matter most for this feature: org management is where
 * reporting lines and department membership are set, and both are inputs the entire
 * authorization model reads. Anyone who can edit them can rewrite who may see what.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class OrgAdminAccessTest {

    private static final String USERS = "/api/admin/users";
    private static final String DEPARTMENTS = "/api/admin/departments";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    private String bearer(String handle) {
        return "Bearer " + OrgFixture.tokenFor(handle);
    }

    @Test
    @DisplayName("P-9.1: a plain employee cannot list users")
    void P_9_1_employeeCannotListUsers() throws Exception {
        org.user("emp", Role.EMPLOYEE);
        org.flush();

        mvc.perform(get(USERS).header("Authorization", bearer("emp")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-9.1: a manager cannot manage the organisation")
    void P_9_1_managerCannotListUsers() throws Exception {
        // Managing a team is not the same as editing the org chart. A manager who could
        // reassign reporting lines could grant themselves reports, and with them access.
        org.user("mgr", Role.EMPLOYEE, Role.MANAGER);
        org.flush();

        mvc.perform(get(USERS).header("Authorization", bearer("mgr")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-9.1: HR cannot manage the organisation")
    void P_9_1_hrCannotManageUsers() throws Exception {
        // HR oversees cycles; the Super Admin owns structure. HR being able to move people
        // between departments would let them redraw their own scoping.
        org.user("hr", Role.EMPLOYEE, Role.HR);
        org.flush();

        mvc.perform(get(USERS).header("Authorization", bearer("hr")))
                .andExpect(status().isForbidden());
        mvc.perform(post(DEPARTMENTS)
                        .header("Authorization", bearer("hr"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Smuggled\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-9.1: Leadership cannot manage the organisation either")
    void P_9_1_leadershipCannotManageUsers() throws Exception {
        org.user("chief", Role.EMPLOYEE, Role.LEADERSHIP);
        org.flush();

        mvc.perform(get(USERS).header("Authorization", bearer("chief")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a Super Admin can list users")
    void superAdminCanListUsers() throws Exception {
        org.user("root", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        mvc.perform(get(USERS).header("Authorization", bearer("root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").exists());
    }

    @Test
    @DisplayName("nothing to do with org management leaks review content (P-9.4)")
    void P_9_4_orgManagementCarriesNoReviewContent() throws Exception {
        org.user("root2", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        mvc.perform(get(USERS).header("Authorization", bearer("root2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].rating").doesNotExist())
                .andExpect(jsonPath("$.content[0].reviews").doesNotExist())
                .andExpect(jsonPath("$.content[0].plan").doesNotExist());
    }

    @Test
    @DisplayName("a deactivated Super Admin loses admin access immediately (P-0.7)")
    void P_0_7_deactivatedSuperAdminIsRefused() throws Exception {
        org.deactivated("ex-root", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        mvc.perform(get(USERS).header("Authorization", bearer("ex-root")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the page size cannot be inflated to load the whole organisation")
    void pageSizeIsCapped() throws Exception {
        org.user("root3", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        mvc.perform(get(USERS).param("size", "100000").header("Authorization", bearer("root3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    @DisplayName("an employee cannot set a reporting line")
    void employeeCannotSetManager() throws Exception {
        AppUser victim = org.user("victim", Role.EMPLOYEE);
        org.user("climber", Role.EMPLOYEE);
        org.flush();

        mvc.perform(put(USERS + "/" + victim.getId() + "/manager")
                        .header("Authorization", bearer("climber"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"managerId\":null}"))
                .andExpect(status().isForbidden());
    }
}
