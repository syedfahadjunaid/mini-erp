package com.minierp.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/**
 * All HTTP security rules, in one place. Two ways in:
 *
 * <ol>
 *   <li><b>Scripts and integrations</b> ({@code demo.ps1}, Swagger UI): a Keycloak access token in
 *       {@code Authorization: Bearer ...}, verified on every request. Stateless, no cookies, so no
 *       CSRF.</li>
 *   <li><b>Browsers</b> (the React web app): the API is the app's backend-for-frontend. It signs the
 *       user in with Keycloak (authorization code + PKCE, as the confidential client {@code erp-web})
 *       and keeps the tokens in a server-side session (Spring Session, stored in Postgres). The
 *       browser only gets the HttpOnly cookie {@code ERP_SESSION}; no token ever reaches
 *       JavaScript, so an XSS bug can't steal one. Because browsers send cookies automatically,
 *       every state-changing request also needs a CSRF token.</li>
 * </ol>
 *
 * <p>Status codes: not signed in / invalid token = 401 (never a redirect for {@code /api/**}:
 * {@code fetch()} would follow it invisibly); signed in without the role a method needs = 403
 * ({@code @PreAuthorize}, enabled by {@link EnableMethodSecurity}).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    /** Readable by the SPA. Not "XSRF-TOKEN": cookies on localhost are shared by every port. */
    public static final String CSRF_COOKIE = "ERP-XSRF-TOKEN";

    /** Public: they describe the API (no data), or are only reachable on the unpublished management port. */
    private static final String[] PUBLIC = {
        "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
        "/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/error"};

    /** Requests that carry a bearer token. */
    private static final RequestMatcher BEARER_TOKEN_REQUEST = SecurityConfig::isBearerTokenRequest;

    @Bean
    @Order(1)
    SecurityFilterChain bearerTokenSecurity(HttpSecurity http, JwtAuthenticationConverter tokenRoles) throws Exception {
        http
                .securityMatcher(BEARER_TOKEN_REQUEST)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(tokenRoles)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable); // no cookies are involved, so CSRF can't happen
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain browserSecurity(HttpSecurity http, ClientRegistrationRepository registrations, ErpProperties props,
            JsonMapper json) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // Nothing else is reachable (the SPA itself is served by nginx).
                        .anyRequest().denyAll())
                .oauth2Login(login -> login
                        // The SPA starts sign-in by navigating here; no generated login page.
                        .loginPage("/oauth2/authorization/" + KeycloakClientConfig.REGISTRATION_ID)
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/?signin=failed")
                        .userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(rolesFromIdToken())))
                // An API call without a session gets 401, not a redirect to Keycloak; the SPA then
                // starts a sign-in itself (a full-page navigation).
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .csrf(csrf -> csrf
                        .spa() // token in a cookie the SPA reads and sends back in the X-XSRF-TOKEN header
                        .csrfTokenRepository(csrfCookieRepository()))
                .logout(logout -> logout
                        .logoutUrl("/logout") // POST only, with the CSRF header
                        .logoutSuccessHandler(keycloakLogout(registrations, props, json)));
        return http.build();
    }

    private static boolean isBearerTokenRequest(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    /** Browser sign-in: roles come from the ID token's {@code roles} claim (the realm's protocol mapper). */
    private static GrantedAuthoritiesMapper rolesFromIdToken() {
        return authorities -> {
            Set<GrantedAuthority> mapped = new HashSet<>();
            for (GrantedAuthority authority : authorities) {
                if (authority instanceof OidcUserAuthority oidc) {
                    mapped.addAll(RolesClaim.authorities(oidc.getIdToken().getClaims()));
                }
            }
            return mapped;
        };
    }

    private static CookieCsrfTokenRepository csrfCookieRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(CSRF_COOKIE);
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Strict"));
        return repository;
    }

    /**
     * Signing out ends the app session and then the Keycloak session (OpenID Connect
     * RP-initiated logout); otherwise the next sign-in would succeed without a password.
     *
     * <p>The SPA calls {@code /logout} with fetch() (it must, to send the CSRF header), and fetch()
     * can't hand a cross-site redirect to the browser. So the Keycloak logout URL is returned as
     * JSON, and the SPA navigates there.
     */
    private static LogoutSuccessHandler keycloakLogout(ClientRegistrationRepository registrations, ErpProperties props,
            JsonMapper json) {
        var handler = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        handler.setPostLogoutRedirectUri(props.publicUrl() + "/");
        handler.setRedirectStrategy((request, response, url) -> {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), Map.of("logoutUrl", url));
        });
        return handler;
    }

    /**
     * Verifies bearer tokens: signature (keys fetched from Keycloak over the internal network),
     * expiry, issuer, and that the token was issued <em>for this API</em> (the {@code aud}
     * claim). Without the audience check, a token Keycloak issued for some other application in
     * the realm would work here.
     */
    @Bean
    JwtDecoder jwtDecoder(ErpProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(props.keycloak().backchannelUri() + "/protocol/openid-connect/certs")
                .build();
        decoder.setJwtValidator(tokenValidator(props.keycloak().issuerUri(), props.keycloak().audience()));
        return decoder;
    }

    public static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator);
    }

    /** Bearer tokens: roles from the token's {@code roles} claim; the authenticated name is the username. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> RolesClaim.authorities(jwt.getClaims()));
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }
}
