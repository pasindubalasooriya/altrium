package com.altrium.calendar;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code state} parameter, and what it stands for.
 *
 * <p><strong>This is the authorization for the callback.</strong> Google redirects the
 * browser to {@code /api/integrations/google/callback} with a query string and no bearer
 * token, so the endpoint has to be reachable unauthenticated and the usual model has nothing
 * to work with there. What identifies the person is this: a random value minted when they
 * clicked Connect, recorded against their user id, and redeemed exactly once.
 *
 * <p>Three properties do the work, and all three matter:
 *
 * <ul>
 *   <li><b>Unguessable.</b> 256 bits from {@link SecureRandom}. An attacker who could forge a
 *       state could attach their own Google account to somebody else's Altrium user.</li>
 *   <li><b>Single use.</b> Redeeming removes it, so a callback URL captured from a browser
 *       history or a referrer header is already spent.</li>
 *   <li><b>Short lived.</b> Ten minutes, which is longer than a consent screen takes and
 *       shorter than a walk away from an unlocked laptop.</li>
 * </ul>
 *
 * <p>It is also the CSRF defence the OAuth specification asks for, since a callback the person
 * did not initiate carries no state this store has ever heard of.
 *
 * <h2>In memory, and the consequence stated</h2>
 *
 * <p>A restart, or a second instance behind a load balancer, loses pending states and the
 * person sees "that connection attempt expired" and clicks Connect again. That is the whole
 * cost, and it is paid by somebody who is at their keyboard at that moment. The alternative is
 * a table holding rows that are worthless ten minutes later, plus a job to sweep it. If
 * Altrium is ever run as more than one instance this is the thing to move, and this paragraph
 * is the note saying so.
 */
@Component
public class OAuthStateStore {

    private static final Duration LIFETIME = Duration.ofMinutes(10);

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /** Mints a state for this person and remembers it. */
    public String issue(Long userId) {
        purgeExpired();

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        pending.put(state, new Pending(userId, Instant.now().plus(LIFETIME)));
        return state;
    }

    /** Consumes a state, returning whose it was. Empty for unknown, spent or expired. */
    public Optional<Long> redeem(String state) {
        purgeExpired();
        if (state == null) {
            return Optional.empty();
        }
        Pending found = pending.remove(state);
        if (found == null || found.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(found.userId());
    }

    /**
     * Swept on use rather than on a timer.
     *
     * <p>The map is bounded by how many people are mid-connect, which is a handful, so a
     * scheduled job would be machinery for nothing.
     */
    private void purgeExpired() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt().isBefore(now)) {
                it.remove();
            }
        }
    }

    private record Pending(Long userId, Instant expiresAt) {
    }
}
