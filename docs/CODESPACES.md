# Running the demo from a GitHub Codespace

The whole stack runs in Docker inside the Codespace; Claude Code Desktop on your laptop
connects to it over the Codespace's forwarded HTTPS URLs. No Traefik, mkcert, or `/etc/hosts`
needed — github.dev terminates TLS for each forwarded port.

Recommended machine: **4 cores / 16 GB** is plenty (steady-state RAM ≈ 3 GB, images ≈ 5 GB).

## Steps

1. **Prereqs in the Codespace:** Docker + Docker Compose (install these yourself per your
   devcontainer). Confirm with `docker version`.

2. **Secrets:**
   ```bash
   cp .env.example .env
   ```
   Put your `VOYAGE_API_KEY` in `.env`. Leave `PUBLIC_MCP_URL` / `PUBLIC_KEYCLOAK_URL` blank
   (you'll export them next).

3. **Export the two public URLs** (the shell expands `$CODESPACE_NAME`):
   ```bash
   export PUBLIC_MCP_URL=https://$CODESPACE_NAME-8080.app.github.dev
   export PUBLIC_KEYCLOAK_URL=https://$CODESPACE_NAME-8081.app.github.dev
   ```

4. **Fetch the MT Samples CSV on the Codespace host** (one-time; skipped on re-runs).
   The Docker bridge network in most Codespaces can't resolve `huggingface.co`, but the
   Codespace host can — this script downloads it on the host so the seed container reads
   it from a bind mount instead of the network:
   ```bash
   bash scripts/fetch-seed-data.sh
   ```

5. **Bring it up** (Traefik stays off; ports 8080 + 8081 are published):
   ```bash
   docker compose up --build
   ```
   Wait for `hc-seed` to finish with its summary, then `hc-mcp-server` to report healthy.

6. **Make the ports public:** in the **Ports** tab, find **8080** (mcp-server) and **8081**
   (keycloak). Right-click each → **Port Visibility → Public**. (External clients like Claude
   Code Desktop can't reach a Private forwarded port.)

7. **Connect Claude Code Desktop** to the MCP server:
   ```
   https://<your-codespace>-8080.app.github.dev/mcp
   ```
   (Same value as `PUBLIC_MCP_URL` + `/mcp` — copy 8080's URL from the Ports tab.)

8. Trigger a tool. The OAuth flow opens your system browser at the Keycloak URL
   (`...-8081.app.github.dev`); log in as e.g. `dr_smith / demo123`, approve the scope
   consent, then approve the MCP tool-invocation prompt. The redirect returns to
   `127.0.0.1:<port>` on your laptop — that part is local, so it works normally.

## ⚠ Container egress — the Voyage embedding call

Step 4 sidesteps the MT Samples download by fetching on the host. During seeding, the
container also calls `api.voyageai.com` to compute ~800 embeddings — that call runs from
INSIDE the container. If your Codespace blocks container DNS/egress broadly (not just to
huggingface.co), Voyage will fail the same way.

Quick check from a Codespace terminal:
```bash
docker run --rm --network healthcare-rag_healthnet curlimages/curl -fsS -o /dev/null -w "%{http_code}\n" https://api.voyageai.com/v1/embeddings || echo "container egress blocked"
```

If it prints an HTTP status (401 is fine — means it reached the API), you're good.
If it prints `container egress blocked`, tell me and I'll wire an in-network embedding
container (Ollama with `nomic-embed-text`) so seeding needs zero container egress.

## Notes / troubleshooting

- **Restarting the Codespace** keeps `$CODESPACE_NAME`, so the URLs are stable across
  restarts of the same Codespace. A brand-new Codespace gets a new name → re-export and
  re-add the MCP URL in Claude Code.
- **`docker compose` doesn't see the exported vars?** They must be exported in the *same*
  shell that runs `docker compose up`. Verify with `echo $PUBLIC_MCP_URL`.
- **Keycloak login shows a wrong/redirect URL** → `PUBLIC_KEYCLOAK_URL` wasn't exported
  before `up`; `docker compose down` and repeat step 3–4.
- **Watch policy decisions:** `docker compose logs -f opa`.
- Ports 5432 / 27017 / 7687 (the databases) are intentionally NOT forwarded — they're
  internal to the Docker network.
