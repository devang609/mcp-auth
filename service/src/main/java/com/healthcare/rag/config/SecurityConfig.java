package com.healthcare.rag.config;

import com.healthcare.rag.security.ResourceMetadataEntryPoint;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OAuth 2.1 resource-server configuration.
 *
 * <p>Design notes a reviewer will ask about:
 * <ul>
 *   <li><b>Why a custom {@link JwtDecoder}?</b> Tokens carry the browser-facing issuer
 *       ({@code https://keycloak.local/...}) but this container can't cheaply trust the
 *       mkcert TLS cert internally. So we fetch JWKS over plain HTTP inside the Docker
 *       network ({@code http://keycloak:8080/...}) yet still validate the external issuer
 *       string. Signature trust comes from the JWKS keys, not from TLS.</li>
 *   <li><b>Why the custom entry point?</b> The MCP spec requires a 401 whose
 *       {@code WWW-Authenticate} header points clients at the protected-resource metadata
 *       document, which is how Claude Code discovers Keycloak.</li>
 *   <li><b>Scopes → authorities:</b> the {@code scope} claim becomes {@code SCOPE_*}
 *       authorities; individual tools assert the scope they need (least privilege).</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class SecurityConfig {

    private final AppProperties props;

    public SecurityConfig(AppProperties props) {
        this.props = props;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        ResourceMetadataEntryPoint entryPoint =
                new ResourceMetadataEntryPoint(props.mcp().resourceUrl());

        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public discovery + health. Everything else needs a valid token.
                .requestMatchers(
                        "/.well-known/**",
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationEntryPoint(entryPoint)
                .jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())))
            // Also surface the resource-metadata challenge for anonymous access to protected URLs.
            .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint));

        return http.build();
    }

    /**
     * JWKS fetched internally over HTTP; issuer validated against the external URL that
     * actually appears in the token.
     */
    @Bean
    JwtDecoder jwtDecoder() {
        String jwks = props.keycloak().internalUrl()
                + "/realms/" + props.keycloak().realm()
                + "/protocol/openid-connect/certs";

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();

        OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(props.keycloak().issuer()));
        decoder.setJwtValidator(validators);
        return decoder;
    }

    /** Map the OAuth {@code scope} claim to {@code SCOPE_*} authorities. */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthorityPrefix("SCOPE_");
        scopes.setAuthoritiesClaimName("scope");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(scopes);
        return converter;
    }
}
