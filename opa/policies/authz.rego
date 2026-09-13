package healthcare.authz

import rego.v1

# =============================================================================
# Healthcare MCP authorization policy
# Decision entrypoint: data.healthcare.authz.decision  (see CONTRACT.md §5.2)
# Returns EXACTLY: allow, patient_id_filter, redact_fields, reason, decision_id
# =============================================================================

# ---- Single decision object (the only thing the service consumes) -----------
decision := {
	"allow": allow,
	"patient_id_filter": patient_id_filter,
	"redact_fields": redact_fields,
	"reason": reason,
	"decision_id": decision_id,
}

# ---- Constants / helpers ----------------------------------------------------

valid_roles := {"attending_physician", "resident", "nurse", "billing", "researcher", "patient"}

# authenticated user that carries a recognised role
has_role if input.user.role in valid_roles

# convenience accessors that never error on missing optional fields
user_units := object.get(input.user, "units", [])

user_protocol := object.get(input.user, "protocol", "")

max_list := object.get(data.config, "max_list", 50)

# set of patients a given provider TREATS or CONSULTS_ON (from data.care_team,
# which is populated both by the static bundle file and the runtime PUT)
care_set(provider) := {edge.patient |
	some edge in data.care_team
	edge.provider == provider
	edge.rel in {"TREATS", "CONSULTS_ON"}
}

# nurse: union of patients across every unit the nurse is assigned to
nurse_set contains p if {
	some unit in user_units
	some p in object.get(data.unit_patients, unit, [])
}

# researcher: patients enrolled in the researcher's protocol/cohort
researcher_set contains p if {
	some p in object.get(data.protocol_patients, user_protocol, [])
}

# ---- patient_id_filter (sorted array of ints; ["*"] sentinel for billing) ----

default patient_id_filter := []

patient_id_filter := sort([p | some p in care_set(input.user.id)]) if {
	input.user.role == "attending_physician"
}

patient_id_filter := sort([p | some p in care_set(supervisor)]) if {
	input.user.role == "resident"
	supervisor := object.get(data.supervision, input.user.id, "")
}

patient_id_filter := sort([p | some p in nurse_set]) if {
	input.user.role == "nurse"
}

patient_id_filter := sort([p | some p in researcher_set]) if {
	input.user.role == "researcher"
}

patient_id_filter := [to_number(input.user.patient_id)] if {
	input.user.role == "patient"
	object.get(input.user, "patient_id", null) != null
}

# billing sees every patient -> service treats a filter containing "*" as "all"
patient_id_filter := ["*"] if input.user.role == "billing"

filter_has_patients if count(patient_id_filter) > 0

# ---- list-size guard --------------------------------------------------------
# true = permitted. Fails only for oversized list queries.

default allow_list_action := true

allow_list_action := false if {
	input.context.query_type == "list"
	object.get(input.context, "requested_count", 0) > max_list
}

# ---- resource-specific allow rules -----------------------------------------

# patient records: allowed when the caller resolves to >=1 patient (billing's
# ["*"] counts) and the list guard passes.
allow_read_patient if {
	allow_list_action
	filter_has_patients
}

# clinical notes: same as patient, but billing has NO clinical note access.
allow_read_note if {
	input.user.role != "billing"
	allow_list_action
	filter_has_patients
}

# ---- top-level allow --------------------------------------------------------

default allow := false

allow if {
	input.resource_type == "patient"
	allow_read_patient
}

allow if {
	input.resource_type == "note"
	allow_read_note
}

allow if {
	input.resource_type == "care_team"
	has_role
	allow_list_action
}

allow if {
	input.resource_type == "audit"
	has_role
	allow_list_action
}

# ---- redaction --------------------------------------------------------------
# attending / resident / patient -> [] (via default)

default redact_fields := []

# researcher: always strip direct identifiers (cohort research, PII redaction)
redact_fields := ["name", "dob", "ssn", "address", "mrn"] if input.user.role == "researcher"

# billing: needs demographics + charges, so no demographic redaction
redact_fields := [] if input.user.role == "billing"

# nurse: least-privilege — no SSN / insurance / address (no billing data)
redact_fields := ["ssn", "insurance_id", "address"] if input.user.role == "nurse"

# ---- reason (ordered precedence; exactly one value via else chain) -----------

reason := sprintf("denied: list request exceeds max of %d", [max_list]) if {
	not allow_list_action
} else := "billing role has no clinical note access" if {
	input.resource_type == "note"
	input.user.role == "billing"
} else := "denied: no patients in access scope" if {
	not allow
} else := "billing demographics-only access" if {
	input.user.role == "billing"
} else := "attending physician access via TREATS/CONSULTS relationship" if {
	input.user.role == "attending_physician"
} else := sprintf("resident inherited access via supervisor %s (TREATS/CONSULTS)", [object.get(data.supervision, input.user.id, "unknown")]) if {
	input.user.role == "resident"
} else := sprintf("nurse unit-based access to units [%s]", [concat(", ", user_units)]) if {
	input.user.role == "nurse"
} else := sprintf("researcher cohort %s with PII redaction", [user_protocol]) if {
	input.user.role == "researcher"
} else := "patient self-access" if {
	input.user.role == "patient"
} else := "access decision"

# ---- decision id ------------------------------------------------------------
# Deterministic, always a string. uuid.rfc4122 is available in OPA v0.60+.

decision_id := uuid.rfc4122(json.marshal(input))
