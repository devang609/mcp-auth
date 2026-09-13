# Seed pipeline — one-shot Python container (CONTRACT §1, §12).
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    DATA_DIR=/data

WORKDIR /app

# curl is used by download_mt_samples.sh; ca-certificates for HTTPS to HF + Voyage.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .
RUN chmod +x download_mt_samples.sh

# Where the MT Samples CSV is cached (mount a volume here to persist across runs).
VOLUME ["/data"]

CMD ["python", "seed.py"]
