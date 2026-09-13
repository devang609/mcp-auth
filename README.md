# Healthcare MCP Demo — Multi-Database RAG with OAuth 2.1 + OPA

A self-contained, Dockerized demonstration of secure, fine-grained **Retrieval-Augmented Generation (RAG) over the Model Context Protocol (MCP)**. A Java MCP server exposes clinical data spread across four datastores (Postgres + pgvector, MongoDB, Neo4j, plus Voyage AI embeddings) to Claude Code Desktop. Every request is authenticated with **OAuth 2.1 + PKCE + Dynamic Client Registration** against Keycloak, and every data access is authorized by **Open Policy Agent (OPA)** with row-level patient filtering and field-level redaction — so the same prompt returns different, role-appropriate results for an attending physician, a nurse, a researcher, a billing clerk, and a patient. It is a healthcare demo, but the pattern (pure resource server + external IdP + external policy engine) is general.

## Architecture

```
                          ┌──────────────────────────┐
                          │   Claude Code Desktop     │
                          │  (MCP client / OAuth app) │
                          └────────────┬─────────────┘
                                       │ HTTPS (mcp.local, keycloak.local)
                                       ▼
                          ┌──────────────────────────┐
                          │        Traefik v3        │   :80 → :443 (TLS, mkcert)
                          │   reverse proxy + TLS    │   dashboard :8080 (internal)
                          └───┬──────────────────┬───┘
              Host(mcp.local) │                  │ Host(keycloak.local)
                              ▼                  ▼
        ┌─────────────────────────────┐   ┌──────────────────────┐
        │   Java MCP Server           │   │      Keycloak 26     │
        │   com.healthcare.rag :8080  │   │  realm: healthcare-  │
        │   (Spring Boot, resource    │   │  demo (OAuth 2.1 AS, │
        │    server, 5 MCP tools)     │   │  PKCE, DCR, consent) │
        └──┬─────────┬────────┬───────┘   └──────────────────────┘
           │         │        │  │  authorize decision (POST /v1/data/...)
           │         │        │  └──────────────► ┌──────────────────┐
           │         │        │                   │   OPA (envoy)    │
           │         │        │                   │  healthcare.authz│  :8181
           │         │        │                   │  decision logs   │
           │         │        │                   └──────────────────┘
           ▼         ▼        ▼
   ┌────────────┐ ┌────────┐ ┌────────┐          ┌────────────────────┐
   │ Postgres   │ │MongoDB │ │ Neo4j  │          │   Voyage AI API    │
   │ +pgvector  │ │clinical│ │ graph  │          │ voyage-3-lite      │
   │ :5432      │ │ :27017 │ │ :7687  │          │ (embeddings, ext.) │
   └────────────┘ └────────┘ └────────┘          └────────────────────┘

   Bridge network: healthnet. Only Traefik publishes host ports (80, 443).
```

## Prerequisites

- **Docker + Docker Compose** (Compose v2, `docker compose`).
- **[mkcert](https://github.com/FiloSottile/mkcert)** — generates a locally-trusted TLS certificate for `mcp.local` / `keycloak.local`.
- **A Voyage AI API key** — required for embeddings (`voyage-3-lite`). Get one at https://www.voyageai.com/.
- **Ability to edit your hosts file** — to point `mcp.local` and `keycloak.local` at `127.0.0.1` (needs admin/root).

## Quickstart (5 steps)

### 1. Create your `.env` and add your Voyage key

```bash
cp .env.example .env
```

Then edit `.env` and set `VOYAGE_API_KEY=<your-voyage-key>`.

### 2. Add the demo hostnames to your hosts file

Add this line so both hostnames resolve to your machine:

```
127.0.0.1 mcp.local keycloak.local
```

- **Linux / macOS:** `/etc/hosts`
- **Windows:** `C:\Windows\System32\drivers\etc\hosts`

(Edit the file with admin/root privileges.)

### 3. Generate the local TLS certificate

Linux / macOS:

```bash
scripts/gen-certs.sh
```

Windows (PowerShell):

```powershell
scripts\gen-certs.ps1
```

This runs `mkcert -install` (trusts the local CA) and writes `traefik/certs/local.pem` and `traefik/certs/local-key.pem`.

### 4. Build and start the stack

```bash
docker compose up --build
```

### 5. Connect Claude Code Desktop and trigger a tool

Add the MCP server `https://mcp.local` (endpoint `https://mcp.local/mcp`) to your Claude Code Desktop MCP config, complete the OAuth login (see [The auth flow](#the-auth-flow)), then invoke a tool such as `search_patients`.

## Demo users

All users live in the `healthcare-demo` realm. **Password for every user is `demo123`** (not temporary).

| Username | Role | Attributes |
|---|---|---|
| `dr_smith` | `attending_physician` | department=cardiology, units=[CCU, ICU-3] |
| `dr_jones` | `resident` | department=cardiology, supervisor=dr_smith |
| `nurse_adams` | `nurse` | units=[CCU] |
| `billing_clerk` | `billing` | department=revenue_cycle |
| `researcher_lee` | `researcher` | protocol=CARDIO-2025 |
| `patient_portal` | `patient` | patient_id=10001 |

## Architecture & tech choices (with rationale)

- **pgvector, not a dedicated vector DB (Pinecone/Weaviate/Milvus).** Vectors live in the same Postgres that holds the relational patient data, so a similarity hit and its structured record join in one place with no extra service or sync. Good enough at demo scale; one fewer moving part.
- **OPA, not OpenFGA.** We need *attribute*-based rules (role + unit + supervision + protocol + list-size limits) and per-decision redaction output, expressed as policy-as-code (Rego) with a built-in decision log for audit. OpenFGA excels at relationship tuples but is a poorer fit for rich attribute logic and structured decision objects.
- **Keycloak, not custom auth.** A standards-complete OAuth 2.1 / OIDC provider gives us PKCE, Dynamic Client Registration (RFC 7591), consent screens, and JWKS for free. Rolling our own auth would be the exact anti-pattern this demo argues against.
- **MCP Java SDK, not hand-rolled JSON-RPC.** The official SDK (`io.modelcontextprotocol.sdk`) handles the protocol, streamable-HTTP transport, and tool wiring correctly; hand-writing JSON-RPC invites subtle spec drift.
- **Neo4j, not Apache AGE or Postgres SQL/PGQ.** Care-team traversals (TREATS, CONSULTS_ON, SIMILAR_CASE, supervision) are naturally graph-shaped; a native graph DB keeps those queries clear and fast versus bolting graph semantics onto SQL.
- **MT Samples, not Synthea or MIMIC.** Real-sounding transcribed clinical notes give meaningful semantic-search results with no data-use agreement or PHI handling burden — unlike MIMIC (credentialed access) or Synthea (synthetic but shallow narrative text).
- **Voyage AI, not local Ollama embeddings.** `voyage-3-lite` gives high-quality 1024-dim clinical embeddings via a simple API, keeping the demo lightweight and reproducible instead of shipping a local embedding model and its hardware requirements.

## The auth flow

The server is a **pure OAuth 2.1 resource server** — it never sees a password. Credentials only ever touch Keycloak.

1. **Discovery.** Claude Code calls the MCP endpoint `https://mcp.local/mcp` with no token and gets **401** with `WWW-Authenticate: Bearer resource_metadata="https://mcp.local/.well-known/oauth-protected-resource"`. It fetches that metadata (RFC 9728), learning the resource identifier and the authorization server (`https://keycloak.local/realms/healthcare-demo`).
2. **Dynamic Client Registration (RFC 7591).** Claude Code registers itself as an OAuth client at Keycloak's registration endpoint. The registration policy forces **Consent Required = ON** for the new client.
3. **Authorization request with PKCE.** Claude Code opens the browser to Keycloak's authorization endpoint with a PKCE `code_challenge` and the requested scopes (`patients:read`, `notes:read`, `careteam:read`, `audit:read`).
4. **Login — credentials touch Keycloak only.** The user authenticates on Keycloak's login page (e.g. `dr_smith` / `demo123`). The MCP server never receives these credentials.
5. **Consent moment 1 (OAuth scope consent).** Keycloak shows a consent screen listing the requested scopes in human-readable form ("Access patient records on your behalf", etc.). The user approves.
6. **Code → token exchange with PKCE verifier.** Keycloak redirects back with an authorization code; Claude Code exchanges it (sending the PKCE `code_verifier`) for a JWT access token whose `iss` is `https://keycloak.local/realms/healthcare-demo` and which carries custom claims (`role`, `department`, `units`, `supervisor`, `protocol`, `patient_id`).
7. **Authenticated MCP calls.** Claude Code calls `https://mcp.local/mcp` with `Authorization: Bearer <jwt>`. The server validates the token (signature via internal JWKS, timestamp, and external issuer string) — it is a resource server, nothing more.
8. **Consent moment 2 (per-tool invocation consent).** Before each tool runs, Claude Code Desktop asks the user to approve the specific tool call ("Claude wants to use `search_patients` — Deny / Allow once / Allow always").
9. **Fine-grained authorization + audit.** For each tool call the server builds an OPA input from the token claims and POSTs it to `http://opa:8181/v1/data/healthcare/authz/decision`. OPA returns `allow`, a `patient_id_filter`, `redact_fields`, and a `reason`. The server enforces the filter and redaction against the databases and returns only role-appropriate data. Every decision is written to **OPA's console decision log** (the canonical audit trail) and to the server's in-memory ring buffer surfaced by `audit_my_access`.

**Two consent layers + audit:** (1) OAuth scope consent in Keycloak, (2) MCP tool-invocation consent in Claude Code Desktop, plus (3) the OPA decision-log audit trail recording every allow/deny.

## MCP tools

| Tool | Scope | Resource type | Input / notes |
|---|---|---|---|
| `search_patients` | `patients:read` | patient | `{query:string, limit?:int}` — vector search + structured data |
| `get_patient_summary` | `patients:read`, `notes:read` | patient | `{patient_id:int}` — joins Postgres + Mongo + Neo4j |
| `find_similar_cases` | `notes:read` | note | `{query:string, limit?:int}` — pgvector + Neo4j SIMILAR_CASE |
| `query_care_team` | `careteam:read` | care_team | `{patient_id:int}` — Neo4j traversal |
| `audit_my_access` | `audit:read` | audit | `{}` — recent OPA decisions for the calling user |

## Troubleshooting

- **Certificate not trusted (Claude rejects the self-signed cert).** Run the cert script so `mkcert -install` adds the local CA to your trust store, then **fully restart Claude Code Desktop** so it picks up the new trust store. Confirm `traefik/certs/local.pem` and `local-key.pem` exist.
- **Hosts entries missing.** If `mcp.local` / `keycloak.local` do not resolve, re-check step 2. Both must map to `127.0.0.1`. On Windows the file is `C:\Windows\System32\drivers\etc\hosts` (edit as Administrator).
- **Voyage key missing/invalid.** Embedding calls fail if `VOYAGE_API_KEY` is unset in `.env`. Confirm the value and restart: `docker compose up -d --build mcp-server`.
- **Inspect logs.**

```bash
docker compose logs -f mcp-server
```

```bash
docker compose logs -f opa
```

```bash
docker compose logs -f keycloak
```

- **Port 443 already in use.** Another process (a local web server, another proxy) may hold 443. Stop it, or free the port, then `docker compose up` again. On Windows, check with `netstat -ano | findstr :443`.
- **Keycloak issuer mismatch.** Tokens carry `iss=https://keycloak.local/realms/healthcare-demo` (external), while the service fetches JWKS internally over `http://keycloak:8080`. If you see issuer-validation errors, verify `KC_HOSTNAME=https://keycloak.local`, `KC_PROXY_HEADERS=xforwarded`, and that Traefik is forwarding `X-Forwarded-*` headers (it does by default) — otherwise Keycloak may emit the wrong issuer.

## Observability

- **Health:** `GET https://mcp.local/actuator/health` (public) reports service health.
- **OPA decision logs** (the canonical authorization audit trail):

```bash
docker compose logs opa
```

- **Structured JSON logs** from the MCP server show token validation, OPA decisions, and DB queries. Per-user audit is also available in-app via the `audit_my_access` tool.
