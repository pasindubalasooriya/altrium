package com.altrium.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Stands in for Asgardeo's JWKS validation so tests never touch the network.
 *
 * <p>Only signature verification is replaced. Everything downstream — the resource-server
 * filter chain, {@code AltriumJwtAuthenticationConverter}, the authority checks — is the
 * real production path. A denial test that stubbed the converter would prove nothing.
 *
 * <p>Token format is {@code subject} or {@code subject|ROLE_A,ROLE_B}, where the roles become
 * the Asgardeo {@code roles} claim. That second form exists to prove the claim grants
 * nothing: the database governs authorities, so a token asserting SUPER_ADMIN gets whatever
 * the database says and no more.
 */
@TestConfiguration
public class StubJwtDecoderConfig {

    /**
     * Both delimiters must be legal bearer-token characters, or Spring's
     * {@code BearerTokenAuthenticationFilter} rejects the request as malformed before the
     * decoder is ever reached. RFC 6750 permits {@code A-Za-z0-9-._~+/} only.
     */
    public static final String ROLE_DELIMITER = "~";
    public static final String ROLE_SEPARATOR = "+";

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            String subject = token;
            List<String> roles = List.of();

            int split = token.indexOf(ROLE_DELIMITER);
            if (split >= 0) {
                subject = token.substring(0, split);
                String tail = token.substring(split + 1);
                if (!tail.isBlank()) {
                    roles = Arrays.asList(tail.split(Pattern.quote(ROLE_SEPARATOR)));
                }
            }

            return Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .subject(subject)
                    .claim("roles", roles)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                    .build();
        };
    }
}
