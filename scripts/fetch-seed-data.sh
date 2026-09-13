#!/usr/bin/env bash
# Download the MT Samples CSV to seed/data/ on the HOST.
#
# Run this once before `docker compose up` in any environment where the Docker bridge
# network can't reach huggingface.co (corp networks, Codespaces with restricted container
# egress, etc.). The seed container bind-mounts seed/data/ at /host-data/ and uses that
# copy instead of downloading. Idempotent: skips if the file already exists.
set -euo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)/seed/data"
OUT="${DIR}/mtsamples.csv"
URL="https://huggingface.co/datasets/harishnair04/mtsamples/resolve/main/mtsamples.csv"

mkdir -p "${DIR}"

if [ -f "${OUT}" ] && [ "$(wc -c < "${OUT}")" -gt 1000000 ]; then
    echo "[fetch-seed-data] already present: ${OUT} ($(wc -c < "${OUT}") bytes)"
    exit 0
fi

echo "[fetch-seed-data] downloading ${URL}"
if [ -n "${HF_TOKEN:-}" ]; then
    curl -fL --retry 3 --retry-delay 2 \
         -H "Authorization: Bearer ${HF_TOKEN}" \
         -o "${OUT}" "${URL}"
else
    curl -fL --retry 3 --retry-delay 2 -o "${OUT}" "${URL}"
fi
echo "[fetch-seed-data] done: ${OUT} ($(wc -c < "${OUT}") bytes)"
