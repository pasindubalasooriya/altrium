package com.altrium.calendar;

import com.altrium.auth.CurrentUser;
import com.altrium.auth.CurrentUserService;
import com.altrium.config.ConflictApiException;
import com.altrium.config.ValidationApiException;
import com.altrium.org.AppUser;
import com.altrium.org.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Connecting and disconnecting a personal Google account (scenario section 12, section 14).
 *
 * <p>Altrium has no Google Workspace, so there is no directory, no domain-wide delegation and
 * no service account that can act for everybody. Every calendar it touches belongs to somebody
 * who personally granted access, and this service is the whole of that grant: it is made here,
 * it is revoked here, and nothing else in the system can widen it.
 *
 * <h2>A person connects only their own account</h2>
 *
 * <p>Every method below works on {@link CurrentUserService#require() the caller}, and none
 * takes a user id from a request. There is no capability governing this and there should not
 * be one: connecting somebody else's Google account is not a permission anybody could hold, it
 * is an action with no meaning. Modelling it as a capability with an empty grounds set would
 * suggest it was a rule that might one day be relaxed.
 *
 * <p>The one exception is {@link #accessTokenFor(Long)}, which reads another person's stored
 * token. It is package-private, and its only caller is {@link MeetingService} after that
 * service has already required a scheduling capability against the subject.
 */
@Service
public class GoogleConnectionService {

    private static final Logger log = LoggerFactory.getLogger(GoogleConnectionService.class);

    private final GoogleConnectionRepository connections;
    private final AppUserRepository users;
    private final CurrentUserService currentUser;
    private final GoogleOAuthClient oauth;
    private final OAuthStateStore states;
    private final TokenCipher cipher;

    public GoogleConnectionService(GoogleConnectionRepository connections,
                                   AppUserRepository users,
                                   CurrentUserService currentUser,
                                   GoogleOAuthClient oauth,
                                   OAuthStateStore states,
                                   TokenCipher cipher) {
        this.connections = connections;
        this.users = users;
        this.currentUser = currentUser;
        this.oauth = oauth;
        this.states = states;
        this.cipher = cipher;
    }

    /**
     * Where to send the caller's browser to consent.
     *
     * <p>Refused when the server holds no OAuth credentials, rather than producing a URL that
     * would fail at Google with an error the person cannot act on. The same condition governs
     * whether a token could be encrypted at rest, so there is no state in which the flow starts
     * but its result cannot be stored safely.
     */
    public String beginConnect() {
        requireConfigured();
        CurrentUser caller = currentUser.require();
        return oauth.authorizationUrl(states.issue(caller.id()));
    }

    /**
     * The return leg, called by the unauthenticated callback endpoint.
     *
     * <p>The state is the credential here, and it is redeemed before anything else happens. A
     * callback with a state this server did not mint, or one already used, or one older than
     * ten minutes, gets no further and attaches nothing to anybody.
     *
     * @return the address of the account that was connected, for the message shown afterwards
     */
    @Transactional
    public String completeConnect(String state, String code) {
        requireConfigured();

        Long userId = states.redeem(state).orElseThrow(() -> new ValidationApiException(
                "That connection attempt is no longer valid. Start again from Altrium."));

        if (code == null || code.isBlank()) {
            throw new ValidationApiException("Google returned no authorization code.");
        }

        GoogleTokens tokens = oauth.exchange(code);

        if (tokens.refreshToken() == null || tokens.refreshToken().isBlank()) {
            // Google withholds the refresh token on a repeat consent unless prompt=consent is
            // sent, which it is. Reaching here means something else went wrong, and storing a
            // connection without one would leave a row that works for an hour and then stops.
            throw new ConflictApiException(
                    "Google did not return a refresh token. Remove Altrium from your Google "
                            + "account permissions and connect again.");
        }

        if (!grantsCalendarWrite(tokens.grantedScopes())) {
            // Google's consent screen lets a person deselect individual permissions, and the
            // flow still completes. Checking here means they are told now, rather than when a
            // booking fails later for a reason that looks like a fault in Altrium.
            throw new ConflictApiException(
                    "Calendar access was not granted. Connect again and leave the calendar "
                            + "permission ticked.");
        }

        AppUser user = users.findById(userId).orElseThrow(() ->
                new ValidationApiException("That connection attempt is no longer valid."));

        byte[] encrypted = cipher.encrypt(tokens.refreshToken());

        connections.findByUserId(userId).ifPresentOrElse(
                existing -> existing.replaceWith(tokens.googleEmail(), encrypted, tokens.grantedScopes()),
                () -> connections.save(new GoogleConnection(
                        user, tokens.googleEmail(), encrypted, tokens.grantedScopes())));

        log.info("Google Calendar connected for user {}", userId);
        return tokens.googleEmail();
    }

    public Status status() {
        CurrentUser caller = currentUser.require();
        return connections.findByUserId(caller.id())
                .map(c -> new Status(true, c.getGoogleEmail(), isConfigured()))
                .orElseGet(() -> new Status(false, null, isConfigured()));
    }

    /**
     * Forgets the stored token.
     *
     * <p>It does not revoke the grant at Google, and the message shown to the person says so.
     * Altrium can stop holding a credential; only the person can withdraw the consent, and
     * claiming otherwise would leave them believing they had.
     */
    @Transactional
    public void disconnect() {
        CurrentUser caller = currentUser.require();
        connections.deleteByUserId(caller.id());
        log.info("Google Calendar disconnected for user {}", caller.id());
    }

    public boolean isConnected(Long userId) {
        return connections.existsByUserId(userId);
    }

    public boolean isConfigured() {
        return cipher.isConfigured();
    }

    /**
     * A usable access token for somebody, or empty if they have not connected.
     *
     * <p><strong>Package-private, and it must stay that way.</strong> Reading another person's
     * calendar is what this returns the means to do, and the only safe caller is one that has
     * already asked {@code AuthorizationService} whether it may schedule with that person. That
     * is {@link MeetingService}, and it makes the check first.
     */
    Optional<String> accessTokenFor(Long userId) {
        return connections.findByUserId(userId)
                .map(connection -> oauth.accessToken(cipher.decrypt(connection.getRefreshTokenCipher())));
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new ConflictApiException("Google Calendar is not configured on this server.");
        }
    }

    private static boolean grantsCalendarWrite(String grantedScopes) {
        return grantedScopes != null
                && grantedScopes.contains("https://www.googleapis.com/auth/calendar");
    }

    /**
     * @param configured whether the server holds OAuth credentials at all. Reported separately
     *                   from whether this person has connected, so the screen can say "not
     *                   available here" rather than offering a button that cannot work
     */
    public record Status(boolean connected, String googleEmail, boolean configured) {
    }
}
