"""Faker-based structured data generator for the healthcare RAG demo.

Produces patient / encounter / order / medication / lab records for patient ids
10001..10050, consistent with the OPA cohort and Neo4j graph defined in the
CONTRACT (§3, §5, §7, §9).

Importable by seed.py:  `from generate_structured import generate`
`generate(n=50)` returns a dict of lists of plain dicts (JSON-serialisable),
ready to be inserted into Postgres and mirrored into Neo4j.
"""

from __future__ import annotations

import random
from datetime import datetime, timedelta

from faker import Faker

# --- Fixed demo coordinates (must stay consistent with the CONTRACT) ----------

FIRST_PATIENT_ID = 10001
# Patients 10001..10022 are the cardiology cohort (== OPA researcher cohort).
CARDIOLOGY_MAX_ID = 10022

# Neo4j / OPA admission map (CONTRACT §9): these patients are ADMITTED_TO a unit.
ADMIT_UNITS = {
    10001: "CCU",
    10002: "CCU",
    10010: "CCU",
    10003: "ICU-3",
    10004: "ICU-3",
}

# Non-cardiology departments (a realistic mix).
OTHER_DEPARTMENTS = [
    "pulmonology",
    "radiology",
    "oncology",
    "orthopedics",
    "gastroenterology",
    "nephrology",
    "neurology",
    "general_medicine",
]

# Ordering providers — ids match Keycloak usernames / Neo4j provider ids.
PROVIDERS = ["dr_smith", "dr_jones", "dr_lee", "dr_adams", "dr_patel"]

WARD_UNITS = ["WARD-A", "WARD-B", "WARD-C", "TELE-1", "STEP-DOWN"]

CARDIAC_COMPLAINTS = [
    "Chest pain", "Shortness of breath", "Palpitations", "Syncope",
    "Acute coronary syndrome", "Congestive heart failure exacerbation",
    "Atrial fibrillation with RVR", "Hypertensive urgency",
]
GENERAL_COMPLAINTS = [
    "Abdominal pain", "Fever", "Cough", "Fall", "Altered mental status",
    "Nausea and vomiting", "Back pain", "Dyspnea", "Weakness", "Dizziness",
]

CARDIAC_MEDS = [
    ("Metoprolol", "25 mg", "PO", "BID"),
    ("Atorvastatin", "40 mg", "PO", "daily"),
    ("Lisinopril", "10 mg", "PO", "daily"),
    ("Aspirin", "81 mg", "PO", "daily"),
    ("Furosemide", "40 mg", "IV", "BID"),
    ("Clopidogrel", "75 mg", "PO", "daily"),
    ("Heparin", "5000 units", "SC", "q8h"),
    ("Amiodarone", "200 mg", "PO", "daily"),
]
GENERAL_MEDS = [
    ("Acetaminophen", "650 mg", "PO", "q6h PRN"),
    ("Ondansetron", "4 mg", "IV", "q8h PRN"),
    ("Pantoprazole", "40 mg", "PO", "daily"),
    ("Ceftriaxone", "1 g", "IV", "daily"),
    ("Albuterol", "2.5 mg", "NEB", "q4h PRN"),
    ("Insulin glargine", "20 units", "SC", "nightly"),
    ("Enoxaparin", "40 mg", "SC", "daily"),
]

CARDIAC_LABS = [
    ("Troponin I", lambda r: f"{r.uniform(0.01, 3.5):.2f}", "ng/mL", "<0.04"),
    ("BNP", lambda r: str(r.randint(30, 1800)), "pg/mL", "<100"),
    ("CK-MB", lambda r: f"{r.uniform(0.5, 25):.1f}", "ng/mL", "0.0-5.0"),
    ("Potassium", lambda r: f"{r.uniform(3.0, 5.8):.1f}", "mmol/L", "3.5-5.1"),
]
GENERAL_LABS = [
    ("WBC", lambda r: f"{r.uniform(3.0, 18.0):.1f}", "10^3/uL", "4.0-11.0"),
    ("Hemoglobin", lambda r: f"{r.uniform(8.0, 17.0):.1f}", "g/dL", "12.0-16.0"),
    ("Creatinine", lambda r: f"{r.uniform(0.6, 3.2):.2f}", "mg/dL", "0.6-1.3"),
    ("Glucose", lambda r: str(r.randint(70, 320)), "mg/dL", "70-99"),
    ("Sodium", lambda r: str(r.randint(128, 148)), "mmol/L", "135-145"),
]

ORDER_TYPES = [
    ("imaging", ["Echocardiogram", "Chest X-ray", "CT angiography", "Cardiac MRI", "Coronary angiogram"]),
    ("lab", ["Lipid panel", "CBC with differential", "Basic metabolic panel", "Troponin series"]),
    ("procedure", ["12-lead ECG", "Stress test", "Cardiac catheterization", "Telemetry monitoring"]),
    ("consult", ["Cardiology consult", "Nutrition consult", "Physical therapy consult"]),
]


def _department_for(pid: int, rnd: random.Random) -> str:
    if pid <= CARDIOLOGY_MAX_ID:
        return "cardiology"
    return rnd.choice(OTHER_DEPARTMENTS)


def generate(n: int = 50, seed: int = 42) -> dict:
    """Generate structured records for `n` patients starting at id 10001.

    Returns dict with keys: patients, encounters, orders, medications,
    lab_results, plus helper lists patient_ids and cardiology_ids.
    """
    fake = Faker()
    Faker.seed(seed)
    rnd = random.Random(seed)

    patient_ids = list(range(FIRST_PATIENT_ID, FIRST_PATIENT_ID + n))
    cardiology_ids = [p for p in patient_ids if p <= CARDIOLOGY_MAX_ID]

    patients: list[dict] = []
    encounters: list[dict] = []
    orders: list[dict] = []
    medications: list[dict] = []
    lab_results: list[dict] = []

    order_id = 1
    med_id = 1
    lab_id = 1

    for pid in patient_ids:
        department = _department_for(pid, rnd)
        is_cardio = department == "cardiology"
        sex = rnd.choice(["M", "F"])
        first = fake.first_name_male() if sex == "M" else fake.first_name_female()
        name = f"{first} {fake.last_name()}"
        dob = fake.date_of_birth(minimum_age=30, maximum_age=89)

        patients.append({
            "id": pid,
            "mrn": f"MRN{pid:07d}",
            "name": name,
            "dob": dob.isoformat(),
            "sex": sex,
            "address": fake.address().replace("\n", ", "),
            "ssn": fake.ssn(),
            "insurance_id": f"{fake.bothify('???').upper()}-{fake.numerify('#########')}",
            "department": department,
        })

        # 1-3 encounters per patient; first encounter honours the admission map.
        n_enc = rnd.randint(1, 3)
        for seq in range(1, n_enc + 1):
            enc_id = pid * 10 + seq
            admit = fake.date_time_between(start_date="-2y", end_date="-2d")
            los_hours = rnd.randint(12, 24 * 9)
            discharge = admit + timedelta(hours=los_hours)

            if seq == 1 and pid in ADMIT_UNITS:
                unit = ADMIT_UNITS[pid]
            elif is_cardio and rnd.random() < 0.5:
                unit = rnd.choice(["CCU", "ICU-3", "TELE-1"])
            else:
                unit = rnd.choice(WARD_UNITS)

            complaint = (rnd.choice(CARDIAC_COMPLAINTS) if is_cardio
                         else rnd.choice(GENERAL_COMPLAINTS))

            encounters.append({
                "id": enc_id,
                "patient_id": pid,
                "admit_time": admit.isoformat(sep=" ", timespec="seconds"),
                "discharge_time": discharge.isoformat(sep=" ", timespec="seconds"),
                "unit_id": unit,
                "chief_complaint": complaint,
            })

            # Orders (1-3)
            for _ in range(rnd.randint(1, 3)):
                otype, details = rnd.choice(ORDER_TYPES)
                orders.append({
                    "id": order_id,
                    "encounter_id": enc_id,
                    "ordering_provider_id": rnd.choice(PROVIDERS),
                    "order_type": otype,
                    "order_details": rnd.choice(details),
                })
                order_id += 1

            # Medications (1-4)
            med_pool = CARDIAC_MEDS if is_cardio else GENERAL_MEDS
            for drug, dose, route, freq in rnd.sample(med_pool, rnd.randint(1, min(4, len(med_pool)))):
                medications.append({
                    "id": med_id,
                    "encounter_id": enc_id,
                    "drug": drug,
                    "dose": dose,
                    "route": route,
                    "frequency": freq,
                })
                med_id += 1

            # Labs (1-4)
            lab_pool = (CARDIAC_LABS + GENERAL_LABS) if is_cardio else GENERAL_LABS
            for lab_type, valfn, unit_, ref in rnd.sample(lab_pool, rnd.randint(1, min(4, len(lab_pool)))):
                taken = admit + timedelta(hours=rnd.randint(1, max(2, los_hours)))
                lab_results.append({
                    "id": lab_id,
                    "encounter_id": enc_id,
                    "lab_type": lab_type,
                    "value": valfn(rnd),
                    "unit": unit_,
                    "reference_range": ref,
                    "taken_at": taken.isoformat(sep=" ", timespec="seconds"),
                })
                lab_id += 1

    return {
        "patients": patients,
        "encounters": encounters,
        "orders": orders,
        "medications": medications,
        "lab_results": lab_results,
        "patient_ids": patient_ids,
        "cardiology_ids": cardiology_ids,
    }


if __name__ == "__main__":
    data = generate()
    for k, v in data.items():
        if isinstance(v, list):
            print(f"{k}: {len(v)}")
    print("sample patient:", data["patients"][0])
