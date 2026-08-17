package com.altrium.auth;

import com.altrium.org.AppUser;
import com.altrium.org.Department;
import com.altrium.org.HrGrantService;
import com.altrium.org.Role;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature 3 - HR department grants (P-2.1 to P-2.5, P-9.2).
 *
 * <p>This is the conflict-of-interest control, so the tests that matter are the ones proving
 * an HR user cannot widen their own reach, and that revoking reaches them immediately rather
 * than whenever their token happens to expire.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class HrGrantScopeTest {

    private static final String GRANTS = "/api/admin/hr-grants";
    private static final String SCOPE = "/api/hr/scope";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    @Autowired
    private HrGrantService grants;

    private String bearer(String handle) {
        return "Bearer " + OrgFixture.tokenFor(handle);
    }

    // ------------------------------------------------------------------ who may grant

    @Test
    @DisplayName("P-9.2: HR cannot grant themselves a department")
    void P_9_2_hrCannotGrantThemselves() throws Exception {
        Department sales = org.department("Sales");
        AppUser hr = org.userIn(org.department("People"), "grabby", Role.EMPLOYEE, Role.HR);
        org.flush();

        // If this succeeded, an HR user could hand themselves every department and the
        // scoping control would be decorative.
        mvc.perform(post(GRANTS)
                        .header("Authorization", bearer("grabby"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hrUserId\":%d,\"departmentId\":%d,\"explicitGrant\":false}"
                                .formatted(hr.getId(), sales.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-9.2: HR cannot promote themselves to HR Head by setting the explicit flag")
    void P_9_2_hrCannotSetExplicitFlag() throws Exception {
        Department people = org.department("People");
        AppUser hr = org.userIn(people, "climber", Role.EMPLOYEE, Role.HR);
        org.flush();
        grants.grant(hr.getId(), people.getId(), false, null, g -> g.getId());

        // Setting this themselves would lift their own-department block (P-2.4) and let them
        // oversee reviews in the department they work in.
        mvc.perform(put(GRANTS + "/" + hr.getId() + "/" + people.getId() + "/explicit")
                        .header("Authorization", bearer("climber"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"explicitGrant\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a manager cannot grant HR departments either")
    void managerCannotGrant() throws Exception {
        Department sales = org.department("Sales");
        AppUser hr = org.userIn(org.department("People"), "target", Role.EMPLOYEE, Role.HR);
        org.user("boss", Role.EMPLOYEE, Role.MANAGER);
        org.flush();

        mvc.perform(post(GRANTS)
                        .header("Authorization", bearer("boss"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hrUserId\":%d,\"departmentId\":%d,\"explicitGrant\":false}"
                                .formatted(hr.getId(), sales.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a Super Admin can grant, and the grant records who made it")
    void superAdminCanGrant() throws Exception {
        Department sales = org.department("Sales");
        AppUser hr = org.userIn(org.department("People"), "helen", Role.EMPLOYEE, Role.HR);
        org.user("root", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        mvc.perform(post(GRANTS)
                        .header("Authorization", bearer("root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hrUserId\":%d,\"departmentId\":%d,\"explicitGrant\":false}"
                                .formatted(hr.getId(), sales.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.departmentName").exists())
                .andExpect(jsonPath("$.explicitGrant").value(false))
                .andExpect(jsonPath("$.grantedByName").value("root"));
    }

    @Test
    @DisplayName("granting a department to someone without the HR role is refused")
    void grantToNonHrRefused() throws Exception {
        Department sales = org.department("Sales");
        AppUser plain = org.user("plain", Role.EMPLOYEE);
        org.user("root2", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        // Storing it would leave a row that reads like access having been given, while
        // granting nothing.
        mvc.perform(post(GRANTS)
                        .header("Authorization", bearer("root2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hrUserId\":%d,\"departmentId\":%d,\"explicitGrant\":false}"
                                .formatted(plain.getId(), sales.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("granting the same department twice is a 409, not a silent second row")
    void duplicateGrantIsConflict() throws Exception {
        Department sales = org.department("Sales");
        AppUser hr = org.userIn(org.department("People"), "dup", Role.EMPLOYEE, Role.HR);
        org.user("root3", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();
        grants.grant(hr.getId(), sales.getId(), false, null, g -> g.getId());

        // Two rows disagreeing on the explicit flag would make the own-department decision
        // depend on row order.
        mvc.perform(post(GRANTS)
                        .header("Authorization", bearer("root3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hrUserId\":%d,\"departmentId\":%d,\"explicitGrant\":true}"
                                .formatted(hr.getId(), sales.getId())))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ the scope rules

    @Test
    @DisplayName("P-2.3: an HR user's own department is excluded from their scope")
    void P_2_3_ownDepartmentExcluded() throws Exception {
        Department people = org.department("People");
        Department sales = org.department("Sales");
        AppUser hana = org.userIn(people, "hana", Role.EMPLOYEE, Role.HR);
        org.flush();

        // Granted both, including her own - but a plain grant over your own department buys
        // nothing, which is what makes this a segregation of duties rather than a formality.
        grants.grant(hana.getId(), sales.getId(), false, null, g -> g.getId());
        grants.grant(hana.getId(), people.getId(), false, null, g -> g.getId());

        mvc.perform(get(SCOPE).header("Authorization", bearer("hana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments.length()").value(1))
                .andExpect(jsonPath("$.departments[0].name").value(sales.getName()))
                .andExpect(jsonPath("$.ownDepartmentExcluded").value(true))
                .andExpect(jsonPath("$.ownDepartmentLiftedByExplicitGrant").value(false));
    }

    @Test
    @DisplayName("P-2.4: the explicit grant lifts the own-department block - the HR Head")
    void P_2_4_explicitGrantLiftsOwnDepartment() throws Exception {
        Department people = org.department("People");
        AppUser kevin = org.userIn(people, "kevin", Role.EMPLOYEE, Role.HR);
        org.flush();

        grants.grant(kevin.getId(), people.getId(), true, null, g -> g.getId());

        mvc.perform(get(SCOPE).header("Authorization", bearer("kevin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments.length()").value(1))
                .andExpect(jsonPath("$.departments[0].isOwnDepartment").value(true))
                .andExpect(jsonPath("$.ownDepartmentExcluded").value(false))
                .andExpect(jsonPath("$.ownDepartmentLiftedByExplicitGrant").value(true))
                // The response must not imply the override goes further than it does.
                .andExpect(jsonPath("$.note").value(
                        containsString("cannot access your own reviews")));
    }

    @Test
    @DisplayName("an HR user with no grants has an empty scope, not a wide one")
    void ungrantedHrHasEmptyScope() throws Exception {
        org.userIn(org.department("People"), "newhr", Role.EMPLOYEE, Role.HR);
        org.flush();

        // Failing open here would hand a brand-new HR account the whole organisation.
        mvc.perform(get(SCOPE).header("Authorization", bearer("newhr")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments.length()").value(0));
    }

    @Test
    @DisplayName("a non-HR caller cannot read the HR scope endpoint at all")
    void nonHrCannotReadScope() throws Exception {
        org.user("nosy", Role.EMPLOYEE, Role.MANAGER);
        org.flush();

        mvc.perform(get(SCOPE).header("Authorization", bearer("nosy")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ P-2.5, the core rule

    @Test
    @DisplayName("P-2.5: revoking a grant applies on the very next request, with the same token")
    void P_2_5_revocationAppliesOnNextRequest() throws Exception {
        Department sales = org.department("Sales");
        AppUser hr = org.userIn(org.department("People"), "scoped", Role.EMPLOYEE, Role.HR);
        org.user("root4", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        grants.grant(hr.getId(), sales.getId(), false, null, g -> g.getId());

        // Before: in scope.
        mvc.perform(get(SCOPE).header("Authorization", bearer("scoped")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments.length()").value(1));

        mvc.perform(delete(GRANTS + "/" + hr.getId() + "/" + sales.getId())
                        .header("Authorization", bearer("root4")))
                .andExpect(status().isNoContent());

        // After: gone - same token, no re-login, no waiting for expiry. If the scope were
        // resolved at login or cached on the authentication, this would still return 1, and
        // a revoked HR user would keep their access for the life of their token.
        mvc.perform(get(SCOPE).header("Authorization", bearer("scoped")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments.length()").value(0));
    }

    @Test
    @DisplayName("P-2.5: a newly granted department is visible on the next request too")
    void P_2_5_grantAppliesOnNextRequest() throws Exception {
        Department sales = org.department("Sales");
        AppUser hr = org.userIn(org.department("People"), "waiting", Role.EMPLOYEE, Role.HR);
        org.user("root5", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        mvc.perform(get(SCOPE).header("Authorization", bearer("waiting")))
                .andExpect(jsonPath("$.departments.length()").value(0));

        mvc.perform(post(GRANTS)
                        .header("Authorization", bearer("root5"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hrUserId\":%d,\"departmentId\":%d,\"explicitGrant\":false}"
                                .formatted(hr.getId(), sales.getId())))
                .andExpect(status().isCreated());

        mvc.perform(get(SCOPE).header("Authorization", bearer("waiting")))
                .andExpect(jsonPath("$.departments.length()").value(1));
    }

    @Test
    @DisplayName("P-2.4: clearing the explicit flag removes the own department immediately")
    void P_2_4_clearingExplicitFlagAppliesImmediately() throws Exception {
        Department people = org.department("People");
        AppUser head = org.userIn(people, "demoted", Role.EMPLOYEE, Role.HR);
        org.user("root6", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        grants.grant(head.getId(), people.getId(), true, null, g -> g.getId());

        mvc.perform(get(SCOPE).header("Authorization", bearer("demoted")))
                .andExpect(jsonPath("$.departments.length()").value(1));

        mvc.perform(put(GRANTS + "/" + head.getId() + "/" + people.getId() + "/explicit")
                        .header("Authorization", bearer("root6"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"explicitGrant\":false}"))
                .andExpect(status().isOk());

        // Demotion from HR Head takes effect at once, not at token expiry.
        mvc.perform(get(SCOPE).header("Authorization", bearer("demoted")))
                .andExpect(jsonPath("$.departments.length()").value(0))
                .andExpect(jsonPath("$.ownDepartmentExcluded").value(true));
    }
}
