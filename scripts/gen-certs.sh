#!/usr/bin/env bash
#
# gen-certs.sh — generate a locally-trusted TLS cert for the healthcare MCP demo.
#
# Produces:
#   traefik/certs/local.pem      (certificate, covers mcp.local + keycloak.local)
#   traefik/certs/local-key.pem  (private key)
#
# Uses mkcert so the cert is trusted by the OS/browser trust store (mkcert -install).
# Idempotent: skips generation if both files already exist.

set -euo pipefail

# Resolve repo root (this script lives in <root>/scripts).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CERT_DIR="${ROOT_DIR}/traefik/certs"

CERT_FILE="${CERT_DIR}/local.pem"
KEY_FILE="${CERT_DIR}/local-key.pem"

HOSTS=("mcp.local" "keycloak.local")

if ! command -v mkcert >/dev/null 2>&1; then
  echo "ERROR: mkcert is not installed or not on PATH." >&2
  echo "" >&2
  echo "mkcert generates a locally-trusted development certificate." >&2
  echo "Install it, then re-run this script:" >&2
  echo "  macOS:   brew install mkcert nss" >&2
  echo "  Linux:   see distro packages or the releases page" >&2
  echo "  Docs:    https://github.com/FiloSottile/mkcert" >&2
  exit 1
fi

mkdir -p "${CERT_DIR}"

if [[ -f "${CERT_FILE}" && -f "${KEY_FILE}" ]]; then
  echo "Certificates already exist — skipping generation:"
  echo "  ${CERT_FILE}"
  echo "  ${KEY_FILE}"
  echo "(Delete them and re-run to regenerate.)"
  exit 0
fi

echo "Installing mkcert local CA into the system trust store (mkcert -install)..."
mkcert -install

echo "Generating certificate for: ${HOSTS[*]}"
mkcert -cert-file "${CERT_FILE}" -key-file "${KEY_FILE}" "${HOSTS[@]}"

echo ""
echo "Done. Certificate written to:"
echo "  ${CERT_FILE}"
echo "  ${KEY_FILE}"
echo ""
echo "Traefik references these at /certs/local.pem and /certs/local-key.pem."
