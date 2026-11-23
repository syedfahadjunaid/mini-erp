package com.minierp.shared;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Reads the realm roles Keycloak puts in an access token: the realm's protocol mapper adds
 * them as a top-level {@code roles} claim; Keycloak's standard {@code realm_access.roles} is read
 * as a fallback. Only roles this app knows ({@link Role}) become authorities; Keycloak's
 * built-in ones (offline_access, uma_authorization) are ignored.
 */
public final class RolesClaim {

    private RolesClaim() {}

    public static List<GrantedAuthority> authorities(Map<String, Object> claims) {
        List<String> names = new ArrayList<>(stringList(claims.get("roles")));
        if (claims.get("realm_access") instanceof Map<?, ?> realmAccess) {
            names.addAll(stringList(realmAccess.get("roles")));
        }
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (Role role : Role.values()) {
            if (names.contains(role.name())) {
                authorities.add(new SimpleGrantedAuthority(role.authority()));
            }
        }
        return authorities;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> values)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof String s) {
                strings.add(s);
            }
        }
        return strings;
    }
}
