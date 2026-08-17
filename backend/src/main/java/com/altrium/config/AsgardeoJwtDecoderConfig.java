package com.altrium.config;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the decoder that validates Asgardeo access tokens.
 *
 * <p>Spring's out-of-the-box decoder rejects them. Asgardeo stamps its access tokens with the
 * JOSE header {@code typ: at+jwt} - the RFC 9068 media type for a JWT access token - while
 * Nimbus's default type verifier accepts only {@code JWT} or no type at all. The result is
 * every request failing with "JOSE header typ (type) at+jwt not allowed", which surfaces as a
 * flat 401 and looks indistinguishable from a bad token.
 *
 * <p>Widening the accepted types is the whole fix. Nothing about verification is relaxed:
 * the signature is still checked against the tenant's JWKS, and the issuer and expiry
 * validators that {@code withIssuerLocation} installs are left in place.
 *
 * <p>Guarded by a flag of our own rather than by the presence of {@code issuer-uri}. A
 * profile-specific file layers over the base configuration instead of replacing it, so the
 * issuer default in {@code application.yml} is visible even under the test profile - the
 * condition would always match, and this bean would collide with the test's stub decoder.
 * Tests set {@code altrium.security.asgardeo-decoder=false}.
 *
 * <p>The guard also matters because {@code withIssuerLocation} fetches the tenant's OpenID
 * metadata while building. Constructing it under test would mean a network call on every
 * context load.
 */
@Configuration
@ConditionalOnProperty(name = "altrium.security.asgardeo-decoder", havingValue = "true", matchIfMissing = true)
public class AsgardeoJwtDecoderConfig {

    /** RFC 9068. What Asgardeo actually stamps on an access token. */
    private static final JOSEObjectType AT_JWT = new JOSEObjectType("at+jwt");

    private final String issuerUri;

    public AsgardeoJwtDecoderConfig(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        this.issuerUri = issuerUri;
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withIssuerLocation(issuerUri)
                .jwtProcessorCustomizer(processor -> processor.setJWSTypeVerifier(
                        // `null` permits a token with no typ header at all, which stays
                        // compatible with issuers that omit it.
                        new DefaultJOSEObjectTypeVerifier<SecurityContext>(
                                AT_JWT, JOSEObjectType.JWT, null)))
                .build();
    }
}
