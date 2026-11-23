package com.minierp.shared;

import java.util.EnumSet;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Who is making the request: the username and roles from their verified access token.
 *
 * <p>Controller methods declare an {@code Actor} parameter and get it from the security
 * context ({@link ActorArgumentResolver}), never from the request body, so nobody can act
 * in someone else's name. The username is what audit columns ({@code created_by},
 * {@code recorded_by}, ...) store.
 *
 * @param username Keycloak's {@code preferred_username}, e.g. "pam"
 */
public record Actor(String username, Set<Role> roles) {

    /** Background work triggered by an event (e.g. automatic invoicing) is done in this name. */
    public static final Actor SYSTEM = new Actor("system", Set.of());

    public Actor {
        roles = Set.copyOf(roles); // immutable copy: a record's state shouldn't change under it
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }

    public static Actor from(Authentication authentication) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            for (Role role : Role.values()) {
                if (role.authority().equals(authority.getAuthority())) {
                    roles.add(role);
                }
            }
        }
        return new Actor(authentication.getName(), roles);
    }
}
