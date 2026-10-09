"""The whole pipeline, over real HTTP, against an OpenMRS that enforces the same gates as the real one.

These are the tests that would catch a regression in the properties that matter clinically:
nothing is written without an explicit yes, a read-only clinician cannot cause a write however
they phrase it, a task the deployment does not support is reported rather than attempted, and
every call that does go out carries the clinician's identity and the audit headers.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from tests.conftest import CHANNEL_SECRET
from tests.mock_openmrs import seed_patient


@pytest.fixture
def client():
    from app.main import app

    with TestClient(app) as test_client:
        yield test_client


def say(client, mint, prompt, *, conversation_id="conv-1", may_write=True, username="dr.benali", patient_uuid=None):
    payload = {
        "conversation_id": conversation_id,
        "prompt": prompt,
        "delegated_token": mint(username=username, may_write=may_write, conversation_id=conversation_id),
        "context": {"patient_uuid": patient_uuid, "locale": "fr"} if patient_uuid else {"locale": "fr"},
    }
    return client.post("/chat", json=payload, headers={"X-Agent-Channel-Key": CHANNEL_SECRET})


# --------------------------------------------------------------------------- channel and token


def test_a_caller_without_the_channel_key_is_refused(client, mint):
    response = client.post(
        "/chat",
        json={"conversation_id": "c", "prompt": "bonjour", "delegated_token": mint()},
    )
    assert response.status_code == 403


def test_a_caller_with_a_bad_token_is_refused(client):
    response = client.post(
        "/chat",
        json={"conversation_id": "c", "prompt": "bonjour", "delegated_token": "not-a-token"},
        headers={"X-Agent-Channel-Key": CHANNEL_SECRET},
    )
    assert response.status_code == 401


# --------------------------------------------------------------------------- reads


def test_a_lookup_runs_immediately_without_a_confirmation_step(client, mint, mock_state, openmrs_server):
    """CA4: read-only tasks answer directly."""
    seed_patient(openmrs_server["app"], "Benali", ["Amine"], "1978-04-03")

    response = say(client, mint, "cherche le patient Benali")
    body = response.json()

    assert response.status_code == 200
    assert body["state"] == "answered"
    assert "Benali" in body["reply"]
    assert body["pending_action"] is None
    assert [call["method"] for call in mock_state["calls"]] == ["GET"]


def test_every_call_carries_the_clinician_and_the_audit_headers(client, mint, mock_state, openmrs_server):
    seed_patient(openmrs_server["app"], "Benali", ["Amine"], "1978-04-03")

    say(client, mint, "cherche le patient Benali", username="dr.hamdi")

    call = mock_state["calls"][0]
    assert call["user"] == "dr.hamdi"
    assert call["task"] == "search_patient"
    assert call["conversation"] == "conv-1"
    assert call["has_prompt_header"] is True


def test_a_lookup_with_no_match_says_so_plainly(client, mint, mock_state):
    body = say(client, mint, "cherche le patient Inexistant").json()
    assert body["state"] == "answered"
    assert "Aucun patient" in body["reply"]


# --------------------------------------------------------------------------- the confirmation gate


def test_a_create_is_summarised_and_waits_for_a_yes(client, mint, mock_state):
    """CA5 / ADR-2: nothing is written on the turn that asks for it, however clear it was."""
    body = say(
        client, mint, 'cree un patient nomme "Amine Benali", sexe masculin, ne le 03/04/1978'
    ).json()

    assert body["state"] == "awaiting_confirmation"
    assert "CREER" in body["reply"]
    assert "Amine Benali" in body["reply"]
    assert "1978-04-03" in body["reply"]
    assert body["pending_action"]["operations"][0]["method"] == "POST"
    # The decisive assertion: a summary exists and nothing has been written. The reads that did
    # happen are the duplicate check, which is exactly what should precede a create.
    assert [call for call in mock_state["calls"] if call["method"] != "GET"] == []


def test_confirming_performs_the_write(client, mint, mock_state, openmrs_server):
    say(client, mint, 'cree un patient nomme "Amine Benali", sexe masculin, ne le 03/04/1978')
    body = say(client, mint, "oui, je confirme").json()

    assert body["state"] == "answered"
    assert "cree" in body["reply"].lower()
    assert any(call["method"] == "POST" for call in mock_state["calls"])
    assert len(openmrs_server["app"].state.mock["patients"]) == 1


def test_refusing_writes_nothing(client, mint, mock_state, openmrs_server):
    say(client, mint, 'cree un patient nomme "Amine Benali", sexe masculin, ne le 03/04/1978')
    body = say(client, mint, "non, annuler").json()

    assert body["state"] == "cancelled"
    assert not [call for call in mock_state["calls"] if call["method"] == "POST"]
    assert openmrs_server["app"].state.mock["patients"] == {}


def test_an_unclear_answer_keeps_waiting_rather_than_assuming_yes(client, mint, mock_state):
    say(client, mint, 'cree un patient nomme "Amine Benali", sexe masculin, ne le 03/04/1978')
    body = say(client, mint, "le patient est arrive ce matin").json()

    assert body["state"] == "awaiting_confirmation"
    assert not [call for call in mock_state["calls"] if call["method"] == "POST"]


def test_a_pending_action_cannot_be_confirmed_by_a_different_clinician(client, mint, mock_state):
    """A guessed conversation id must not let somebody else approve a colleague's write."""
    say(client, mint, 'cree un patient nomme "Amine Benali", sexe masculin, ne le 03/04/1978',
        username="dr.benali")
    body = say(client, mint, "oui, je confirme", username="dr.autre").json()

    assert body["state"] != "answered"
    assert not [call for call in mock_state["calls"] if call["method"] == "POST"]


# --------------------------------------------------------------------------- privilege


def test_a_read_only_clinician_cannot_get_a_write_planned_at_all(client, mint, mock_state):
    """The extra chat-write gate, checked before a summary is even produced."""
    body = say(
        client,
        mint,
        'cree un patient nomme "Amine Benali", sexe masculin, ne le 03/04/1978',
        may_write=False,
    ).json()

    assert body["state"] == "failed"
    assert "consultations" in body["reply"]
    assert body["pending_action"] is None
    assert mock_state["calls"] == []


def test_a_privilege_revoked_mid_conversation_stops_the_pending_write(client, mint, mock_state):
    """The summary was produced while the clinician could write; the yes arrives after they cannot."""
    say(client, mint, 'cree un patient nomme "Amine Benali", sexe masculin, ne le 03/04/1978',
        may_write=True)
    body = say(client, mint, "oui, je confirme", may_write=False).json()

    assert body["state"] == "failed"
    assert not [call for call in mock_state["calls"] if call["method"] == "POST"]


def test_openmrs_refusing_a_write_is_reported_in_plain_language(client, mint, mock_state, key_pair, monkeypatch):
    """CA8: a 403 from OpenMRS becomes an actionable sentence, not a status code."""
    from app import orchestrator as orchestrator_module

    say(client, mint, 'cree un patient nomme "Amine Benali", sexe masculin, ne le 03/04/1978')

    # Force the write to be rejected by OpenMRS itself, the way a missing "Add Patients"
    # privilege would, while the chat-level gate still allows the attempt.
    original = orchestrator_module.OpenmrsClient

    class RefusingClient(original):  # type: ignore[misc,valid-type]
        async def call(self, method, path, body=None):
            if method == "POST":
                return orchestrator_module.ApiResult(status=403, body={"error": {"message": "Privilege required"}})
            return await super().call(method, path, body)

    monkeypatch.setattr(orchestrator_module, "OpenmrsClient", RefusingClient)
    body = say(client, mint, "oui, je confirme").json()

    assert body["state"] == "failed"
    assert "droits" in body["reply"]
    assert "403" not in body["reply"]


# --------------------------------------------------------------------------- clarification loop


def test_a_missing_field_is_asked_for_specifically(client, mint, mock_state):
    body = say(client, mint, "cree un patient").json()
    assert body["state"] == "awaiting_clarification"
    assert "nom" in body["reply"].lower()
    assert mock_state["calls"] == []


def test_the_answer_to_a_question_completes_the_original_request(client, mint, mock_state):
    """CA3 loops back into the pipeline rather than restarting the conversation."""
    say(client, mint, "cree un patient")
    say(client, mint, "Amine Benali")
    say(client, mint, "sexe masculin")
    body = say(client, mint, "ne le 03/04/1978").json()

    assert body["state"] == "awaiting_confirmation"
    assert "Amine Benali" in body["reply"]


def test_an_out_of_scope_request_lists_what_is_possible(client, mint, mock_state):
    body = say(client, mint, "quelle est la meteo a Blida").json()
    assert body["state"] == "unsupported"
    assert "rechercher un patient" in body["reply"]
    assert mock_state["calls"] == []


# --------------------------------------------------------------------------- capability gating


def test_a_task_the_deployment_does_not_support_is_declared_not_attempted(client, mint, mock_state, openmrs_server):
    """The mock's capability statement has no Appointment, exactly as an older fhir2 would not."""
    patient_id = seed_patient(openmrs_server["app"], "Benali", ["Amine"], "1978-04-03")

    body = say(client, mint, "programme un rendez-vous le 12/09/2026 a 14h30",
               patient_uuid=patient_id).json()

    assert body["state"] == "unsupported"
    assert "Appointment" in body["reply"]
    assert not [call for call in mock_state["calls"] if call["method"] == "POST"]


def test_the_capability_endpoint_explains_each_tool(client):
    response = client.get("/capabilities", headers={"X-Agent-Channel-Key": CHANNEL_SECRET})
    assert response.status_code == 200

    tools = {tool["name"]: tool for tool in response.json()["tools"]}
    assert tools["search_patient"]["available"] is True
    assert tools["book_appointment"]["available"] is False
    assert "Appointment" in tools["book_appointment"]["reason"]
    # The neurosurgery-specific family stays off until patientview exposes REST resources (4.3).
    assert tools["record_neuro_assessment"]["available"] is False


def test_the_capability_endpoint_needs_the_channel_key(client):
    assert client.get("/capabilities").status_code == 403


# --------------------------------------------------------------------------- updates


def test_a_patient_named_in_the_turn_wins_over_the_open_chart(client, mint, openmrs_server):
    """Otherwise a question about one patient gets answered about whoever's chart is open."""
    open_chart = seed_patient(openmrs_server["app"], "Benali", ["Amine"], "1978-04-03")
    seed_patient(openmrs_server["app"], "Cherif", ["Fatima"], "1990-01-20")

    body = say(client, mint, "affiche le dossier de la patiente Cherif", patient_uuid=open_chart).json()

    assert body["state"] == "answered"
    assert "Cherif" in body["reply"]
    assert "Benali" not in body["reply"]


def test_the_open_chart_is_used_when_no_patient_is_named(client, mint, openmrs_server):
    patient_id = seed_patient(openmrs_server["app"], "Benali", ["Amine"], "1978-04-03")

    body = say(client, mint, "affiche le dossier", patient_uuid=patient_id).json()

    assert body["state"] == "answered"
    assert "Benali" in body["reply"]


def test_an_update_preserves_the_fields_nobody_mentioned(client, mint, mock_state, openmrs_server):
    """A partial instruction must never be turned into a full overwrite."""
    patient_id = seed_patient(openmrs_server["app"], "Benali", ["Amine"], "1978-04-03")

    say(client, mint, "mets a jour le telephone du patient, tel 0555 12 34 56", patient_uuid=patient_id)
    body = say(client, mint, "oui").json()

    assert body["state"] == "answered"
    stored = openmrs_server["app"].state.mock["patients"][patient_id]
    assert stored["birthDate"] == "1978-04-03"
    assert stored["name"][0]["family"] == "Benali"
    assert stored["telecom"][0]["value"] == "0555123456"


# --------------------------------------------------------------------------- duplicates


def test_possible_duplicates_are_surfaced_before_a_create_is_confirmed(client, mint, openmrs_server):
    seed_patient(openmrs_server["app"], "Benali", ["Amine"], "1978-04-03")

    body = say(client, mint, 'cree un patient nomme "Amine Benali", sexe masculin, ne le 03/04/1978').json()

    assert body["state"] == "awaiting_confirmation"
    assert "ATTENTION" in body["reply"]
    assert "doublon" in body["reply"]


# --- creating a patient reserves an identifier first (Finding 8) -------------------------


def test_a_create_reserves_an_identifier_and_uses_it(client, mint, mock_state, openmrs_server):
    """The identifier is drawn from idgen, not invented, and carries the type fhir2 needs.

    Two things this pins. fhir2 resolves the PatientIdentifierType from ``identifier.type.text``
    alone and refuses the resource without it, so a create that omits it fails no matter how correct
    the rest is. And OpenMRS's usual identifier types validate a check digit, so the assistant
    cannot make a value up - it has to ask for one.
    """
    say(client, mint, 'cree un patient nomme "Amine Benali", sexe masculin, ne le 03/04/1978')
    body = say(client, mint, "oui, je confirme").json()

    assert body["state"] == "answered"
    assert mock_state["generated_identifiers"], "no identifier was reserved from idgen"

    created = list(openmrs_server["app"].state.mock["patients"].values())[-1]
    sent = created["_rest_identifier"]
    assert sent["identifier"] == "10023X"
    assert sent["identifierType"] == "05a29f94-c0ed-11e2-94be-8c13b969e334"


def test_the_summary_says_the_identifier_will_be_assigned(client, mint):
    """The clinician approves the identifier being chosen for them, rather than discovering it after."""
    body = say(client, mint, 'cree un patient nomme "Amine Benali", sexe masculin, ne le 03/04/1978').json()

    assert body["state"] == "awaiting_confirmation"
    assert "automatiquement" in body["reply"]


def test_the_created_identifier_carries_its_assignment_location(client, mint, openmrs_server):
    """OpenMRS refuses "Identifier Location cannot be null" when the type's location behaviour is REQUIRED.

    Pinned because the failure names the identifier rather than the missing location, which makes it
    read as an idgen problem.
    """
    say(client, mint, 'cree un patient nomme "Amine Benali", sexe masculin, ne le 03/04/1978')
    assert say(client, mint, "oui").json()["state"] == "answered"

    created = list(openmrs_server["app"].state.mock["patients"].values())[-1]
    assert created["_rest_identifier"]["location"] == "99999999-8888-7777-6666-555555555555"


def test_the_success_message_names_the_created_patient(client, mint):
    """webservices.rest answers with {uuid, display}, not a FHIR Patient.

    Read as FHIR it has no name, so the reply said "Le dossier a ete cree : (sans nom)" for a record
    that had in fact been created correctly - alarming for something irreversible-looking, and it
    withheld the identifier the clinician needs to find the patient again.
    """
    say(client, mint, 'cree un patient nomme "Amine Benali", sexe masculin, ne le 03/04/1978')
    reply = say(client, mint, "oui").json()["reply"]

    assert "sans nom" not in reply
    assert "10023X" in reply


# --- the clarification loop must be escapable (from the first live update attempt) --------


def test_answering_with_an_identifier_escapes_an_ambiguous_name(client, mint, openmrs_server):
    """Two patients share the name given in the sentence; the identifier given in answer was
    discarded and the same question came back forever. The answer now replaces the ambiguous name
    instead of losing to it.

    Both patients are named "Test Neurochir" on purpose: the extractor now resolves "<field> de
    <name>" (Finding 31), so a sentence naming only one *distinct* Test Neurochir would resolve in
    one turn and never reach the ambiguity this test is about.
    """
    seed_patient(openmrs_server["app"], "Test", ["Neurochir"], "2003-02-05", identifier="10001V")
    seed_patient(openmrs_server["app"], "Test", ["Neurochir"], "1980-03-15", identifier="10007F")

    # The live sequence, turn for turn.
    first = say(client, mint, "mets a jour le telephone de Test Neurochir a 0555123456").json()
    assert first["state"] == "awaiting_clarification"
    assert "Plusieurs patients" in first["reply"]

    second = say(client, mint, "10007F").json()
    # The identifier must win over the ambiguous name. Anything but the same question again.
    assert "Plusieurs patients" not in second["reply"]
    assert second["state"] in ("awaiting_confirmation", "awaiting_clarification")


def test_the_first_patient_question_accepts_an_identifier(client, mint, openmrs_server):
    """"Donnez son nom ou son identifiant" has to mean it - answering with one used to search by name."""
    seed_patient(openmrs_server["app"], "Test", ["Neurochir"], "1980-03-15", identifier="10007F")

    say(client, mint, "mets a jour le telephone de Test Neurochir a 0555123456")
    reply = say(client, mint, "10007F").json()

    assert "Aucun patient" not in reply["reply"]
    assert "De quel patient" not in reply["reply"]


# --- Findings 19, 20, 21 and the identifier-as-name defect --------------------------------


def test_deletion_is_refused_by_name_not_by_incomprehension(client, mint):
    """Finding 21. "Je n'ai pas compris" told the clinician a perfectly clear sentence was garbled."""
    body = say(client, mint, "Supprime le patient avec ID 1000C6").json()

    assert body["state"] == "unsupported"
    assert "supprimer" in body["reply"].lower()
    assert "pas compris" not in body["reply"].lower()


def test_a_new_command_is_not_absorbed_into_a_pending_question(client, mint):
    """Finding 19. "supprime tous les patients" was typed while a create was half-finished and came
    back as the same "what is the patient's sex?" question - never seen as a request at all."""
    first = say(client, mint, 'cree un patient nomme "Test Un"').json()
    assert first["state"] == "awaiting_clarification"

    second = say(client, mint, "supprime tous les patients").json()

    assert second["reply"] != first["reply"], "the pending question was repeated verbatim"
    assert "supprimer" in second["reply"].lower()


def test_an_unrelated_reply_abandons_the_pending_request_out_loud(client, mint):
    """Option (b): abandoning is announced. Silently swallowing a typed command is the worse failure."""
    say(client, mint, 'cree un patient nomme "Test Deux"')
    body = say(client, mint, "commande une pizza").json()

    assert "abandonne" in body["reply"].lower()


def test_an_unavailable_task_is_refused_before_any_question(client, mint):
    """Finding 20. The interpreter's question was returned before availability was checked, so the
    assistant asked for an appointment date and only then refused the booking it can never make."""
    body = say(client, mint, "programme un rendez-vous pour Ahmed Ziani demain a 10h").json()

    assert body["state"] == "unsupported"
    assert "Appointment" in body["reply"] or "ne peux pas effectuer" in body["reply"]


def test_an_identifier_is_searched_as_an_identifier_not_a_name(client, mint, mock_state, openmrs_server):
    """The clinician wrote "du patient 1000C6"; the query went out as name=1000C6, which a FHIR name
    search can never match, and the record was reported as non-existent."""
    seed_patient(openmrs_server["app"], "Ghezal", ["Rachid"], "1999-06-15", identifier="1000C6")

    say(client, mint, "mets a jour le telephone du patient 1000C6 a 0555123456")

    searches = [c for c in mock_state["calls"] if c["method"] == "GET" and "Patient" in c["path"]]
    assert searches, "no patient search was issued"
    assert any("identifier=1000C6" in c["query"] for c in searches), \
        f"searched by name instead of identifier: {[c['query'] for c in searches]}"
