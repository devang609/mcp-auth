#!/usr/bin/env python3
"""Seed pipeline for the Dockerized healthcare MCP demo.

Orchestrates:
  1. ensure the MT Samples CSV is present (download if needed),
  2. parse the CSV,
  3. generate structured data via Faker (generate_structured.generate),
  4. load Postgres (structured tables) + Mongo (notes) + Neo4j (graph),
  5. compute Voyage embeddings for each note and store them in Postgres.

Reads all connection coordinates from the environment (defaults match the
CONTRACT so it also runs standalone). Idempotent-ish: schema is created with
IF NOT EXISTS and every table/collection is truncated before load, so re-runs
are clean. On any failure it prints a clear message and exits non-zero.
"""

from __future__ import annotations

import os
import subprocess
import sys
import time
import traceback

import requests

from generate_structured import generate

# --------------------------------------------------------------------------- #
# Configuration (env with CONTRACT-matching defaults)
# --------------------------------------------------------------------------- #

DATA_DIR = os.environ.get("DATA_DIR", "/data")
CSV_PATH = os.path.join(DATA_DIR, "mtsamples.csv")

PG_HOST = os.environ.get("PG_HOST", "postgres")
PG_PORT = int(os.environ.get("PG_PORT", "5432"))
PG_DB = os.environ.get("PG_DB", "healthcare")
PG_USER = os.environ.get("PG_USER", "rag")
PG_PASSWORD = os.environ.get("PG_PASSWORD", "ragpass")

MONGO_URI = os.environ.get(
    "MONGO_URI", "mongodb://rag:ragpass@mongodb:27017/clinical?authSource=admin"
)
MONGO_DB = os.environ.get("MONGO_DB", "clinical")

NEO4J_URI = os.environ.get("NEO4J_URI", "bolt://neo4j:7687")
NEO4J_USER = os.environ.get("NEO4J_USER", "neo4j")
NEO4J_PASSWORD = os.environ.get("NEO4J_PASSWORD", "neo4jpass")

VOYAGE_API_KEY = os.environ.get("VOYAGE_API_KEY", "").strip()
VOYAGE_MODEL = os.environ.get("VOYAGE_MODEL", "voyage-3-lite")
VOYAGE_URL = "https://api.voyageai.com/v1/embeddings"
EMBEDDING_DIM = int(os.environ.get("EMBEDDING_DIM", "512"))  # voyage-3-lite native dim

HF_TOKEN = os.environ.get("HF_TOKEN", "").strip()

SCHEMA_SQL = os.path.join(os.path.dirname(__file__), "postgres", "01_schema.sql")

# Tuning
MAX_NOTES = int(os.environ.get("MAX_NOTES", "60"))        # cap rows (free-tier fits 60 in ~4 min)
NUM_PATIENTS = int(os.environ.get("NUM_PATIENTS", "50"))
EMBED_BATCH = int(os.environ.get("EMBED_BATCH", "6"))     # Voyage batch size (free tier: keep small)
EMBED_TRUNCATE = int(os.environ.get("EMBED_TRUNCATE", "1500"))  # chars/note (≈400 tokens)
EMBED_INTERVAL_SEC = float(os.environ.get("EMBED_INTERVAL_SEC", "22"))  # >20s → <3 RPM
DB_RETRY_SECONDS = int(os.environ.get("DB_RETRY_SECONDS", "60"))


def log(msg: str) -> None:
    print(f"[seed] {msg}", flush=True)


# --------------------------------------------------------------------------- #
# Step 1 — ensure CSV present
# --------------------------------------------------------------------------- #

def ensure_csv() -> None:
    if os.path.exists(CSV_PATH) and os.path.getsize(CSV_PATH) > 0:
        log(f"CSV already present at {CSV_PATH} "
            f"({os.path.getsize(CSV_PATH)} bytes)")
        return

    os.makedirs(DATA_DIR, exist_ok=True)

    # Fast path: host-mounted pre-downloaded CSV (see docker-compose seed volumes).
    host_csv = "/host-data/mtsamples.csv"
    if os.path.exists(host_csv) and os.path.getsize(host_csv) > 0:
        log(f"copying host-mounted CSV {host_csv} -> {CSV_PATH}")
        import shutil
        shutil.copyfile(host_csv, CSV_PATH)
        return

    downloader = os.path.join(os.path.dirname(__file__), "download_mt_samples.sh")

    # Prefer the shell downloader (uses curl); fall back to in-python requests.
    if os.path.exists(downloader):
        log("downloading MT Samples CSV via download_mt_samples.sh ...")
        try:
            subprocess.run(
                ["bash", downloader],
                check=True,
                env={**os.environ, "DATA_DIR": DATA_DIR},
            )
        except (subprocess.CalledProcessError, FileNotFoundError) as exc:
            log(f"shell downloader failed ({exc}); falling back to python download")
            _download_csv_python()
    else:
        _download_csv_python()

    if not (os.path.exists(CSV_PATH) and os.path.getsize(CSV_PATH) > 0):
        raise RuntimeError(f"CSV download did not produce a file at {CSV_PATH}")
    log(f"CSV ready: {os.path.getsize(CSV_PATH)} bytes")


def _download_csv_python() -> None:
    url = ("https://huggingface.co/datasets/harishnair04/mtsamples/"
           "resolve/main/mtsamples.csv")
    headers = {}
    if HF_TOKEN:
        headers["Authorization"] = f"Bearer {HF_TOKEN}"
    log(f"downloading {url} (python/requests)")
    with requests.get(url, headers=headers, stream=True, timeout=120) as resp:
        resp.raise_for_status()
        with open(CSV_PATH, "wb") as fh:
            for chunk in resp.iter_content(chunk_size=1 << 16):
                if chunk:
                    fh.write(chunk)


# --------------------------------------------------------------------------- #
# Step 2 — parse CSV
# --------------------------------------------------------------------------- #

def parse_csv() -> list[dict]:
    """Return list of note rows with normalized keys, non-empty transcription."""
    import pandas as pd

    df = pd.read_csv(CSV_PATH)
    # Normalize column names: strip + lower.
    df.columns = [str(c).strip().lower() for c in df.columns]

    def col(*candidates):
        for c in candidates:
            if c in df.columns:
                return c
        return None

    c_desc = col("description")
    c_spec = col("medical_specialty", "medical specialty")
    c_name = col("sample_name", "sample name")
    c_trans = col("transcription")
    c_keys = col("keywords")

    if c_trans is None:
        raise RuntimeError(
            f"CSV missing 'transcription' column; found {list(df.columns)}")

    rows: list[dict] = []
    for idx, r in df.iterrows():
        trans = r.get(c_trans)
        if trans is None or (isinstance(trans, float)) or not str(trans).strip():
            continue
        rows.append({
            "rownum": int(idx),
            "description": _s(r.get(c_desc)),
            "medical_specialty": _s(r.get(c_spec)) or "Unknown",
            "sample_name": _s(r.get(c_name)),
            "transcription": str(trans).strip(),
            "keywords": _s(r.get(c_keys)),
        })
        if len(rows) >= MAX_NOTES:
            break

    log(f"parsed {len(rows)} notes with non-empty transcription "
        f"(cap {MAX_NOTES})")
    if not rows:
        raise RuntimeError("no usable notes parsed from CSV")
    return rows


def _s(v) -> str:
    import math
    if v is None:
        return ""
    if isinstance(v, float) and math.isnan(v):
        return ""
    return str(v).strip()


# --------------------------------------------------------------------------- #
# Step 3 — build note documents (patient linkage + Mongo routing)
# --------------------------------------------------------------------------- #

def _classify_collection(specialty: str, alt_counter: int) -> tuple[str, str]:
    """Map a medical_specialty string to (collection, note_type)."""
    s = specialty.lower()
    if any(k in s for k in ("cardio", "cardiovascular", "pulmonary", "consult")):
        return "progress_notes", "progress_note"
    if "radiolog" in s:
        return "radiology_reports", "radiology_report"
    if "patholog" in s or "lab" in s:
        return "pathology_reports", "pathology_report"
    if "discharge" in s:
        return "discharge_summaries", "discharge_summary"
    # everything else: distribute ~2:1 toward progress notes vs discharge summaries
    if alt_counter % 3 == 0:
        return "discharge_summaries", "discharge_summary"
    return "progress_notes", "progress_note"


def build_notes(rows: list[dict], patient_ids: list[int],
                cardiology_ids: list[int]) -> list[dict]:
    """Distribute CSV notes across patients and route them to Mongo collections."""
    import hashlib

    ci = 0   # cardiology round-robin index
    ai = 0   # all-patient round-robin index
    alt = 0  # alternator for "everything else" routing

    notes: list[dict] = []
    for row in rows:
        spec = row["medical_specialty"]
        is_cardio_note = "cardio" in spec.lower() or "cardiovascular" in spec.lower()

        if is_cardio_note and cardiology_ids:
            pid = cardiology_ids[ci % len(cardiology_ids)]
            ci += 1
        else:
            pid = patient_ids[ai % len(patient_ids)]
            ai += 1

        collection, note_type = _classify_collection(spec, alt)
        alt += 1

        note_id = f"note-{row['rownum']}"

        # ~20% patient_viewable, deterministic; discharge summaries biased higher.
        h = int(hashlib.md5(note_id.encode()).hexdigest(), 16)
        viewable = (h % 6 == 0)
        # discharge summaries are "esp." patient-viewable (CONTRACT §8) — add a
        # modest boost without pushing the overall share far past ~20%.
        if collection == "discharge_summaries" and (h % 4 == 0):
            viewable = True

        doc = {
            "note_id": note_id,
            "patient_id": pid,
            "specialty": spec,
            "note_type": note_type,
            "text": row["transcription"],
            "patient_viewable": viewable,
            "collection": collection,          # internal routing; stripped on insert
            "description": row["description"],
            "keywords": row["keywords"],
        }

        # Specialty-specific fields so schemas visibly vary (CONTRACT §8).
        if collection == "radiology_reports":
            doc["modality"] = _guess_modality(row)
            doc["impression"] = (row["description"] or row["sample_name"] or "")[:300]
        elif collection == "pathology_reports":
            doc["specimen"] = row["sample_name"] or "Tissue specimen"
            doc["result"] = "See report text"
        elif collection == "discharge_summaries":
            doc["discharge_disposition"] = "Home"
            doc["follow_up"] = "Follow up with primary care in 1-2 weeks"
        else:  # progress_notes
            doc["author"] = "dr_smith" if pid in cardiology_ids else "dr_lee"
            doc["sample_name"] = row["sample_name"]

        notes.append(doc)

    _ensure_patient_10001_mix(notes)
    _log_note_distribution(notes)
    return notes


def _guess_modality(row: dict) -> str:
    blob = f"{row['sample_name']} {row['description']} {row['keywords']}".lower()
    for key, label in (("ct", "CT"), ("mri", "MRI"), ("x-ray", "X-ray"),
                       ("xray", "X-ray"), ("ultrasound", "Ultrasound"),
                       ("echo", "Echocardiogram"), ("pet", "PET")):
        if key in blob:
            return label
    return "X-ray"


def _ensure_patient_10001_mix(notes: list[dict]) -> None:
    """Guarantee patient 10001 has some viewable and some non-viewable notes."""
    mine = [n for n in notes if n["patient_id"] == 10001]
    if not mine:
        # Reassign the first two notes to 10001 so the portal demo has data.
        for n in notes[:2]:
            n["patient_id"] = 10001
        mine = notes[:2]
    # Force at least 2 viewable and 2 non-viewable where possible.
    for n in mine[:2]:
        n["patient_viewable"] = True
    for n in mine[2:4]:
        n["patient_viewable"] = False


def _log_note_distribution(notes: list[dict]) -> None:
    by_coll: dict[str, int] = {}
    viewable = 0
    for n in notes:
        by_coll[n["collection"]] = by_coll.get(n["collection"], 0) + 1
        if n["patient_viewable"]:
            viewable += 1
    log(f"note routing: {by_coll}")
    log(f"patient_viewable notes: {viewable}/{len(notes)} "
        f"({100 * viewable / max(1, len(notes)):.0f}%)")
    p1 = [n for n in notes if n["patient_id"] == 10001]
    log(f"patient 10001 notes: {len(p1)} "
        f"(viewable={sum(1 for n in p1 if n['patient_viewable'])}, "
        f"hidden={sum(1 for n in p1 if not n['patient_viewable'])})")


# --------------------------------------------------------------------------- #
# Step 4a — Postgres load
# --------------------------------------------------------------------------- #

def connect_postgres():
    import psycopg
    deadline = time.time() + DB_RETRY_SECONDS
    last = None
    while time.time() < deadline:
        try:
            conn = psycopg.connect(
                host=PG_HOST, port=PG_PORT, dbname=PG_DB,
                user=PG_USER, password=PG_PASSWORD, connect_timeout=5,
            )
            conn.autocommit = False
            log(f"connected to Postgres {PG_HOST}:{PG_PORT}/{PG_DB}")
            return conn
        except Exception as exc:  # noqa: BLE001
            last = exc
            log(f"waiting for Postgres... ({exc})")
            time.sleep(3)
    raise RuntimeError(f"could not connect to Postgres in {DB_RETRY_SECONDS}s: {last}")


def load_postgres_structured(conn, data: dict) -> None:
    with open(SCHEMA_SQL, "r", encoding="utf-8") as fh:
        schema = fh.read()

    with conn.cursor() as cur:
        log("applying schema (01_schema.sql)")
        cur.execute(schema)

        log("truncating structured tables")
        cur.execute(
            "TRUNCATE clinical_notes_embeddings, lab_results, medications, "
            "orders, encounters, patients RESTART IDENTITY CASCADE;"
        )

        cur.executemany(
            "INSERT INTO patients "
            "(id, mrn, name, dob, sex, address, ssn, insurance_id, department) "
            "VALUES (%(id)s, %(mrn)s, %(name)s, %(dob)s, %(sex)s, %(address)s, "
            "%(ssn)s, %(insurance_id)s, %(department)s)",
            data["patients"],
        )
        cur.executemany(
            "INSERT INTO encounters "
            "(id, patient_id, admit_time, discharge_time, unit_id, chief_complaint) "
            "VALUES (%(id)s, %(patient_id)s, %(admit_time)s, %(discharge_time)s, "
            "%(unit_id)s, %(chief_complaint)s)",
            data["encounters"],
        )
        cur.executemany(
            "INSERT INTO orders "
            "(id, encounter_id, ordering_provider_id, order_type, order_details) "
            "VALUES (%(id)s, %(encounter_id)s, %(ordering_provider_id)s, "
            "%(order_type)s, %(order_details)s)",
            data["orders"],
        )
        cur.executemany(
            "INSERT INTO medications "
            "(id, encounter_id, drug, dose, route, frequency) "
            "VALUES (%(id)s, %(encounter_id)s, %(drug)s, %(dose)s, %(route)s, "
            "%(frequency)s)",
            data["medications"],
        )
        cur.executemany(
            "INSERT INTO lab_results "
            "(id, encounter_id, lab_type, value, unit, reference_range, taken_at) "
            "VALUES (%(id)s, %(encounter_id)s, %(lab_type)s, %(value)s, %(unit)s, "
            "%(reference_range)s, %(taken_at)s)",
            data["lab_results"],
        )
    conn.commit()
    log(f"loaded Postgres: {len(data['patients'])} patients, "
        f"{len(data['encounters'])} encounters, {len(data['orders'])} orders, "
        f"{len(data['medications'])} meds, {len(data['lab_results'])} labs")


def load_postgres_embeddings(conn, notes: list[dict]) -> int:
    """Compute Voyage embeddings in batches and insert into Postgres."""
    total = len(notes)
    inserted = 0
    with conn.cursor() as cur:
        last_request_ts = 0.0
        for start in range(0, total, EMBED_BATCH):
            batch = notes[start:start + EMBED_BATCH]
            texts = [n["text"][:EMBED_TRUNCATE] for n in batch]

            # Throttle to stay under Voyage's free-tier 3 RPM limit.
            wait = EMBED_INTERVAL_SEC - (time.time() - last_request_ts)
            if last_request_ts and wait > 0:
                log(f"throttle: sleeping {wait:.1f}s to respect free-tier 3 RPM")
                time.sleep(wait)
            last_request_ts = time.time()

            embeddings = voyage_embed(texts)

            params = []
            for note, emb in zip(batch, embeddings):
                if len(emb) != EMBEDDING_DIM:
                    raise RuntimeError(
                        f"embedding dim {len(emb)} != expected {EMBEDDING_DIM}")
                vec = "[" + ",".join(f"{x:.7g}" for x in emb) + "]"
                params.append((note["note_id"], note["patient_id"],
                               note["note_type"], note["specialty"], vec))

            cur.executemany(
                "INSERT INTO clinical_notes_embeddings "
                "(note_id, patient_id, note_type, specialty, embedding) "
                "VALUES (%s, %s, %s, %s, %s::vector) "
                "ON CONFLICT (note_id) DO UPDATE SET embedding = EXCLUDED.embedding",
                params,
            )
            conn.commit()
            inserted += len(batch)
            log(f"embedded {inserted}/{total} notes")
    return inserted


def voyage_embed(texts: list[str], retries: int = 4) -> list[list[float]]:
    headers = {
        "Authorization": f"Bearer {VOYAGE_API_KEY}",
        "Content-Type": "application/json",
    }
    # Use the model's native output dim (512 for voyage-3-lite) — pgvector column matches.
    body = {"input": texts, "model": VOYAGE_MODEL, "input_type": "document"}
    for attempt in range(1, retries + 1):
        try:
            resp = requests.post(VOYAGE_URL, headers=headers, json=body, timeout=120)
            # Free-tier 429 needs a FULL RPM window (~60s), not an exponential-from-2s
            # backoff — bumping delay is meaningless when the limit is per-minute.
            if resp.status_code == 429:
                raise requests.HTTPError(f"429: {resp.text[:200]}")
            if resp.status_code >= 500:
                raise requests.HTTPError(f"{resp.status_code}: {resp.text[:200]}")
            resp.raise_for_status()
            data = resp.json()["data"]
            # Preserve request order.
            data.sort(key=lambda d: d.get("index", 0))
            return [d["embedding"] for d in data]
        except Exception as exc:  # noqa: BLE001
            if attempt == retries:
                raise RuntimeError(
                    f"Voyage embedding request failed after {retries} tries: {exc}"
                ) from exc
            is_429 = "429" in str(exc)
            delay = 65.0 if is_429 else min(2.0 * (2 ** (attempt - 1)), 30.0)
            log(f"Voyage request failed ({exc}); retry {attempt}/{retries} "
                f"in {delay:.0f}s")
            time.sleep(delay)
    return []  # unreachable


# --------------------------------------------------------------------------- #
# Step 4b — Mongo load
# --------------------------------------------------------------------------- #

def load_mongo(notes: list[dict]) -> dict:
    from pymongo import MongoClient

    deadline = time.time() + DB_RETRY_SECONDS
    client = None
    last = None
    while time.time() < deadline:
        try:
            client = MongoClient(MONGO_URI, serverSelectionTimeoutMS=4000)
            client.admin.command("ping")
            log("connected to Mongo")
            break
        except Exception as exc:  # noqa: BLE001
            last = exc
            log(f"waiting for Mongo... ({exc})")
            time.sleep(3)
    else:
        raise RuntimeError(f"could not connect to Mongo in {DB_RETRY_SECONDS}s: {last}")

    db = client[MONGO_DB]
    collections = ["progress_notes", "radiology_reports",
                   "pathology_reports", "discharge_summaries"]
    for c in collections:
        db[c].delete_many({})

    buckets: dict[str, list[dict]] = {c: [] for c in collections}
    for n in notes:
        coll = n["collection"]
        doc = {k: v for k, v in n.items() if k != "collection"}
        doc["_id"] = n["note_id"]  # cross-reference to Postgres note_id
        buckets[coll].append(doc)

    counts = {}
    for coll, docs in buckets.items():
        if docs:
            db[coll].insert_many(docs)
        counts[coll] = len(docs)
    log(f"loaded Mongo collections: {counts}")
    client.close()
    return counts


# --------------------------------------------------------------------------- #
# Step 4c — Neo4j load
# --------------------------------------------------------------------------- #

def load_neo4j(data: dict, notes: list[dict]) -> None:
    from neo4j import GraphDatabase

    deadline = time.time() + DB_RETRY_SECONDS
    driver = None
    last = None
    while time.time() < deadline:
        try:
            driver = GraphDatabase.driver(
                NEO4J_URI, auth=(NEO4J_USER, NEO4J_PASSWORD))
            driver.verify_connectivity()
            log("connected to Neo4j")
            break
        except Exception as exc:  # noqa: BLE001
            last = exc
            log(f"waiting for Neo4j... ({exc})")
            time.sleep(3)
    else:
        raise RuntimeError(f"could not connect to Neo4j in {DB_RETRY_SECONDS}s: {last}")

    patients = data["patients"]
    cardiology_ids = set(data["cardiology_ids"])

    # Care-team edges — MUST match opa/data/care_team.json semantics (CONTRACT §5/§9).
    treats = list(range(10001, 10006))          # dr_smith TREATS 10001-10005
    consults = [10010, 10011]                    # dr_smith CONSULTS_ON 10010,10011
    admitted = {"CCU": [10001, 10002, 10010], "ICU-3": [10003, 10004]}

    # A few SIMILAR_CASE edges between first encounters of cardiology patients.
    enc_by_patient: dict[int, int] = {}
    for e in data["encounters"]:
        enc_by_patient.setdefault(e["patient_id"], e["id"])
    cardio_encs = [enc_by_patient[p] for p in sorted(cardiology_ids)
                   if p in enc_by_patient]
    similar_edges = []
    for i in range(0, min(len(cardio_encs) - 1, 8)):
        similar_edges.append({
            "a": cardio_encs[i], "b": cardio_encs[i + 1],
            "score": round(0.75 + 0.02 * i, 3),
        })

    # Specialty nodes + REFERRED_TO edges (synthesized, kept simple).
    specialties = sorted({n["specialty"] for n in notes})[:12]
    referrals = [
        {"src": "dr_jones", "dst": "dr_smith"},
        {"src": "dr_lee", "dst": "dr_smith"},
    ]

    with driver.session() as session:
        session.run("MATCH (n) DETACH DELETE n")

        # Constraints (idempotent).
        for label, key in (("Physician", "id"), ("Nurse", "id"),
                           ("Patient", "id"), ("Unit", "name"),
                           ("Specialty", "name"), ("Encounter", "id")):
            session.run(
                f"CREATE CONSTRAINT IF NOT EXISTS FOR (x:{label}) "
                f"REQUIRE x.{key} IS UNIQUE")

        # Physicians (dr_jones modeled as Physician with role=resident).
        session.run(
            "MERGE (p:Physician {id:'dr_smith'}) "
            "SET p.name='Dr. Smith', p.department='cardiology', "
            "p.role='attending_physician'")
        session.run(
            "MERGE (p:Physician {id:'dr_jones'}) "
            "SET p.name='Dr. Jones', p.department='cardiology', p.role='resident'")
        # Extra physicians referenced as note authors / referrers.
        session.run(
            "MERGE (p:Physician {id:'dr_lee'}) "
            "SET p.name='Dr. Lee', p.department='general_medicine', "
            "p.role='attending_physician'")

        # Nurse
        session.run(
            "MERGE (n:Nurse {id:'nurse_adams'}) SET n.name='Nurse Adams'")

        # Units
        for u in ("CCU", "ICU-3"):
            session.run("MERGE (:Unit {name:$name})", name=u)

        # Patients
        session.run(
            "UNWIND $rows AS r MERGE (p:Patient {id:r.id}) SET p.name=r.name",
            rows=[{"id": p["id"], "name": p["name"]} for p in patients])

        # Encounters
        session.run(
            "UNWIND $rows AS r MERGE (e:Encounter {id:r.id}) "
            "SET e.patient_id=r.patient_id",
            rows=[{"id": e["id"], "patient_id": e["patient_id"]}
                  for e in data["encounters"]])
        # Link encounters to patients (handy for traversal, not required by contract).
        session.run(
            "MATCH (e:Encounter),(p:Patient {id:e.patient_id}) "
            "MERGE (p)-[:HAS_ENCOUNTER]->(e)")

        # dr_smith TREATS
        session.run(
            "MATCH (d:Physician {id:'dr_smith'}) "
            "UNWIND $ids AS pid MATCH (p:Patient {id:pid}) MERGE (d)-[:TREATS]->(p)",
            ids=treats)
        # dr_smith CONSULTS_ON
        session.run(
            "MATCH (d:Physician {id:'dr_smith'}) "
            "UNWIND $ids AS pid MATCH (p:Patient {id:pid}) "
            "MERGE (d)-[:CONSULTS_ON]->(p)",
            ids=consults)
        # dr_jones SUPERVISED_BY dr_smith
        session.run(
            "MATCH (r:Physician {id:'dr_jones'}),(a:Physician {id:'dr_smith'}) "
            "MERGE (r)-[:SUPERVISED_BY]->(a)")
        # nurse_adams ASSIGNED_TO_UNIT CCU
        session.run(
            "MATCH (n:Nurse {id:'nurse_adams'}),(u:Unit {name:'CCU'}) "
            "MERGE (n)-[:ASSIGNED_TO_UNIT]->(u)")
        # Patients ADMITTED_TO units
        for unit, ids in admitted.items():
            session.run(
                "MATCH (u:Unit {name:$unit}) UNWIND $ids AS pid "
                "MATCH (p:Patient {id:pid}) MERGE (p)-[:ADMITTED_TO]->(u)",
                unit=unit, ids=ids)

        # Specialties + REFERRED_TO
        session.run(
            "UNWIND $names AS nm MERGE (:Specialty {name:nm})", names=specialties)
        for ref in referrals:
            session.run(
                "MATCH (a:Physician {id:$src}),(b:Physician {id:$dst}) "
                "MERGE (a)-[:REFERRED_TO]->(b)", src=ref["src"], dst=ref["dst"])

        # SIMILAR_CASE between encounters
        for edge in similar_edges:
            session.run(
                "MATCH (a:Encounter {id:$a}),(b:Encounter {id:$b}) "
                "MERGE (a)-[s:SIMILAR_CASE]->(b) SET s.score=$score",
                a=edge["a"], b=edge["b"], score=edge["score"])

    driver.close()
    log(f"loaded Neo4j: physicians=3, nurse=1, units=2, "
        f"patients={len(patients)}, encounters={len(data['encounters'])}, "
        f"specialties={len(specialties)}, "
        f"TREATS={len(treats)}, CONSULTS_ON={len(consults)}, "
        f"SIMILAR_CASE={len(similar_edges)}")


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #

def main() -> int:
    log("=== healthcare seed pipeline starting ===")

    if not VOYAGE_API_KEY:
        log("FATAL: VOYAGE_API_KEY is empty. A Voyage key is required to compute "
            "embeddings. Set VOYAGE_API_KEY and re-run.")
        return 1

    # 1. CSV
    ensure_csv()

    # 2. parse
    rows = parse_csv()

    # 3. structured data
    data = generate(n=NUM_PATIENTS)
    log(f"generated structured data for {len(data['patients'])} patients "
        f"(cardiology cohort: {len(data['cardiology_ids'])})")

    # build note docs (linkage + Mongo routing)
    notes = build_notes(rows, data["patient_ids"], data["cardiology_ids"])

    # 4a. Postgres structured
    pg = connect_postgres()
    try:
        load_postgres_structured(pg, data)

        # 5. embeddings -> Postgres
        log(f"computing Voyage embeddings (model={VOYAGE_MODEL}, "
            f"batch={EMBED_BATCH}) for {len(notes)} notes ...")
        n_emb = load_postgres_embeddings(pg, notes)
    finally:
        pg.close()

    # 4b. Mongo
    mongo_counts = load_mongo(notes)

    # 4c. Neo4j
    load_neo4j(data, notes)

    # Summary
    log("=== SEED COMPLETE ===")
    log(f"  patients:            {len(data['patients'])}")
    log(f"  encounters:          {len(data['encounters'])}")
    log(f"  orders:              {len(data['orders'])}")
    log(f"  medications:         {len(data['medications'])}")
    log(f"  lab_results:         {len(data['lab_results'])}")
    log(f"  notes (Mongo):       {sum(mongo_counts.values())} {mongo_counts}")
    log(f"  embeddings (PG):     {n_emb}")
    log("  Neo4j graph:         loaded (see counts above)")
    return 0


if __name__ == "__main__":
    try:
        code = main()
    except Exception:  # noqa: BLE001
        print("[seed] FATAL: seeding failed", file=sys.stderr, flush=True)
        traceback.print_exc()
        code = 1
    sys.exit(code)
