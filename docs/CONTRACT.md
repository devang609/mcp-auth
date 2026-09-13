# Internal Build Contract — SINGLE SOURCE OF TRUTH

Every component (Java service, Keycloak realm, OPA bundle, seed scripts, Traefik,
docker-compose) MUST agree with the names, ports, env vars, and schemas below.
Do not invent alternatives. If something is underspecified, follow this file
literally and keep it consistent.

Project root: `C:\mcp-auth`  (referred to below with forward slashes).

---

## 1. Docker network & services

- Bridge network name: `healthnet`
- Only Traefik publishes host ports (80, 443). Everything else is internal-only.

| Service (compose key = hostname) | Image | Internal port(s) |
|---|---|---|
| `traefik`    | `traefik:v3` | 80, 443 (published), 8080 dashboard (internal) |
| `mcp-server` | local `./service/Dockerfile` | 8080 |
| `keycloak`   | `quay.io/keycloak/keycloak:26` | 8080 |
| `opa`        | `openpolicyagent/opa:latest-envoy` | 8181 |
| `postgres`   | `pgvector/pgvector:pg16` | 5432 |
| `mongodb`    | `mongo:7` | 27017 |
| `neo4j`      | `neo4j:5-community` | 7687 (bolt), 7474 (http) |
| `seed`       | local `./seed/Dockerfile` (Python one-shot) | — |

Internal DNS names = the compose service keys above (e.g. `http://opa:8181`,
`jdbc:postgresql://postgres:5432/...`, `bolt://neo4j:7687`, `mongodb://mongodb:27017`).

## 2. Public URLs / exposure (env-driven; Traefik is now optional)

Default topology exposes two container ports directly (no Traefik):
- `mcp-server` → host `8080`  (public URL = `PUBLIC_MCP_URL`, default `http://localhost:8080`)
- `keycloak`  → host `8081`  (public URL = `PUBLIC_KEYCLOAK_URL`, default `http://localhost:8081`)

`KEYCLOAK_ISSUER` is derived as `${PUBLIC_KEYCLOAK_URL}/realms/healthcare-demo`;
`MCP_RESOURCE_URL` = `PUBLIC_MCP_URL`; `KC_HOSTNAME` = `PUBLIC_KEYCLOAK_URL`. The service
still fetches JWKS internally at `KEYCLOAK_INTERNAL_URL` (`http://keycloak:8080`).

Deployment modes (set the two PUBLIC_* vars accordingly):
- **Local:** blank → localhost:8080 / :8081 (plain HTTP; fine for loopback clients).
- **Codespaces:** forward 8080 + 8081 (Public), export
  `PUBLIC_MCP_URL=https://$CODESPACE_NAME-8080.app.github.dev` and
  `PUBLIC_KEYCLOAK_URL=https://$CODESPACE_NAME-8081.app.github.dev`. github.dev terminates TLS.
- **Local HTTPS (optional):** `docker compose --profile edge up` starts Traefik with the
  mkcert cert for `https://mcp.local` + `https://keycloak.local`; set PUBLIC_* to those and do
  the `/etc/hosts` + mkcert steps. Traefik routes `mcp.local`→`mcp-server:8080`,
  `keycloak.local`→`keycloak:8080`.

## 3. Credentials / DB coordinates (demo values, all in .env / .env.example)

| Var | Value |
|---|---|
| `POSTGRES_DB` | `healthcare` |
| `POSTGRES_USER` | `rag` |
| `POSTGRES_PASSWORD` | `ragpass` |
| `MONGO_INITDB_DATABASE` | `clinical` |
| `MONGO_USER` | `rag` |
| `MONGO_PASSWORD` | `ragpass` |
| `NEO4J_USER` | `neo4j` |
| `NEO4J_PASSWORD` | `neo4jpass` (compose passes `NEO4J_AUTH=neo4j/neo4jpass`) |
| `KEYCLOAK_ADMIN` | `admin` |
| `KEYCLOAK_ADMIN_PASSWORD` | `admin` |
| `VOYAGE_API_KEY` | (user-supplied, required) |
| `VOYAGE_MODEL` | `voyage-3-lite` |
| `EMBEDDING_DIM` | `1024` |

Mongo connection string used by seed + service:
`mongodb://rag:ragpass@mongodb:27017/clinical?authSource=admin`
(root user created via `MONGO_INITDB_ROOT_USERNAME/PASSWORD` = `rag`/`ragpass`).

## 4. Keycloak

- Realm: `healthcare-demo`
- External issuer (in JWT `iss`): `https://keycloak.local/realms/healthcare-demo`
- Internal base for JWKS (service uses this, avoids TLS trust): `http://keycloak:8080`
- JWKS URL (service fetches): `http://keycloak:8080/realms/healthcare-demo/protocol/openid-connect/certs`
- Keycloak env: `KC_HOSTNAME=https://keycloak.local`, `KC_HTTP_ENABLED=true`,
  `KC_PROXY_HEADERS=xforwarded`, `KC_HEALTH_ENABLED=true`, realm import at
  `/opt/keycloak/data/import/realm-healthcare-demo.json`, start with
  `start-dev --import-realm`.
- Dynamic Client Registration (RFC 7591): anonymous registration allowed;
  client-registration policies must permit it and force **Consent Required = ON**
  for newly registered clients. Registration endpoint:
  `/realms/healthcare-demo/clients-registrations/openid-connect`.
- Client scopes (each: protocol=openid-connect, display on consent screen = true,
  consent screen text set, include in token scope = true):
  - `patients:read`  → consent text "Access patient records on your behalf"
  - `notes:read`     → consent text "Access clinical notes on your behalf"
  - `careteam:read`  → consent text "Query care team relationships"
  - `audit:read`     → consent text "View your access history"
  These are **Optional** client scopes on any client (so scope is granular/least-privilege).

### 4.1 JWT custom claims (added via protocol mappers on the scopes or a dedicated
"healthcare-claims" default client scope). Token MUST contain:

| Claim | Type | Meaning |
|---|---|---|
| `preferred_username` | string | user id, e.g. `dr_smith` (standard) |
| `role` | string | one of: `attending_physician`, `resident`, `nurse`, `billing`, `researcher`, `patient` |
| `department` | string | e.g. `cardiology`, `revenue_cycle` (may be absent) |
| `units` | string[] | e.g. `["CCU","ICU-3"]` (multivalued; may be absent) |
| `supervisor` | string | e.g. `dr_smith` (residents only; else absent) |
| `protocol` | string | e.g. `CARDIO-2025` (researchers only; else absent) |
| `patient_id` | string | e.g. `10001` (patient portal only; else absent) |

Put these in a **default** client scope `healthcare-claims` (added to all users' tokens
regardless of requested OAuth scopes) sourced from user attributes. `units` mapper is
multivalued. All mappers: add to access token = true.

### 4.2 Six seeded users (realm `users`), all password `demo123` (not temporary):

| username | role | attributes |
|---|---|---|
| `dr_smith` | `attending_physician` | department=cardiology, units=[CCU,ICU-3] |
| `dr_jones` | `resident` | department=cardiology, supervisor=dr_smith |
| `nurse_adams` | `nurse` | units=[CCU] |
| `billing_clerk` | `billing` | department=revenue_cycle |
| `researcher_lee` | `researcher` | protocol=CARDIO-2025 |
| `patient_portal` | `patient` | patient_id=10001 |

Each user is granted all four optional scopes (so they may be consented). Attributes are
stored as Keycloak user attributes with the exact claim names above.

## 5. OPA

- Base URL (service → OPA): `http://opa:8181`
- Rego package: `healthcare.authz`
- Decision entrypoint (service POSTs here):
  `POST http://opa:8181/v1/data/healthcare/authz/decision`
  body: `{ "input": { ...see below... } }`
- OPA started with decision logs to console:
  `run --server --addr :8181 --log-level info --set decision_logs.console=true /policies /data`
  (policies mounted at `/policies`, data at `/data`).
- Care-team data is ALSO pushed at service startup via
  `PUT http://opa:8181/v1/data/care_team` (array of edges). A static fallback copy
  lives in the bundle at `data/care_team.json` under document path `care_team` too.

### 5.1 OPA input schema

```json
{
  "user": { "id":"dr_smith", "role":"attending_physician",
            "department":"cardiology", "units":["CCU","ICU-3"],
            "supervisor":null, "protocol":null, "patient_id":null },
  "action": "read",
  "resource_type": "patient",         // patient | note | care_team | audit
  "resource_id": null,                 // optional string/int
  "context": { "query_type":"list",    // list | get | search | similar
               "timestamp":"...",
               "requested_count": 0 }  // for the list-size policy
}
```

### 5.2 OPA output — the `decision` object MUST have exactly these keys:

```json
{
  "allow": true,
  "patient_id_filter": [10001, 10023],   // integers; [] means "no patients"; use the
                                          // sentinel "*" ONLY inside data, service treats
                                          // a filter containing the string "*" as "all"
  "redact_fields": ["ssn","address"],    // field names to blank out
  "reason": "attending physician access via TREATS relationship",
  "decision_id": "uuid"                  // generated in Rego via uuid or opa.runtime; if
                                          // not available, service generates one and logs it
}
```

Rules to implement in package `healthcare.authz` (see brief section "Rego Rules"):
`allow_read_patient`, `allow_read_note`, `patient_id_filter`, `redact_fields`,
`allow_list_action` (deny when `context.requested_count` > `max_list` [=50]).
Redaction: researcher always `["name","dob","ssn","address","mrn"]`; billing `[]`;
patient sees only own record, notes require `patient_viewable:true` (enforced in service
query, but note-type policy returns allow + reason). Nurse/attending/resident get `[]`
redaction for patients in their filter.

Static data files in bundle (mounted at `/data`):
- `data/units.json`         → `{ "unit_patients": { "CCU":[...], "ICU-3":[...] } }`
- `data/supervision.json`   → `{ "supervision": { "dr_jones":"dr_smith" } }`
- `data/protocols.json`     → `{ "protocol_patients": { "CARDIO-2025":[...] } }`
- `data/care_team.json`     → `{ "care_team": [ {"provider":"dr_smith","patient":10001,"rel":"TREATS"}, ... ] }`
- `data/config.json`        → `{ "config": { "max_list": 50 } }`

Patient IDs are integers starting at `10001`. `patient_portal` = patient `10001`.
Keep the demo data internally consistent: dr_smith TREATS a set incl. 10001; nurse_adams'
unit CCU contains a subset; researcher cohort = all cardiology patient ids; dr_jones
inherits dr_smith's set via supervision. Use ids in range 10001..10050.

## 6. Voyage embeddings

- Endpoint: `https://api.voyageai.com/v1/embeddings`
- Header: `Authorization: Bearer ${VOYAGE_API_KEY}`
- Body: `{ "input": ["..."], "model": "voyage-3-lite", "input_type": "query"|"document" }`
- Response: `{ "data":[ {"embedding":[...1024 floats...]} ], ... }`
- Dim: 1024. Postgres column `vector(1024)`.

## 7. Postgres schema (created by seed, DDL in `seed/postgres/01_schema.sql`)

Tables (service maps these via JPA):
- `patients(id BIGINT PK, mrn TEXT, name TEXT, dob DATE, sex TEXT, address TEXT, ssn TEXT, insurance_id TEXT, department TEXT)`
  (add `department` so researcher cohort = cardiology is derivable; patient ids 10001..)
- `encounters(id BIGINT PK, patient_id BIGINT FK, admit_time TIMESTAMP, discharge_time TIMESTAMP, unit_id TEXT, chief_complaint TEXT)`
- `orders(id BIGINT PK, encounter_id BIGINT FK, ordering_provider_id TEXT, order_type TEXT, order_details TEXT)`
- `medications(id BIGINT PK, encounter_id BIGINT FK, drug TEXT, dose TEXT, route TEXT, frequency TEXT)`
- `lab_results(id BIGINT PK, encounter_id BIGINT FK, lab_type TEXT, value TEXT, unit TEXT, reference_range TEXT, taken_at TIMESTAMP)`
- `clinical_notes_embeddings(note_id TEXT PK, patient_id BIGINT, note_type TEXT, specialty TEXT, embedding vector(1024))`
  with HNSW index: `CREATE INDEX ON clinical_notes_embeddings USING hnsw (embedding vector_cosine_ops);`
- Extension: `CREATE EXTENSION IF NOT EXISTS vector;`

`note_id` cross-references Mongo document `_id`/`note_id` so vector hits link to full text.

## 8. MongoDB (seeded; service reads)

Database `clinical`, collections: `progress_notes`, `radiology_reports`,
`pathology_reports`, `discharge_summaries`. Each document at minimum:
`{ note_id: "<matches postgres note_id>", patient_id: <int>, specialty: "...",
   note_type: "...", text: "<full transcription>", patient_viewable: <bool> }`
plus specialty-specific fields (varying schema is intentional). `patient_viewable` true on
a subset (esp. discharge summaries) so the patient-portal policy is demonstrable.

## 9. Neo4j (seeded; service reads via Bolt)

Nodes: `Physician{id,name,department}`, `Nurse{id,name}`, `Patient{id,name}`,
`Unit{name}`, `Specialty{name}`, `Encounter{id,patient_id}`.
Rels: `(:Physician)-[:TREATS]->(:Patient)`, `(:Physician)-[:CONSULTS_ON]->(:Patient)`,
`(:Nurse)-[:ASSIGNED_TO_UNIT]->(:Unit)`, `(:Patient)-[:ADMITTED_TO]->(:Unit)`,
`(:Physician)-[:REFERRED_TO]->(:Physician)`, `(:Resident)-[:SUPERVISED_BY]->(:Physician)`
(model Resident as Physician label + role prop or a Resident label — use `Physician`
with `role` prop to keep it simple), `(:Encounter)-[:SIMILAR_CASE {score}]->(:Encounter)`.
Provider ids match Keycloak usernames (`dr_smith`, `dr_jones`, `nurse_adams`). Patient ids
match Postgres. The care-team edges here must match `opa/data/care_team.json`.

## 10. Java service (`com.healthcare.rag`)

- Java 21, Spring Boot 3.3+, Maven, multistage Dockerfile.
- Base package `com.healthcare.rag`. Server port 8080. Context path `/`.
- Endpoints:
  - `GET /.well-known/oauth-protected-resource` → RFC 9728 JSON:
    `{ "resource":"https://mcp.local", "authorization_servers":["https://keycloak.local/realms/healthcare-demo"], "scopes_supported":["patients:read","notes:read","careteam:read","audit:read"], "bearer_methods_supported":["header"] }`
    (public, no auth)
  - `POST /mcp` (+ GET/DELETE for streamable HTTP session) → MCP endpoint, protected.
    On missing/invalid token return **401** with header
    `WWW-Authenticate: Bearer resource_metadata="https://mcp.local/.well-known/oauth-protected-resource"`.
  - `GET /actuator/health` (public).
- JWT validation: custom `JwtDecoder` = `NimbusJwtDecoder.withJwkSetUri(<internal certs url>)`
  + validators: timestamp + issuer(`https://keycloak.local/realms/healthcare-demo`).
  (Fetch JWKS internally over http to avoid TLS trust; validate external issuer string.)
- MCP: MCP Java SDK (`io.modelcontextprotocol.sdk:mcp` + `mcp-spring-webmvc` transport),
  wired via `@Configuration` (NOT Spring AI ChatClient/VectorStore/EmbeddingClient/Rag).
  Register 5 tools. Each handler reads identity from `SecurityContextHolder`
  (`JwtAuthenticationToken`), builds OPA input, calls OPA, enforces filter+redaction,
  queries the DBs, returns structured JSON text content.
- Config env the service reads:
  `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`,
  `SPRING_DATA_MONGODB_URI`, `SPRING_NEO4J_URI`, `SPRING_NEO4J_AUTHENTICATION_USERNAME`,
  `SPRING_NEO4J_AUTHENTICATION_PASSWORD`, `OPA_URL` (=`http://opa:8181`),
  `KEYCLOAK_INTERNAL_URL` (=`http://keycloak:8080`), `KEYCLOAK_ISSUER`
  (=`https://keycloak.local/realms/healthcare-demo`), `VOYAGE_API_KEY`, `VOYAGE_MODEL`,
  `MCP_RESOURCE_URL` (=`https://mcp.local`).

## 11. MCP tools ↔ scope

| tool | scope | resource_type | notes |
|---|---|---|---|
| `search_patients` | patients:read | patient | vector search + structured; input: `{query:string, limit?:int}` |
| `get_patient_summary` | patients:read, notes:read | patient | input: `{patient_id:int}`; joins PG+Mongo+Neo4j |
| `find_similar_cases` | notes:read | note | input: `{query:string, limit?:int}`; pgvector + Neo4j SIMILAR_CASE |
| `query_care_team` | careteam:read | care_team | input: `{patient_id:int}`; Neo4j traversal |
| `audit_my_access` | audit:read | audit | input: `{}`; returns recent decisions for this user from the in-service audit log |

Audit: service keeps an in-memory ring buffer of every OPA decision (user, action,
resource, allow, reason, decision_id, ts). `audit_my_access` returns the caller's entries.
(OPA console decision logs are the canonical trail; this tool surfaces them per-user.)

## 12. Startup ordering (compose `depends_on` + healthchecks)

postgres/mongodb/neo4j healthy → `seed` runs to completion (exit 0) → mcp-server starts.
keycloak healthy (its own health endpoint) before mcp-server. opa up before mcp-server.
Traefik depends on mcp-server + keycloak. Seed is `restart: no`.
