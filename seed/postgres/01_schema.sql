-- Healthcare RAG demo — Postgres schema (CONTRACT §7)
-- Executed by seed.py before loading. Uses IF NOT EXISTS so re-runs are safe;
-- seed.py additionally TRUNCATEs the tables before each load for clean re-seeds.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS patients (
    id           BIGINT PRIMARY KEY,
    mrn          TEXT,
    name         TEXT,
    dob          DATE,
    sex          TEXT,
    address      TEXT,
    ssn          TEXT,
    insurance_id TEXT,
    department   TEXT
);

CREATE TABLE IF NOT EXISTS encounters (
    id             BIGINT PRIMARY KEY,
    patient_id     BIGINT REFERENCES patients(id),
    admit_time     TIMESTAMP,
    discharge_time TIMESTAMP,
    unit_id        TEXT,
    chief_complaint TEXT
);

CREATE TABLE IF NOT EXISTS orders (
    id                   BIGINT PRIMARY KEY,
    encounter_id         BIGINT REFERENCES encounters(id),
    ordering_provider_id TEXT,
    order_type           TEXT,
    order_details        TEXT
);

CREATE TABLE IF NOT EXISTS medications (
    id           BIGINT PRIMARY KEY,
    encounter_id BIGINT REFERENCES encounters(id),
    drug         TEXT,
    dose         TEXT,
    route        TEXT,
    frequency    TEXT
);

CREATE TABLE IF NOT EXISTS lab_results (
    id              BIGINT PRIMARY KEY,
    encounter_id    BIGINT REFERENCES encounters(id),
    lab_type        TEXT,
    value           TEXT,
    unit            TEXT,
    reference_range TEXT,
    taken_at        TIMESTAMP
);

CREATE TABLE IF NOT EXISTS clinical_notes_embeddings (
    note_id    TEXT PRIMARY KEY,
    patient_id BIGINT,
    note_type  TEXT,
    specialty  TEXT,
    embedding  vector(1024)
);

-- HNSW index for cosine similarity search over note embeddings (CONTRACT §7).
CREATE INDEX IF NOT EXISTS clinical_notes_embeddings_hnsw
    ON clinical_notes_embeddings
    USING hnsw (embedding vector_cosine_ops);
