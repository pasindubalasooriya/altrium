package com.altrium.auth;

import com.altrium.org.Role;
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
 * Feature 1 - identity resolution and role-based landing.
 *
 * <p>Every test calls the endpoint directly with a bearer token. Nothing here drives a UI: a
 * UI-driven test proves a control is hidden, not that access is refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class IdentityAndLandingTest {

    private static final String ME = "/api/me";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    @Test
    @DisplayName("no token at all is 401, not 403 - the caller has not identified themselves yet")
    void noTokenIsUnauthorized() throws Exception {
        mvc.perform(get(ME))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("P-0.5: a valid Asgardeo token for someone with no app_user row is 403")
    void P_0_5_unprovisionedSubjectIsForbidden() throws Exception {
        // Authenticated by Asgardeo, unknown to Altrium. The response must not reveal that
        // the account simply is not provisioned.
        mvc.perform(get(ME).header("Authorization", "Bearer " + OrgFixture.tokenFor("ghost")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-0.7: a deactivated user is refused, but their row still exists")
    void P_0_7_deactivatedUserIsForbidden() throws Exception {
        org.deactivated("dormant", Role.EMPLOYEE, Role.MANAGER);
        org.flush();

        mvc.perform(get(ME).header("Authorization", "Bearer " + OrgFixture.tokenFor("dormant")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an active, provisioned user gets their identity back")
    void activeUserIsResolved() throws Exception {
        org.user("ana", Role.EMPLOYEE);
        org.flush();

        mvc.perform(get(ME).header("Authorization", "Bearer " + OrgFixture.tokenFor("ana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("ana"))
                .andExpect(jsonPath("$.roles[0]").value("EMPLOYEE"))
                .andExpect(jsonPath("$.landing").value("/my/reviews"));
    }

    @Test
    @DisplayName("P-0.1: roles are additive, and landing takes the most privileged")
    void P_0_1_landingUsesMostPrivilegedRole() throws Exception {
        // Someone who manages a team and also works in HR must not land on the manager
        // screen just because of enum ordering.
        org.user("hilda", Role.EMPLOYEE, Role.MANAGER, Role.HR);
        org.flush();

        mvc.perform(get(ME).header("Authorization", "Bearer " + OrgFixture.tokenFor("hilda")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.landing").value("/hr/cycles"));
    }

    @Test
    @DisplayName("a token's role claim grants nothing - the database governs authorities")
    void tokenRoleClaimGrantsNothing() throws Exception {
        // Provisioned as a plain employee, but presenting a token that asserts SUPER_ADMIN.
        // If claims were trusted, this would land on the admin console.
        org.user("mallory", Role.EMPLOYEE);
        org.flush();

        mvc.perform(get(ME).header("Authorization",
                        "Bearer " + OrgFixture.tokenClaiming("mallory", Role.SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("EMPLOYEE"))
                .andExpect(jsonPath("$.landing").value("/my/reviews"));
    }

    @Test
    @DisplayName("a stale role claim cannot resurrect a revoked role")
    void staleRoleClaimIsIgnored() throws Exception {
        // The HR role was removed in the database, but the caller's token was minted before
        // that and still asserts it. P-2.5's principle: the change applies on the very next
        // request, not when the token happens to expire.
        org.user("ex-hr", Role.EMPLOYEE);
        org.flush();

        mvc.perform(get(ME).header("Authorization",
                        "Bearer " + OrgFixture.tokenClaiming("ex-hr", Role.HR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.landing").value("/my/reviews"));
    }

    @Test
    @DisplayName("/me never carries review, rating or plan content")
    void meCarriesIdentityOnly() throws Exception {
        org.user("nadia", Role.EMPLOYEE);
        org.flush();

        mvc.perform(get(ME).header("Authorization", "Bearer " + OrgFixture.tokenFor("nadia")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").doesNotExist())
                .andExpect(jsonPath("$.reviews").doesNotExist())
                .andExpect(jsonPath("$.plan").doesNotExist());
    }
}
