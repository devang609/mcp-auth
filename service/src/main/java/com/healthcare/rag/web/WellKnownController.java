package com.healthcare.rag.web;

import com.healthcare.rag.config.AppProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * RFC 9728 OAuth 2.0 Protected Resource Metadata.
 *
 * <p>Claude Code fetches this (unauthenticated) after receiving the 401 challenge, learns
 * that the authorization server is Keycloak, and proceeds with discovery + DCR + PKCE.
 */
@RestController
public class WellKnownController {

    private final AppProperties props;

    public WellKnownController(AppProperties props) {
        this.props = props;
    }

    @GetMapping("/.well-known/oauth-protected-resource")
    public Map<String, Object> protectedResourceMetadata() {
        return Map.of(
                "resource", props.mcp().resourceUrl(),
                "authorization_servers", List.of(props.keycloak().issuer()),
                "scopes_supported", List.of(
                        "patients:read", "notes:read", "careteam:read", "audit:read"),
                "bearer_methods_supported", List.of("header"));
    }
}
