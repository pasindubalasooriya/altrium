package com.altrium.config;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The browser-facing half of the API contract.
 *
 * <p>Without this configuration the frontend cannot make a single call: the preflight is refused
 * before the filter chain runs, so no token is ever presented and every screen fails identically
 * with nothing in the server log to explain it.
 *
 * <p><strong>None of this is access control.</strong> CORS decides which web pages may read a
 * response; it decides nothing about who may have the data. The tests below assert that the
 * browser is told the right thing, and the last one asserts the thing that actually matters -
 * that an allowed origin buys no access at all on its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class CorsTest {

    private static final String ALLOWED = "http://localhost:5173";
    private static final String ELSEWHERE = "http://evil.example";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("a preflight from the frontend origin is answered without a token")
    void preflightFromAllowedOriginSucceeds() throws Exception {
        // The browser sends this before it will attach an Authorization header at all, so
        // requiring a token here would be a deadlock rather than a check.
        mvc.perform(options("/api/me")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
    }

    @Test
    @DisplayName("a preflight from an unlisted origin is refused")
    void preflightFromUnknownOriginIsRefused() throws Exception {
        mvc.perform(options("/api/me")
                        .header("Origin", ELSEWHERE)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("credentials are never allowed - Altrium carries bearer tokens, not cookies")
    void credentialsAreNotAllowed() throws Exception {
        mvc.perform(options("/api/me")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    @DisplayName("an allowed origin grants nothing: an unprovisioned caller is still 403")
    void allowedOriginIsNotAccess() throws Exception {
        // The point of the whole configuration, stated as a test. CORS opens a channel; the
        // authorization component still decides what comes down it.
        mvc.perform(get("/api/me")
                        .header("Origin", ALLOWED)
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("ghost")))
                .andExpect(status().isForbidden());
    }
}
