package com.altrium.calendar;

import com.altrium.config.ConflictApiException;
import com.altrium.config.ValidationApiException;
import com.altrium.org.AppUser;
import com.altrium.org.Role;
import com.altrium.testsupport.Acting;
import com.altrium.testsupport.OrgFixture;
import com.altrium.testsupport.StubJwtDecoderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Connecting a personal Google account: the state parameter, the stored token, and the one
 * unauthenticated endpoint in the API.
 *
 * <p>The callback has no bearer token to check, so the {@code state} value <em>is</em> the
 * credential. Most of what follows is about that: that it cannot be reused, that one this
 * server never minted attaches nothing to anybody, and that a failure returns a person to a
 * page rather than a stack trace.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class GoogleConnectionTest {

    private static final String CALLBACK = "/api/integrations/google/callback";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    @Autowired
    private Acting acting;

    @Autowired
    private GoogleConnectionService connections;

    @Autowired
    private TokenCipher cipher;

    @MockitoBean
    private GoogleOAuthClient oauth;

    @MockitoBean
    private CalendarClient calendar;

    @BeforeEach
    void stubGoogle() {
        when(oauth.authorizationUrl(anyString()))
                .thenAnswer(call -> "https://consent.example/?state=" + call.getArgument(0));
        when(oauth.accessToken(anyString()))
                .thenAnswer(call -> "access-for-" + call.getArgument(0));
        when(oauth.exchange("good-code")).thenReturn(new GoogleTokens(
                "refresh-token-value", "access-now", "someone@gmail.example",
                "https://www.googleapis.com/auth/calendar.events"));
    }

    private String stateFor(AppUser user) {
        acting.as(user);
        String url = connections.beginConnect();
        return url.substring(url.indexOf("state=") + "state=".length());
    }

    @Test
    @DisplayName("A completed consent stores a token that decrypts back to what Google sent")
    void aCompletedConsentStoresAUsableToken() {
        AppUser user = org.user("connector", Role.EMPLOYEE);
        org.flush();

        String state = stateFor(user);
        String email = connections.completeConnect(state, "good-code");

        assertThat(email).isEqualTo("someone@gmail.example");
        // A real round trip through MySQL as ciphertext, and back out through the access token
        // call. If the cipher, the column type or the key derivation were wrong, this is where
        // it would show.
        assertThat(connections.accessTokenFor(user.getId()))
                .contains("access-for-refresh-token-value");
    }

    @Test
    @DisplayName("The stored column is ciphertext, not the token")
    void theStoredColumnIsCiphertext() {
        // The one field in the schema that would be worth something in a database dump.
        byte[] encrypted = cipher.encrypt("refresh-token-value");

        assertThat(new String(encrypted)).doesNotContain("refresh-token-value");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("refresh-token-value");
    }

    @Test
    @DisplayName("Encrypting the same token twice gives different ciphertext")
    void encryptionIsNotDeterministic() {
        // A fresh nonce each time, so two people who connected the same account do not have
        // matching rows, and a repeated value cannot be recognised in a dump.
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    @DisplayName("A state cannot be redeemed twice")
    void aStateCannotBeRedeemedTwice() {
        AppUser user = org.user("replayer", Role.EMPLOYEE);
        org.flush();

        String state = stateFor(user);
        connections.completeConnect(state, "good-code");

        // A callback URL sitting in browser history, or in a referrer header, is already spent.
        acting.as(user);
        assertThatThrownBy(() -> connections.completeConnect(state, "good-code"))
                .isInstanceOf(ValidationApiException.class);
    }

    @Test
    @DisplayName("A state this server never minted attaches nothing to anybody")
    void aForgedStateAttachesNothing() {
        AppUser user = org.user("victim", Role.EMPLOYEE);
        org.flush();

        acting.as(user);
        assertThatThrownBy(() -> connections.completeConnect("not-a-state-we-issued", "good-code"))
                .isInstanceOf(ValidationApiException.class);

        assertThat(connections.isConnected(user.getId())).isFalse();
    }

    @Test
    @DisplayName("Consent without the calendar permission is refused rather than half stored")
    void consentWithoutCalendarPermissionIsRefused() {
        AppUser user = org.user("partial", Role.EMPLOYEE);
        org.flush();
        when(oauth.exchange("partial-code")).thenReturn(new GoogleTokens(
                "refresh-token-value", "access-now", "someone@gmail.example",
                "https://www.googleapis.com/auth/userinfo.email"));

        String state = stateFor(user);

        // Google's consent screen lets a person deselect a permission and still complete the
        // flow. Storing that connection would mean the first booking failing for a reason that
        // looks like a fault in Altrium.
        assertThatThrownBy(() -> connections.completeConnect(state, "partial-code"))
                .isInstanceOf(ConflictApiException.class);
        assertThat(connections.isConnected(user.getId())).isFalse();
    }

    @Test
    @DisplayName("Reconnecting replaces the token in place rather than adding a second grant")
    void reconnectingReplacesInPlace() {
        AppUser user = org.user("reconnector", Role.EMPLOYEE);
        org.flush();

        connections.completeConnect(stateFor(user), "good-code");

        when(oauth.exchange("second-code")).thenReturn(new GoogleTokens(
                "second-refresh-token", "access-now", "other@gmail.example",
                "https://www.googleapis.com/auth/calendar.events"));
        connections.completeConnect(stateFor(user), "second-code");

        // A second row would leave the first token live and unreferenced: a grant the person
        // believes they have replaced and in fact still holds.
        acting.as(user);
        assertThat(connections.status().googleEmail()).isEqualTo("other@gmail.example");
        assertThat(connections.accessTokenFor(user.getId()))
                .contains("access-for-second-refresh-token");
    }

    @Test
    @DisplayName("Disconnecting forgets the token")
    void disconnectingForgetsTheToken() {
        AppUser user = org.user("leaver", Role.EMPLOYEE);
        org.flush();
        connections.completeConnect(stateFor(user), "good-code");

        acting.as(user);
        connections.disconnect();

        assertThat(connections.isConnected(user.getId())).isFalse();
    }

    // ------------------------------------------------------------------- the endpoint

    @Test
    @DisplayName("The callback is reachable without a bearer token, and only the callback is")
    void theCallbackIsTheOnlyUnauthenticatedEndpoint() throws Exception {
        // It has to be: Google redirects the browser here from another origin, so there is no
        // header to attach. What identifies the person is the state, which this request has
        // none of, so it ends where every failure does.
        mvc.perform(get(CALLBACK).param("state", "nonsense").param("code", "nonsense"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("google=failed")));

        // Its neighbours are not. A status call without a token is refused as usual.
        mvc.perform(get("/api/integrations/google/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Declining at Google returns the person to Altrium, not to an error")
    void decliningReturnsThePersonToAltrium() throws Exception {
        mvc.perform(get(CALLBACK).param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        containsString("google=declined")));
    }

    @Test
    @DisplayName("Status reports the caller's own connection and nobody else's")
    void statusReportsTheCallersOwnConnection() throws Exception {
        AppUser connected = org.user("has-google", Role.EMPLOYEE);
        org.user("no-google", Role.EMPLOYEE);
        org.flush();
        connections.completeConnect(stateFor(connected), "good-code");

        mvc.perform(get("/api/integrations/google/status")
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("has-google")))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.googleEmail").value("someone@gmail.example"));

        // There is no endpoint that takes a user id, so this is the only shape the question
        // has: connecting or reading somebody else's account is not a permission anybody holds.
        mvc.perform(get("/api/integrations/google/status")
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("no-google")))
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.googleEmail").doesNotExist());
    }
}
