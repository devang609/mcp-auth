# Demo Script — Fine-Grained Access Control over MCP

This script walks through the "same prompt, different results per role" story that makes the demo compelling. It assumes the stack is running and Claude Code Desktop is connected.

**Setup recap:** `cp .env.example .env` (add `VOYAGE_API_KEY`) → add `127.0.0.1 mcp.local keycloak.local` to your hosts file → run `scripts/gen-certs.sh` (or `.ps1`) → `docker compose up --build` → add MCP server `https://mcp.local` (endpoint `https://mcp.local/mcp`) in Claude Code Desktop. See [README.md](README.md) for details.

## Switching users in Claude Code Desktop

Access tokens are **per-connection**: the JWT (and therefore the role and claims) is bound to the connection you authorized. To demo a different role you must re-authenticate as a different Keycloak user:

- **Cleanest:** remove the `https://mcp.local` MCP connection and re-add it, then complete the OAuth login as the new user (e.g. `nurse_adams` / `demo123`). This forces a fresh authorization from scratch.
- **Or use the re-auth flow:** trigger a re-login on the connection so Claude Code sends you back to Keycloak's authorization endpoint.
- At **consent moment 1** (Keycloak's login + consent screen) log in as the user whose role you want to demonstrate. Because Keycloak may hold an SSO session cookie, you may need to log out of Keycloak first (or use a private browser window) so it prompts for the new user's credentials rather than silently reusing the previous session.

All six demo users share the password `demo123`.

## Example prompts (run each as multiple users)

For each prompt, run it, then switch users (above) and run the identical prompt. The difference in output is the point — it comes entirely from OPA's `patient_id_filter` and `redact_fields`, not from any change in the prompt.

### 1. "Summarize patient 10001's recent cardiac history."

Tool: `get_patient_summary` (patients:read + notes:read).

| Run as | Expected result |
|---|---|
| `dr_smith` (attending) | **Full** record — demographics, encounters, notes, meds, labs; no redaction (10001 is in his TREATS set). |
| `nurse_adams` (nurse) | Clinical detail, but **SSN, insurance, and address redacted** (10001 is in her CCU unit). |
| `researcher_lee` (researcher) | Cohort-style data with **name, dob, ssn, address, mrn redacted** (10001 is a cardiology-cohort patient). |
| `billing_clerk` (billing) | **DENIED for clinical notes** — billing has no note access. |
| `patient_portal` (patient 10001) | **Only patient-viewable notes** for their own record (e.g. discharge summaries flagged `patient_viewable:true`). |

### 2. "Find patients with heart failure symptoms." (semantic search)

Tool: `search_patients` (patients:read) — Voyage embedding → pgvector similarity, then filtered.

- Each role gets results constrained by a **different `patient_id_filter`**: `dr_smith` sees his TREATS set, `nurse_adams` sees CCU patients, `researcher_lee` sees the CARDIO-2025 cohort, `dr_jones` inherits `dr_smith`'s set via supervision. The semantic ranking is the same; the visible rows differ per role.

### 3. "Who is on the care team for patient 10001?"

Tool: `query_care_team` (careteam:read) — Neo4j traversal.

- Requires the `careteam:read` scope; returns the graph result (TREATS / CONSULTS_ON / ASSIGNED_TO_UNIT edges) for authorized roles. A user without the scope consented, or without access to patient 10001, is denied.

### 4. "Show me similar cardiology cases to <query>."

Tool: `find_similar_cases` (notes:read) — pgvector similarity + Neo4j `SIMILAR_CASE` edges.

- Returns semantically similar clinical cases, joined through the `SIMILAR_CASE` graph relationship, subject to the caller's note access and patient filter.

### 5. "What have I accessed recently?"

Tool: `audit_my_access` (audit:read).

- Returns the calling user's recent OPA decisions (action, resource, allow/deny, reason, decision_id, timestamp) from the in-service audit log. Run this as different users to show each sees **only their own** access history.

## The two consent moments (screenshots)

Capture these two screens — they are the heart of the "layered consent" story — plus the audit line.

**Consent moment 1 — Keycloak OAuth consent screen.** After login, Keycloak lists the requested scopes in human-readable form ("Access patient records on your behalf", "Access clinical notes on your behalf", "Query care team relationships", "View your access history"). The user approves the scopes the client may use.

![Consent moment 1](docs/img/consent-oauth.png)

**Consent moment 2 — Claude Code Desktop per-tool invocation prompt.** Before a tool runs, Claude Code asks to approve that specific call: *"Claude wants to use `search_patients` — Deny / Allow once / Allow always."*

![Consent moment 2](docs/img/consent-tool.png)

**The audit trail — OPA decision log.** Every authorization decision is logged by OPA. Watch it live while running the prompts above:

```bash
docker compose logs -f opa
```

Point to the decision-log line for a call (it shows the input identity, the decision, and the `decision_id`) — this is the canonical audit record that pairs with the per-user `audit_my_access` view.

![OPA decision log](docs/img/opa-decision-log.png)
