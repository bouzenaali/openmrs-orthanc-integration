"""The tools themselves: FHIR R4 for what OpenMRS covers, module-native REST for what it does not.

That split is ADR-10. FHIR is the right shape for demographics, encounters, observations and
appointments, and OpenMRS already exposes them. It has no shape for the neurosurgery record's own
entities - GCS, Karnofsky, the neurological exam - because those were never modelled as OpenMRS
Concepts or Obs to begin with. Forcing them into FHIR extensions to keep one uniform converter
would be speculative work with no consumer; the honest arrangement is two families of tools,
picked by task.

Every summary here is written for a clinician to read and approve. It says what will change, in
words, before anything is sent - that summary *is* the confirmation gate (CA5, ADR-2).
"""

from __future__ import annotations

import os
from typing import Any, Dict, List

from ..nlu.base import (
    TASK_BOOK_APPOINTMENT,
    TASK_CREATE_PATIENT,
    TASK_GET_PATIENT_SUMMARY,
    TASK_LIST_PATIENTS,
    TASK_RECORD_NEURO_ASSESSMENT,
    TASK_SEARCH_PATIENT,
    TASK_UPDATE_PATIENT,
)
from ..nlu.rules import identifier_shaped
from .registry import PlannedOperation, ToolRegistry, ToolSpec

# Creating a patient in OpenMRS needs an identifier of a type the installation knows about.
# Configured rather than guessed: identifier types are a per-deployment decision.
#
# The *type* is what matters, not the system. fhir2 1.2.2 resolves which PatientIdentifierType to
# use from `identifier.type.text` alone - `FhirPatientServiceImpl.getPatientIdentifierTypeByIdentifier`
# never looks at `identifier.system` - and returns null when that text is blank, which fails the
# whole conversion. So a deployment that sets only the system creates nothing, however correct the
# URI. The system is still sent when configured, because it is proper FHIR and later fhir2 versions
# do consult a system-to-type mapping table.
PATIENT_IDENTIFIER_TYPE = os.environ.get("OPENMRS_PATIENT_IDENTIFIER_TYPE", "")
PATIENT_IDENTIFIER_SYSTEM = os.environ.get("OPENMRS_PATIENT_IDENTIFIER_SYSTEM", "")

# An identifier source to draw a new identifier from when the clinician does not dictate one.
# Needed because the usual types validate their check digit: an invented value like "12345" is
# rejected, so the assistant cannot simply make one up. Empty means "always require the clinician
# to give the identifier".
IDGEN_SOURCE_UUID = os.environ.get("OPENMRS_IDGEN_SOURCE_UUID", "")

# The location an identifier is assigned at. Required whenever the identifier type's location
# behaviour is REQUIRED, which it is for a stock "OpenMRS ID": without it OpenMRS refuses the
# patient with "Identifier Location cannot be null for <identifier>".
#
# FHIR has no field for this, so fhir2 reads it from its own extension - see
# PatientIdentifierTranslatorImpl, which resolves the reference through the location DAO and calls
# setLocation. Nothing else in the resource will do it.
IDENTIFIER_LOCATION_EXTENSION = "http://fhir.openmrs.org/ext/patient/identifier#location"
IDENTIFIER_LOCATION_UUID = os.environ.get("OPENMRS_IDENTIFIER_LOCATION_UUID", "")

# Creating a patient goes through webservices.rest rather than FHIR, against ADR-10's default of
# "FHIR where OpenMRS exposes it". Three separate defects in the deployed fhir2 1.2.2 make its
# create path unusable, all found on this deployment:
#
#   1. the identifier type is resolved from identifier.type.text only, never identifier.system;
#   2. the assignment location has no FHIR field at all and needs a proprietary extension;
#   3. PatientTranslatorImpl, PersonNameTranslatorImpl and PatientIdentifierTranslatorImpl each
#      call setUuid(resource.getId()) *unconditionally* - and a FHIR create carries no ids by
#      definition, so every uuid becomes null and MySQL rejects the insert with
#      "Column 'uuid' cannot be null".
#
# The third is not configurable around: the client would have to invent ids for the patient, the
# name and the identifier, and FHIR forbids sending an id on create. webservices.rest takes the
# same three values as ordinary named fields.
#
# Reads and updates stay on FHIR. Update is unaffected by (3) because it sends back a resource that
# was fetched, so the ids are already there - which is exactly why searching worked from day one and
# only creating failed.
CREATE_VIA_REST = True

# webservices.rest identifies the type by uuid. From the same deployment:
#   identifierType={uuid=05a29f94-c0ed-11e2-94be-8c13b969e334, display=OpenMRS ID}
PATIENT_IDENTIFIER_TYPE_UUID = os.environ.get("OPENMRS_PATIENT_IDENTIFIER_TYPE_UUID", "")

# Only used when a phone number is dictated. OpenMRS stores it as a person attribute, and the
# attribute type is a per-deployment uuid ("Telephone Number" on a stock install).
PHONE_ATTRIBUTE_TYPE_UUID = os.environ.get("OPENMRS_PHONE_ATTRIBUTE_TYPE_UUID", "")

APPOINTMENT_SERVICE_REFERENCE = os.environ.get("OPENMRS_APPOINTMENT_SERVICE", "")


def _identifier_block(value: str) -> Dict[str, Any]:
    """One FHIR identifier, carrying the two things fhir2 actually reads.

    The type text resolves which PatientIdentifierType to use; the location extension satisfies
    identifier types whose location behaviour is REQUIRED. Both are invisible in plain FHIR and
    both are refusals rather than warnings when missing.
    """
    identifier: Dict[str, Any] = {"value": value, "use": "official"}
    if PATIENT_IDENTIFIER_TYPE:
        identifier["type"] = {"text": PATIENT_IDENTIFIER_TYPE}
    if PATIENT_IDENTIFIER_SYSTEM:
        identifier["system"] = PATIENT_IDENTIFIER_SYSTEM
    if IDENTIFIER_LOCATION_UUID:
        identifier["extension"] = [
            {
                "url": IDENTIFIER_LOCATION_EXTENSION,
                "valueReference": {"reference": f"Location/{IDENTIFIER_LOCATION_UUID}"},
            }
        ]
    return identifier


def _split_name(full_name: str) -> Dict[str, Any]:
    """Family name last, everything before it given - the convention the paper form uses."""
    parts = [part for part in full_name.strip().split() if part]
    if len(parts) == 1:
        return {"family": parts[0], "given": []}
    return {"family": parts[-1], "given": parts[:-1]}


def _gender(value: str) -> str:
    return {"M": "male", "F": "female"}.get(value, "unknown")


def _readable_gender(value: str) -> str:
    return {"M": "masculin", "F": "feminin"}.get(value, "non precise")


# --------------------------------------------------------------------------- read tools


def _build_search_patient(slots: Dict[str, Any], context: Dict[str, Any]) -> List[PlannedOperation]:
    # A bare token shaped like an OpenMRS identifier (short, no spaces, digits present - "10002T")
    # is searched as one, even when the extractor or the model put it in `name`: FHIR's `name`
    # parameter cannot match an identifier, so "cherche le patient 10002T" searched name=10002T
    # and reported the patient as unknown. `_resolve_patient` already applies this; this tool
    # builds its own request independently and did not.
    identifier = slots.get("identifier") or identifier_shaped(slots.get("name"))
    if identifier:
        query = f"identifier={identifier}"
    else:
        query = f"name={slots['name']}"
    return [
        PlannedOperation(
            method="GET",
            path=f"/ws/fhir2/R4/Patient?{query}&_count=10",
            summary=f"Rechercher les patients correspondant a « {slots.get('identifier') or slots.get('name')} »",
        )
    ]


def _build_list_patients(slots: Dict[str, Any], context: Dict[str, Any]) -> List[PlannedOperation]:
    query = f"gender={_gender(slots['gender'])}" if slots.get("gender") else ""
    path = "/ws/fhir2/R4/Patient?_count=50" + (f"&{query}" if query else "")
    label = f" de sexe {_readable_gender(slots['gender'])}" if slots.get("gender") else ""
    return [PlannedOperation(method="GET", path=path, summary=f"Lister les patients{label}")]


def _build_patient_summary(slots: Dict[str, Any], context: Dict[str, Any]) -> List[PlannedOperation]:
    patient_uuid = context["patient_uuid"]
    return [
        PlannedOperation(
            method="GET",
            path=f"/ws/fhir2/R4/Patient/{patient_uuid}",
            summary="Lire la fiche administrative du patient",
        ),
        PlannedOperation(
            method="GET",
            path=f"/ws/fhir2/R4/Encounter?patient={patient_uuid}&_count=5&_sort=-date",
            summary="Lire les cinq derniers passages",
        ),
    ]


# --------------------------------------------------------------------------- write tools


def _rest_person(slots: Dict[str, Any]) -> Dict[str, Any]:
    name = _split_name(slots["name"])
    given = " ".join(name["given"]) if name["given"] else name["family"]
    person: Dict[str, Any] = {
        "names": [{"givenName": given, "familyName": name["family"], "preferred": True}],
        "gender": slots["gender"] if slots["gender"] in ("M", "F") else "U",
        "birthdate": slots["birthdate"],
    }
    if slots.get("phone"):
        person["attributes"] = [{"attributeType": PHONE_ATTRIBUTE_TYPE_UUID, "value": slots["phone"]}]
    return person


def _rest_identifier(value: str) -> Dict[str, Any]:
    identifier: Dict[str, Any] = {"identifier": value, "preferred": True}
    if PATIENT_IDENTIFIER_TYPE_UUID:
        identifier["identifierType"] = PATIENT_IDENTIFIER_TYPE_UUID
    elif PATIENT_IDENTIFIER_TYPE:
        identifier["identifierType"] = PATIENT_IDENTIFIER_TYPE
    if IDENTIFIER_LOCATION_UUID:
        identifier["location"] = IDENTIFIER_LOCATION_UUID
    return identifier


def _build_create_patient(slots: Dict[str, Any], context: Dict[str, Any]) -> List[PlannedOperation]:
    """Creates through `webservices.rest`, not FHIR - see the note on CREATE_VIA_REST."""
    person = _rest_person(slots)
    create_summary = f"Creer le dossier patient de {slots['name']}"
    create_path = "/ws/rest/v1/patient"

    if slots.get("identifier"):
        return [
            PlannedOperation(
                method="POST",
                path=create_path,
                body={"person": person, "identifiers": [_rest_identifier(slots["identifier"])]},
                summary=create_summary,
            )
        ]

    # No identifier dictated: reserve the next one from the configured source first, then create the
    # patient with it. Two calls rather than one because OpenMRS's identifier types validate a check
    # digit, so an invented value is refused and the assistant cannot compute a valid one.
    def with_generated_identifier(prior: List[Any]) -> Dict[str, Any]:
        generated = _first_generated_identifier(prior[-1] if prior else None)
        if not generated:
            raise ValueError("the identifier source returned no identifier")
        return {"person": person, "identifiers": [_rest_identifier(generated)]}

    return [
        PlannedOperation(
            method="POST",
            path=f"/ws/rest/v1/idgen/identifiersource/{IDGEN_SOURCE_UUID}/identifier",
            body={},
            summary="Reserver un nouvel identifiant patient",
        ),
        PlannedOperation(
            method="POST",
            path=create_path,
            body_from_results=with_generated_identifier,
            summary=create_summary,
        ),
    ]


def _first_generated_identifier(response: Any) -> Optional[str]:
    """Pulls the identifier out of idgen's reply, whose shape varies by version.

    Accepts a bare string, ``{"identifier": "..."}`` and ``{"identifiers": ["..."]}`` rather than
    assuming one: getting this wrong means a patient created with no identifier, which is worse than
    a clear failure.
    """
    if isinstance(response, str) and response.strip():
        return response.strip()
    if isinstance(response, dict):
        single = response.get("identifier")
        if isinstance(single, str) and single.strip():
            return single.strip()
        many = response.get("identifiers")
        if isinstance(many, list) and many and isinstance(many[0], str):
            return many[0].strip()
    return None


def _summarise_create_patient(slots: Dict[str, Any], context: Dict[str, Any]) -> str:
    lines = [
        "Je vais CREER un nouveau dossier patient avec les informations suivantes :",
        f"  - Nom : {slots['name']}",
        f"  - Sexe : {_readable_gender(slots['gender'])}",
        f"  - Date de naissance : {slots['birthdate']}",
    ]
    if slots.get("identifier"):
        lines.append(f"  - Identifiant : {slots['identifier']}")
    else:
        # Say so before the clinician confirms: the identifier is part of what is being created, and
        # they should not discover only afterwards that OpenMRS chose it.
        lines.append("  - Identifiant : attribue automatiquement par OpenMRS")
    if slots.get("phone"):
        lines.append(f"  - Telephone : {slots['phone']}")
    lines.append("Aucun dossier existant ne sera modifie. Confirmez-vous la creation ?")
    return "\n".join(lines)


def _build_update_patient(slots: Dict[str, Any], context: Dict[str, Any]) -> List[PlannedOperation]:
    """Targeted updates through `webservices.rest`, not FHIR - and this one took two attempts to get
    right, both wrong in the same way CREATE_VIA_REST already was: assuming FHIR PUT works for a
    sub-resource that already exists just because a plain create is what is documented as broken.

    Verified directly against the real deployment, not inferred: a FHIR PUT that replaces an
    existing telecom or name entry with a fresh dict - even one carrying that entry's own `id` -
    returns 200 and changes **nothing** in the database. Decompiling `PersonTranslatorImpl` (fhir2
    1.2.2) explains why: every incoming ContactPoint/HumanName is mapped to a *brand-new*
    `PersonAttribute`/`PersonName` object (`lambda$toOpenmrsType$0` calls `new PersonAttribute()`
    before translating into it), never the managed entity fhir2 itself just read. Hibernate holds
    that sub-collection as a `Set`; a new object carrying the same `uuid` reads as "already
    present" by whatever equality that `Set` uses and the value change is silently dropped - a
    quieter cousin of Finding 10's "Column 'uuid' cannot be null" (which is exactly what happens
    instead when the id is left off a brand-new entry). Either way, FHIR cannot change a value that
    is already there. `webservices.rest`'s own attribute/name sub-resources can, confirmed by
    reading the value back afterwards: `POST .../person/{uuid}/attribute/{attrUuid}` and
    `POST .../person/{uuid}/name/{nameUuid}` both persisted where the FHIR PUT silently had not.

    The patient's *current* resource (``context['current_patient']``, read via FHIR - reads are
    unaffected) supplies the existing attribute's or name's id, so an edit targets that specific
    row instead of guessing. No id means no such entry exists yet, and a POST to the parent
    collection creates one.
    """
    person_uuid = context["patient_uuid"]
    current = context["current_patient"]
    operations: List[PlannedOperation] = []

    if slots.get("phone"):
        existing = (current.get("telecom") or [{}])[0]
        attribute_id = existing.get("id")
        if attribute_id:
            operations.append(
                PlannedOperation(
                    method="POST",
                    path=f"/ws/rest/v1/person/{person_uuid}/attribute/{attribute_id}",
                    body={"value": slots["phone"]},
                    summary="Mettre a jour le numero de telephone",
                )
            )
        else:
            operations.append(
                PlannedOperation(
                    method="POST",
                    path=f"/ws/rest/v1/person/{person_uuid}/attribute",
                    body={"attributeType": PHONE_ATTRIBUTE_TYPE_UUID, "value": slots["phone"]},
                    summary="Ajouter un numero de telephone",
                )
            )

    if slots.get("name"):
        name = _split_name(slots["name"])
        given = " ".join(name["given"]) if name["given"] else name["family"]
        body = {"givenName": given, "familyName": name["family"]}
        existing_names = current.get("name") or []
        name_id = existing_names[0].get("id") if existing_names else None
        path = (
            f"/ws/rest/v1/person/{person_uuid}/name/{name_id}"
            if name_id
            else f"/ws/rest/v1/person/{person_uuid}/name"
        )
        operations.append(
            PlannedOperation(method="POST", path=path, body=body, summary="Mettre a jour le nom")
        )

    return operations


def _summarise_update_patient(slots: Dict[str, Any], context: Dict[str, Any]) -> str:
    changes = []
    if slots.get("phone"):
        changes.append(f"  - Telephone : {slots['phone']}")
    if slots.get("name"):
        changes.append(f"  - Nom : {slots['name']}")
    return "\n".join(
        ["Je vais MODIFIER la fiche du patient " + context.get("patient_label", "") + " :"]
        + changes
        + ["Les autres champs resteront inchanges. Confirmez-vous la modification ?"]
    )


def _build_book_appointment(slots: Dict[str, Any], context: Dict[str, Any]) -> List[PlannedOperation]:
    start = f"{slots['dates'][0]}T{slots.get('time', '09:00')}:00"
    body: Dict[str, Any] = {
        "resourceType": "Appointment",
        "status": "booked",
        "start": start,
        "participant": [
            {
                "actor": {"reference": f"Patient/{context['patient_uuid']}"},
                "status": "accepted",
            }
        ],
    }
    if APPOINTMENT_SERVICE_REFERENCE:
        body["serviceType"] = [{"text": APPOINTMENT_SERVICE_REFERENCE}]
    return [
        PlannedOperation(
            method="POST",
            path="/ws/fhir2/R4/Appointment",
            body=body,
            summary=f"Programmer un rendez-vous le {slots['dates'][0]} a {slots.get('time', '09:00')}",
        )
    ]


def _summarise_book_appointment(slots: Dict[str, Any], context: Dict[str, Any]) -> str:
    return (
        "Je vais PROGRAMMER un rendez-vous :\n"
        f"  - Patient : {context.get('patient_label', context['patient_uuid'])}\n"
        f"  - Date : {slots['dates'][0]}\n"
        f"  - Heure : {slots.get('time', '09:00')}\n"
        "Confirmez-vous ?"
    )


def _build_neuro_assessment(slots: Dict[str, Any], context: Dict[str, Any]) -> List[PlannedOperation]:
    body = {
        "patient": context["patient_uuid"],
        "eyeResponse": slots.get("eye_response"),
        "verbalResponse": slots.get("verbal_response"),
        "motorResponse": slots.get("motor_response"),
        "gcsTotal": slots.get("gcs_total"),
        "karnofskyScore": slots.get("karnofsky"),
    }
    return [
        PlannedOperation(
            method="POST",
            path="/ws/rest/v1/patientview/neuroassessment",
            body={key: value for key, value in body.items() if value is not None},
            summary="Enregistrer un nouveau score neurologique",
        )
    ]


def _summarise_neuro_assessment(slots: Dict[str, Any], context: Dict[str, Any]) -> str:
    lines = ["Je vais ENREGISTRER un score neurologique pour "
             + context.get("patient_label", context["patient_uuid"]) + " :"]
    if slots.get("gcs_total") is not None:
        lines.append(f"  - Glasgow (total) : {slots['gcs_total']}")
    for label, key in (("Ouverture des yeux", "eye_response"), ("Reponse verbale", "verbal_response"),
                       ("Reponse motrice", "motor_response")):
        if slots.get(key) is not None:
            lines.append(f"  - {label} : {slots[key]}")
    if slots.get("karnofsky") is not None:
        lines.append(f"  - Karnofsky : {slots['karnofsky']}")
    lines.append("Cet enregistrement s'ajoute a l'historique, il n'en remplace aucun. Confirmez-vous ?")
    return "\n".join(lines)


# --------------------------------------------------------------------------- the catalog

TOOLS = [
    ToolSpec(
        name="search_patient",
        task=TASK_SEARCH_PATIENT,
        writes=False,
        description="Rechercher un patient par nom ou identifiant",
        required_slots=("name",),
        slot_questions={"name": "Quel est le nom du patient a rechercher ?"},
        fhir_resource="Patient",
        fhir_interaction="search-type",
        expected_privilege="Get Patients",
        build=_build_search_patient,
        summarise=lambda slots, context: "",
    ),
    ToolSpec(
        name="list_patients",
        task=TASK_LIST_PATIENTS,
        writes=False,
        description="Lister ou filtrer les patients, par exemple par sexe",
        fhir_resource="Patient",
        fhir_interaction="search-type",
        expected_privilege="Get Patients",
        build=_build_list_patients,
        summarise=lambda slots, context: "",
    ),
    ToolSpec(
        name="get_patient_summary",
        task=TASK_GET_PATIENT_SUMMARY,
        writes=False,
        description="Afficher la fiche d'un patient et ses derniers passages",
        requires_patient=True,
        fhir_resource="Patient",
        fhir_interaction="read",
        expected_privilege="Get Patients",
        build=_build_patient_summary,
        summarise=lambda slots, context: "",
    ),
    ToolSpec(
        name="create_patient",
        task=TASK_CREATE_PATIENT,
        writes=True,
        description="Creer un nouveau dossier patient",
        # The identifier is required only when this deployment has no identifier source to draw one
        # from. With a source configured the assistant reserves one itself; without, it has to ask,
        # because the usual identifier types validate a check digit and an invented value is
        # refused. Asking is the honest failure - inventing one would create a patient that cannot
        # be found again.
        required_slots=("name", "gender", "birthdate") if IDGEN_SOURCE_UUID
        else ("name", "gender", "birthdate", "identifier"),
        slot_questions={
            "name": "Quel est le nom complet du patient ?",
            "gender": "Quel est le sexe du patient (masculin ou feminin) ?",
            "birthdate": "Quelle est la date de naissance du patient (JJ/MM/AAAA) ?",
            "identifier": "Quel identifiant faut-il attribuer a ce patient ?",
        },
        # No fhir_resource: this tool targets webservices.rest, not FHIR (see CREATE_VIA_REST).
        # Its availability therefore is not read from the FHIR capability statement - webservices.rest
        # is a hard dependency of the module and always present where the assistant runs at all.
        expected_privilege="Add Patients",
        build=_build_create_patient,
        summarise=_summarise_create_patient,
    ),
    ToolSpec(
        name="update_patient_demographics",
        task=TASK_UPDATE_PATIENT,
        writes=True,
        description="Mettre a jour les informations administratives d'un patient",
        requires_patient=True,
        # No fhir_resource: targets webservices.rest, not FHIR (see the note on _build_update_patient
        # - a FHIR PUT cannot actually change an existing telecom or name value on this deployment).
        # Availability therefore is not read from the FHIR capability statement, matching
        # create_patient's own reasoning: webservices.rest is a hard dependency of the module and
        # always present where the assistant runs at all. Reading the patient to resolve who this is
        # about (`requires_patient`) still goes through FHIR - only the write moved.
        expected_privilege="Edit Patients",
        build=_build_update_patient,
        summarise=_summarise_update_patient,
    ),
    ToolSpec(
        name="book_appointment",
        task=TASK_BOOK_APPOINTMENT,
        writes=True,
        description="Programmer un rendez-vous",
        required_slots=("dates",),
        slot_questions={"dates": "A quelle date souhaitez-vous programmer le rendez-vous (JJ/MM/AAAA) ?"},
        requires_patient=True,
        fhir_resource="Appointment",
        fhir_interaction="create",
        expected_privilege="Manage Appointments",
        build=_build_book_appointment,
        summarise=_summarise_book_appointment,
    ),
    ToolSpec(
        name="record_neuro_assessment",
        task=TASK_RECORD_NEURO_ASSESSMENT,
        writes=True,
        description="Enregistrer un score de Glasgow ou de Karnofsky dans le dossier de neurochirurgie",
        requires_patient=True,
        needs_patientview=True,
        expected_privilege="App: patientview.neurosurgeryDashboard.manage",
        build=_build_neuro_assessment,
        summarise=_summarise_neuro_assessment,
    ),
]


def build_registry(patientview_enabled: bool) -> ToolRegistry:
    return ToolRegistry(TOOLS, patientview_enabled=patientview_enabled)
