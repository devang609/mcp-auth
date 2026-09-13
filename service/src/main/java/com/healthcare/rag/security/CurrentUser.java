package com.healthcare.rag.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

/**
 * Reads the authenticated {@link UserContext} from the Spring Security context.
 *
 * <p>MCP tool handlers run on the servlet request thread (Streamable HTTP is a synchronous
 * {@code POST /mcp}), so the thread-local {@link SecurityContextHolder} is populated by the
 * resource-server filter chain before the tool executes.
 */
public final class CurrentUser {

    private CurrentUser() {}

    public static UserContext require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            throw new IllegalStateException("No authenticated JWT in security context");
        }
        Jwt jwt = jwtAuth.getToken();

        List<String> scopes = jwtAuth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("SCOPE_"))
                .map(a -> a.substring("SCOPE_".length()))
                .toList();

        return new UserContext(
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("role"),
                jwt.getClaimAsString("department"),
                stringList(jwt, "units"),
                jwt.getClaimAsString("supervisor"),
                jwt.getClaimAsString("protocol"),
                jwt.getClaimAsString("patient_id"),
                scopes);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Jwt jwt, String claim) {
        Object v = jwt.getClaims().get(claim);
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return List.of();
    }
}
