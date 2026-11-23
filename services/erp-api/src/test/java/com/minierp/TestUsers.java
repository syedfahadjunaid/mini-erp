package com.minierp;

import com.minierp.shared.Actor;
import com.minierp.shared.Role;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The people in the tests, one per job, mirroring the demo users in the Keycloak realm.
 * {@link User#bearer()} adds a real signed access token to a MockMvc request, so tests go
 * through the same token validation as production.
 */
public final class TestUsers {

    private TestUsers() {}

    public record User(String username, Set<Role> roles) {

        public User {
            roles = Set.copyOf(roles);
        }

        /**
         * Signed in through the browser flow (an OIDC login session), for MockMvc requests. The
         * principal's name is {@code preferred_username}, as configured for the real Keycloak client.
         */
        public SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor session() {
            List<GrantedAuthority> authorities = roles.stream()
                    .<GrantedAuthority>map(role -> new SimpleGrantedAuthority(role.authority()))
                    .toList();
            OidcIdToken idToken = OidcIdToken.withTokenValue("test-id-token")
                    .issuer(TestTokens.ISSUER)
                    .subject("sub-" + username)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .claim("preferred_username", username)
                    .claim("name", username + " Example")
                    .build();
            return SecurityMockMvcRequestPostProcessors.oidcLogin()
                    .oidcUser(new DefaultOidcUser(authorities, idToken, "preferred_username"));
        }

        public RequestPostProcessor bearer() {
            String token = TestTokens.issue(this);
            return request -> {
                request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                return request;
            };
        }

        /** The same person for direct service calls (concurrency tests). */
        public Actor actor() {
            return new Actor(username, roles);
        }
    }

    public static final User ADMIN = new User("adam", Set.of(Role.ADMIN));
    public static final User BUYER = new User("pam", Set.of(Role.PURCHASING));
    public static final User OTHER_BUYER = new User("pete", Set.of(Role.PURCHASING));
    public static final User MANAGER = new User("max", Set.of(Role.MANAGER));
    public static final User WAREHOUSE = new User("walt", Set.of(Role.WAREHOUSE));
    public static final User SALES = new User("sue", Set.of(Role.SALES));
    public static final User FINANCE = new User("finn", Set.of(Role.FINANCE));
    /** A buyer who is also a manager: may approve purchase orders, but not their own. */
    public static final User BUYING_MANAGER = new User("bea", Set.of(Role.PURCHASING, Role.MANAGER));
    public static final User NOBODY = new User("nora", Set.of());
}
