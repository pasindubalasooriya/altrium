package com.altrium.config;

import com.altrium.auth.AltriumJwtAuthenticationConverter;
import com.altrium.org.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Resource-server configuration.
 *
 * <p>Asgardeo is the identity provider (decided; do not substitute). The backend never sees a
 * password and holds no shared secret - it validates each JWT's signature against the
 * tenant's JWKS endpoint, which Spring discovers from {@code issuer-uri}.
 *
 * <p>What is configured here is only the coarse gate. The real model - direct reports,
 * department scoping, peer anonymity, the HR self-exclusion rules - cannot be expressed as
 * URL patterns, because whether a manager may open a review depends on who that employee
 * reports to, not on the caller holding the Manager role. That lives in the authorization
 * component and in the queries themselves.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final AltriumJwtAuthenticationConverter jwtAuthenticationConverter;
    private final List<String> allowedOrigins;

    public SecurityConfig(AltriumJwtAuthenticationConverter jwtAuthenticationConverter,
                          @Value("${altrium.cors.allowed-origins:http://localhost:5173}")
                          List<String> allowedOrigins) {
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // No sessions, so no CSRF surface: every request carries its own bearer token.
                .csrf(csrf -> csrf.disable())

                // The frontend is served from a different origin in development, so without
                // this the browser refuses every call before the filter chain is reached.
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()

                        // The single authenticated-endpoint exception in the API, and it is
                        // forced rather than chosen. Google redirects the browser here after
                        // consent: it is a top-level navigation from another origin, so no
                        // Authorization header can be attached and there is no session to fall
                        // back on, Altrium being stateless by design.
                        //
                        // What identifies the caller instead is the `state` query parameter,
                        // which OAuthStateStore minted against their user id when they clicked
                        // Connect, and which is unguessable, single-use and ten minutes old at
                        // most. A request without a state this server issued attaches nothing
                        // to anybody, which is also the CSRF defence the OAuth specification
                        // asks for.
                        //
                        // Note how narrow this is: one path, GET only, and it reads no review,
                        // rating or plan content. It stores a token for one person and
                        // redirects a browser.
                        .requestMatchers(HttpMethod.GET,
                                "/api/integrations/google/callback").permitAll()

                        // Every authenticated endpoint requires a provisioned, active user.
                        // An unprovisioned or deactivated caller carries no authorities, so
                        // this denies them with 403 (P-0.7) without revealing which it was.
                        .anyRequest().hasAuthority(Role.EMPLOYEE.authority()))

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))

                .exceptionHandling(ex -> ex
                        // Missing or invalid credentials is 401: the caller has not
                        // identified themselves yet, so telling them to authenticate leaks
                        // nothing. Every decision *after* identification is 403 (P-0.5).
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(forbiddenHandler()));

        return http.build();
    }

    /**
     * Which origins the browser may let call this API.
     *
     * <p>Driven by {@code altrium.cors.allowed-origins} rather than hardcoded, because the Vite
     * dev server, a teammate's machine and anything deployed later are all different origins and
     * none of them belong in the source.
     *
     * <p><strong>Credentials stay off.</strong> Altrium is stateless and every request carries a
     * bearer token, so there is no cookie for the browser to attach. Turning credentials on would
     * open a cookie surface the design deliberately does not have, and it is also what forces the
     * wildcard origin to be abandoned - so leaving it off keeps both problems away at once.
     *
     * <p>This is a browser convenience, not a security control. CORS decides which pages may read
     * a response; it decides nothing about who may have the data. That is still the JWT and the
     * authorization component, and a caller with curl is unaffected by anything configured here.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    /**
     * Denials that never reach a controller - rejected in the filter chain - still have to
     * look identical to the ones that do, or the difference itself becomes a signal.
     */
    private AccessDeniedHandler forbiddenHandler() {
        return (request, response, ex) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"about:blank","title":"Access denied","status":403,"detail":"Access denied"}""");
        };
    }
}
