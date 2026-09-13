#!/usr/bin/env bash
# Download the MT Samples CSV to $DATA_DIR/mtsamples.csv (default /data).
# Public dataset; HF_TOKEN (if set) is only used to avoid rate limits.
set -euo pipefail

DATA_DIR="${DATA_DIR:-/data}"
OUT="${DATA_DIR}/mtsamples.csv"
URL="https://huggingface.co/datasets/harishnair04/mtsamples/resolve/main/mtsamples.csv"

mkdir -p "${DATA_DIR}"

if [ -f "${OUT}" ]; then
    echo "[download] ${OUT} already exists (skipping download)."
    exit 0
fi

# If the user has pre-downloaded the CSV to ./seed/data/ on the host (bind-mounted at
# /host-data), copy it in and skip the network fetch. Handy when DNS/egress is blocked.
if [ -f "/host-data/mtsamples.csv" ]; then
    echo "[download] found host-mounted /host-data/mtsamples.csv (skipping network fetch)"
    cp "/host-data/mtsamples.csv" "${OUT}"
    echo "[download] copied: $(wc -c < "${OUT}") bytes"
    exit 0
fi

echo "[download] fetching ${URL}"
echo "[download]   -> ${OUT}"

if [ -n "${HF_TOKEN:-}" ]; then
    echo "[download] using HF_TOKEN for authenticated request"
    curl -fL -H "Authorization: Bearer ${HF_TOKEN}" -o "${OUT}" "${URL}"
else
    curl -fL -o "${OUT}" "${URL}"
fi

echo "[download] done: $(wc -c < "${OUT}") bytes"
