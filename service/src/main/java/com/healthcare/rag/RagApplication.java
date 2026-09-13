package com.healthcare.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the healthcare multi-database RAG server exposed over MCP.
 *
 * <p>This process is a pure OAuth 2.1 <em>resource server</em>: it validates JWTs minted
 * by Keycloak, asks OPA for an access decision on every tool call, then queries Postgres
 * (relational + pgvector), MongoDB and Neo4j under the returned filter/redaction.
 */
@SpringBootApplication
public class RagApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
