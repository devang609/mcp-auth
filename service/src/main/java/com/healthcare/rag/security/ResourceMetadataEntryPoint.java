package com.healthcare.rag.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Returns the MCP-compliant 401 challenge. Per the MCP authorization spec (and RFC 9728),
 * an unauthenticated request must receive:
 *
 * <pre>
 *   WWW-Authenticate: Bearer resource_metadata="https://mcp.local/.well-known/oauth-protected-resource"
 * </pre>
 *
 * which is how Claude Code discovers that Keycloak is the authorization server and begins
 * the OAuth 2.1 + PKCE + Dynamic Client Registration flow.
 */
public class ResourceMetadataEntryPoint implements AuthenticationEntryPoint {

    private final String challenge;

    public ResourceMetadataEntryPoint(String resourceUrl) {
        this.challenge = "Bearer resource_metadata=\""
                + resourceUrl + "/.well-known/oauth-protected-resource\"";
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"unauthorized\",\"error_description\":\"authentication required\"}");
    }
}
