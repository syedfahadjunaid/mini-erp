package com.minierp.shared;

import java.util.Comparator;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Who am I?" The web app calls this first: 200 means there is a session (and tells it which
 * menus to show), 401 means it should start a sign-in.
 *
 * <p>The roles only shape the UI. Every endpoint still checks them on the server.
 */
@RestController
class MeController {

    /** @param displayName e.g. "Walt Warehouse"; the username when the token has no name */
    record MeResponse(String username, String displayName, List<Role> roles) {}

    @GetMapping("/api/me")
    MeResponse me(Actor actor, Authentication authentication) {
        String name = null;
        if (authentication.getPrincipal() instanceof OidcUser user) {
            name = user.getFullName();                        // browser session: from the ID token
        } else if (authentication instanceof JwtAuthenticationToken jwt) {
            name = jwt.getToken().getClaimAsString("name");   // bearer token
        }
        List<Role> roles = actor.roles().stream().sorted(Comparator.naturalOrder()).toList();
        return new MeResponse(actor.username(), name == null || name.isBlank() ? actor.username() : name, roles);
    }
}
