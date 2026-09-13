package com.healthcare.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed view of the {@code app.*} configuration block. Keeping these explicit
 * (rather than scattering {@code @Value} lookups) makes the external dependencies of the
 * service obvious at a glance.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Opa opa,
        Keycloak keycloak,
        Mcp mcp,
        Voyage voyage) {

    public record Opa(String url, String decisionPath, String dataPath) {}

    public record Keycloak(String internalUrl, String realm, String issuer) {}

    public record Mcp(String resourceUrl) {}

    public record Voyage(String apiKey, String model, String url, int dim) {}
}
