"""A stand-in for OpenMRS that behaves like the parts of it this service actually touches.

It is not a fake in the "returns canned JSON" sense - it enforces the things the real thing
enforces, so the tests exercise the paths that matter:

* it refuses any call that does not carry a delegated token, and any token it cannot verify with
  agentgateway's public key;
* it refuses a write when the token's ``may_write`` claim is false, exactly as the audit filter's
  purpose/privilege gate does;
* it records every call it received, so a test can assert on what the assistant sent, in what
  order, and with what headers - which is the closest thing to asserting on the audit trail
  without a database.

Its FHIR capability statement is deliberately incomplete (no Appointment), so the tests can prove
that a task whose resource the deployment does not advertise is reported as unavailable rather
than attempted.
"""

from __future__ import annotations

import base64
import uuid
from typing import Any, Dict, List, Optional

import jwt
from cryptography.hazmat.primitives.serialization import load_der_public_key
from fastapi import FastAPI, Header, Request, Response
from fastapi.responses import JSONResponse

from app.openmrs_client import RELAY_PATH_PREFIX

# Imported lazily-ish: conftest sets the env this depends on before importing this module.
GENERATED_IDENTIFIER = "10023X"

CAPABILITY_STATEMENT = {
    "resourceType": "CapabilityStatement",
    "status": "active",
    "fhirVersion": "4.0.1",
    "rest": [
        {
            "mode": "server",
            "resource": [
                {
                    "type": "Patient",
                    "interaction": [
                        {"code": "read"},
                        {"code": "search-type"},
                        {"code": "create"},
                        {"code": "update"},
                        {"code": "delete"},
                    ],
                },
                {"type": "Encounter", "interaction": [{"code": "read"}, {"code": "search-type"}]},
                {"type": "Observation", "interaction": [{"code": "read"}, {"code": "search-type"}]},
                # Appointment is intentionally absent.
            ],
        }
    ],
}


def build_mock_openmrs(public_key_b64: str) -> FastAPI:
    app = FastAPI()

    state: Dict[str, Any] = {
        "patients": {},
        "calls": [],
        "generated_identifiers": [],
    }
    app.state.mock = state

    def _verify(token: Optional[str]) -> Optional[Dict[str, Any]]:
        if not token:
            return None
        try:
            return jwt.decode(
                token,
                load_der_public_key(base64.b64decode(public_key_b64)),
                algorithms=["RS256"],
                audience="clinical-agent-service",
                issuer="openmrs-agentgateway",
            )
        except jwt.InvalidTokenError:
            return None

    @app.middleware("http")
    async def relay(request: Request, call_next):
        """Strips agentgateway's relay prefix, the way the real audit filter does.

        The agent addresses every delegated call as
        ``/module/agentgateway/relay/ws/fhir2/R4/...`` because fhir2's own authentication filter
        owns ``/ws/fhir2/*`` on the real deployment and runs before agentgateway's. Emulating the
        strip here means the tests exercise the same paths the agent really sends, rather than
        passing against a URL shape production never sees.
        """
        if request.url.path.startswith(RELAY_PATH_PREFIX):
            stripped = request.url.path[len(RELAY_PATH_PREFIX) :]
            request.scope["path"] = stripped
            request.scope["raw_path"] = stripped.encode("ascii")
        return await call_next(request)

    @app.middleware("http")
    async def gate(request: Request, call_next):
        path = request.url.path
        if path.endswith("/metadata"):
            return await call_next(request)

        claims = _verify(request.headers.get("X-OpenMRS-Agent-Token"))
        if claims is None:
            return JSONResponse({"error": {"message": "Not authenticated"}}, status_code=401)

        is_write = request.method not in ("GET", "HEAD", "OPTIONS")
        if is_write and not claims.get("may_write"):
            return JSONResponse({"error": {"message": "Not permitted"}}, status_code=403)

        state["calls"].append(
            {
                "method": request.method,
                "path": path,
                "query": str(request.url.query),
                "user": claims.get("sub"),
                "task": request.headers.get("X-OpenMRS-Agent-Task"),
                "conversation": request.headers.get("X-OpenMRS-Agent-Conversation"),
                "has_prompt_header": "X-OpenMRS-Agent-Prompt" in request.headers,
            }
        )
        return await call_next(request)

    @app.post("/ws/rest/v1/idgen/identifiersource/{source_uuid}/identifier")
    async def generate_identifier(source_uuid: str) -> Dict[str, Any]:
        """idgen reserving the next patient identifier.

        Real identifier types validate a check digit, so the assistant cannot invent a value - it
        has to ask for one and then create the patient with it. That two-call shape is the thing
        worth testing, so the mock implements it rather than letting the tests pretend a patient can
        be created out of nothing.
        """
        state["generated_identifiers"].append(source_uuid)
        return {"identifier": GENERATED_IDENTIFIER}

    @app.post("/ws/rest/v1/patient")
    async def create_patient_rest(body: Dict[str, Any]) -> Response:
        """Creating a patient the way the assistant really does it - through webservices.rest.

        Not FHIR: fhir2 1.2.2 calls setUuid(resource.getId()) unconditionally on the patient, the
        name and the identifier, and a FHIR create carries no ids, so every uuid lands null and the
        insert is refused. This endpoint enforces the three things OpenMRS itself insists on, so a
        regression shows up here rather than as a 500 in a hospital.
        """
        person = body.get("person") or {}
        names = person.get("names") or []
        if not names or not names[0].get("familyName"):
            return JSONResponse({"error": {"message": "person.names[0].familyName is required"}}, status_code=400)
        if not person.get("birthdate"):
            return JSONResponse({"error": {"message": "person.birthdate is required"}}, status_code=400)
        if person.get("gender") not in ("M", "F", "U"):
            return JSONResponse({"error": {"message": "person.gender must be M, F or U"}}, status_code=400)

        identifiers = body.get("identifiers") or []
        if not identifiers or not identifiers[0].get("identifier"):
            return JSONResponse({"error": {"message": "at least one identifier is required"}}, status_code=400)
        if not identifiers[0].get("identifierType"):
            return JSONResponse({"error": {"message": "identifierType is required"}}, status_code=400)
        # "Identifier Location cannot be null for <value>" is OpenMRS's own wording when the type's
        # location behaviour is REQUIRED, which it is for a stock OpenMRS ID.
        if not identifiers[0].get("location"):
            value = identifiers[0]["identifier"]
            return JSONResponse(
                {"error": {"message": f"Identifier Location cannot be null for {value}"}}, status_code=400
            )

        patient_id = str(uuid.uuid4())
        given = names[0].get("givenName") or ""
        stored = {
            "resourceType": "Patient",
            "id": patient_id,
            "name": [{"family": names[0]["familyName"], "given": [given] if given else []}],
            "gender": {"M": "male", "F": "female"}.get(person["gender"], "unknown"),
            "birthDate": person["birthdate"],
            "identifier": [{"value": identifiers[0]["identifier"]}],
            "meta": {"lastUpdated": "2026-08-15T09:00:00.000+01:00"},
            # Kept so tests can assert on what was actually sent for the identifier.
            "_rest_identifier": identifiers[0],
        }
        state["patients"][patient_id] = stored
        return JSONResponse({"uuid": patient_id, "display": f"{identifiers[0]['identifier']} - {given} {names[0]['familyName']}"}, status_code=201)

    @app.post("/ws/rest/v1/person/{person_id}/attribute")
    async def create_person_attribute(person_id: str, body: Dict[str, Any]) -> Response:
        """A patient's first phone number - webservices.rest, not FHIR.

        A FHIR PUT that adds a telecom entry with no id reproduces Finding 10's translator bug
        (setUuid(getId()) unconditional); one that carries an existing entry's id is silently
        dropped instead (fhir2 1.2.2 builds a fresh PersonAttribute from every incoming
        ContactPoint, and a Hibernate Set treats same-uuid as already-present). Neither works, so
        this - like create_patient - goes through webservices.rest, verified directly against the
        real deployment to actually persist.
        """
        patient = state["patients"].get(person_id)
        if patient is None:
            return JSONResponse({"error": {"message": "Person not found"}}, status_code=404)
        attribute_id = str(uuid.uuid4())
        telecom = list(patient.get("telecom") or [])
        telecom.append({"id": attribute_id, "value": body.get("value")})
        patient["telecom"] = telecom
        return JSONResponse({"uuid": attribute_id, "value": body.get("value")}, status_code=201)

    @app.post("/ws/rest/v1/person/{person_id}/attribute/{attribute_id}")
    async def update_person_attribute(person_id: str, attribute_id: str, body: Dict[str, Any]) -> Response:
        patient = state["patients"].get(person_id)
        if patient is None:
            return JSONResponse({"error": {"message": "Person not found"}}, status_code=404)
        telecom = list(patient.get("telecom") or [])
        for entry in telecom:
            if entry.get("id") == attribute_id:
                entry["value"] = body.get("value")
                break
        else:
            return JSONResponse({"error": {"message": "Attribute not found"}}, status_code=404)
        patient["telecom"] = telecom
        return JSONResponse({"uuid": attribute_id, "value": body.get("value")})

    @app.post("/ws/rest/v1/person/{person_id}/name")
    async def create_person_name(person_id: str, body: Dict[str, Any]) -> Response:
        patient = state["patients"].get(person_id)
        if patient is None:
            return JSONResponse({"error": {"message": "Person not found"}}, status_code=404)
        name_id = str(uuid.uuid4())
        given = body.get("givenName")
        names = list(patient.get("name") or [])
        names.append({"id": name_id, "family": body.get("familyName"), "given": [given] if given else []})
        patient["name"] = names
        return JSONResponse({"uuid": name_id}, status_code=201)

    @app.post("/ws/rest/v1/person/{person_id}/name/{name_id}")
    async def update_person_name(person_id: str, name_id: str, body: Dict[str, Any]) -> Response:
        """A patient's existing name - webservices.rest, not FHIR, for the same reason as the
        attribute endpoints above: a FHIR PUT with the existing PersonName's id is silently
        dropped rather than applied (PersonNameTranslatorImpl - one of the three translators
        Finding 10 already named - is fed a fresh `PersonName()` per incoming entry, not the
        managed one)."""
        patient = state["patients"].get(person_id)
        if patient is None:
            return JSONResponse({"error": {"message": "Person not found"}}, status_code=404)
        names = list(patient.get("name") or [])
        for entry in names:
            if entry.get("id") == name_id:
                if "familyName" in body:
                    entry["family"] = body["familyName"]
                if "givenName" in body:
                    entry["given"] = [body["givenName"]] if body["givenName"] else []
                break
        else:
            return JSONResponse({"error": {"message": "Name not found"}}, status_code=404)
        patient["name"] = names
        return JSONResponse({"uuid": name_id})

    @app.get("/ws/fhir2/R4/metadata")
    async def metadata() -> Dict[str, Any]:
        return CAPABILITY_STATEMENT

    @app.get("/ws/fhir2/R4/Patient")
    async def search_patients(name: str = "", identifier: str = "", gender: str = "") -> Dict[str, Any]:
        # Matches on any name part, the way FHIR's `name` search parameter does - "Amine Benali"
        # has to find a patient recorded as family "Benali", given "Amine".
        if identifier:
            # An identifier search is exact, which is the whole reason the assistant falls back to
            # one when a name is ambiguous.
            matches = [
                patient
                for patient in state["patients"].values()
                if any(
                    (ident.get("value") or "").upper() == identifier.upper()
                    for ident in (patient.get("identifier") or [])
                )
            ]
        elif name:
            needle_parts = [part for part in name.lower().split() if part]
            matches = [
                patient
                for patient in state["patients"].values()
                if needle_parts and all(part in _label(patient).lower() for part in needle_parts)
            ]
        else:
            # Neither a name nor an identifier: a list, filtered only by whatever else was given
            # (list_patients sends nothing but `gender`, when the clinician asked for one).
            matches = list(state["patients"].values())
        if gender:
            matches = [patient for patient in matches if patient.get("gender") == gender]
        return {
            "resourceType": "Bundle",
            "type": "searchset",
            "total": len(matches),
            "entry": [{"resource": patient} for patient in matches],
        }

    @app.get("/ws/fhir2/R4/Patient/{patient_id}")
    async def read_patient(patient_id: str) -> Response:
        patient = state["patients"].get(patient_id)
        if patient is None:
            return JSONResponse({"error": {"message": "Patient not found"}}, status_code=404)
        return JSONResponse(patient)

    @app.post("/ws/fhir2/R4/Patient")
    async def create_patient(body: Dict[str, Any]) -> Response:
        if not body.get("name") or not body.get("birthDate"):
            return JSONResponse(
                {"issue": [{"details": {"text": "name and birthDate are required"}}]}, status_code=400
            )

        # fhir2 resolves which PatientIdentifierType to use from identifier.type.text and nothing
        # else - FhirPatientServiceImpl.getPatientIdentifierTypeByIdentifier ignores
        # identifier.system entirely - and refuses the whole resource when it cannot resolve one.
        # Enforced here because a mock that accepted a patient with no identifier type is precisely
        # what let this ship broken.
        identifiers = body.get("identifier") or []
        if not identifiers:
            return JSONResponse(
                {"issue": [{"details": {"text": "at least one identifier is required"}}]}, status_code=400
            )
        if not (identifiers[0].get("type") or {}).get("text"):
            return JSONResponse(
                {"issue": [{"details": {"text": "identifier.type.text is required to resolve the type"}}]},
                status_code=400,
            )

        # "OpenMRS ID" has location behaviour REQUIRED, and OpenMRS refuses the patient with
        # "Identifier Location cannot be null for <value>" when it is absent. FHIR has no field for
        # it: fhir2 reads it from its own extension, so a resource that looks perfectly valid is
        # still rejected. Enforced here so the extension cannot be dropped unnoticed.
        extensions = identifiers[0].get("extension") or []
        has_location = any(
            ext.get("url") == "http://fhir.openmrs.org/ext/patient/identifier#location"
            and (ext.get("valueReference") or {}).get("reference")
            for ext in extensions
        )
        if not has_location:
            value = identifiers[0].get("value")
            return JSONResponse(
                {"issue": [{"details": {"text": f"Identifier Location cannot be null for {value}"}}]},
                status_code=400,
            )
        patient_id = str(uuid.uuid4())
        created = dict(body)
        created["id"] = patient_id
        created["meta"] = {"lastUpdated": "2026-08-15T09:00:00.000+01:00"}
        state["patients"][patient_id] = created
        return JSONResponse(created, status_code=201)

    @app.put("/ws/fhir2/R4/Patient/{patient_id}")
    async def update_patient(patient_id: str, body: Dict[str, Any]) -> Response:
        if patient_id not in state["patients"]:
            return JSONResponse({"error": {"message": "Patient not found"}}, status_code=404)
        updated = dict(body)
        updated["id"] = patient_id
        state["patients"][patient_id] = updated
        return JSONResponse(updated)

    @app.get("/ws/fhir2/R4/Encounter")
    async def search_encounters(patient: str = "") -> Dict[str, Any]:
        return {"resourceType": "Bundle", "type": "searchset", "total": 0, "entry": []}

    return app


def _label(patient: Dict[str, Any]) -> str:
    names = patient.get("name") or []
    if not names:
        return ""
    first = names[0]
    return f"{first.get('family', '')} {' '.join(first.get('given') or [])}".strip()


def seed_patient(
    app: FastAPI, family: str, given: List[str], birth_date: str, identifier: Optional[str] = None
) -> str:
    patient_id = str(uuid.uuid4())
    record: Dict[str, Any] = {
        "resourceType": "Patient",
        "id": patient_id,
        "name": [{"use": "official", "family": family, "given": given}],
        "gender": "male",
        "birthDate": birth_date,
        "meta": {"lastUpdated": "2026-08-01T09:00:00.000+01:00"},
    }
    if identifier:
        record["identifier"] = [{"value": identifier, "use": "official"}]
    app.state.mock["patients"][patient_id] = record
    return patient_id
