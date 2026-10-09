"""The model interpreter, tested without a GPU.

A fake vLLM returns whatever a test wants, which is the only way to exercise the answers that matter:
the malicious-by-accident ones. A real model would give plausible answers most of the time, and the
cases worth pinning are the ones where it does not - a fabricated identifier, a task named that does
not exist, a description read as an instruction. Those have to be *chosen*, not waited for.

What is real here: the schema is generated from the actual tool registry, the safety post-checks are
the shipped ones, and the fallback path is the shipped one.
"""

from __future__ import annotations

import json

import httpx
import pytest

from app.nlu.base import INTENT_CONFIRM, INTENT_TASK, INTENT_UNSUPPORTED, TASK_CREATE_PATIENT, TASK_SEARCH_PATIENT
from app.nlu.medgemma import MedGemmaNlu
from app.nlu.schema import build_interpretation_schema
from app.tools.catalog import build_registry


@pytest.fixture
def registry():
    return build_registry(patientview_enabled=False)


def fake_vllm(answer=None, *, status=200, raw=None, fail=False):
    """A vLLM that returns one prepared interpretation."""

    def handler(request: httpx.Request) -> httpx.Response:
        if fail:
            raise httpx.ConnectError("connection refused", request=request)
        content = raw if raw is not None else json.dumps(answer)
        return httpx.Response(
            status,
            json={"choices": [{"message": {"content": content}}]},
        )

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def answer(intent=INTENT_TASK, task=None, slots=None, clarification=None):
    return {"intent": intent, "task": task, "slots": slots or {}, "clarification": clarification}


# --- the schema is the registry's, not a copy of it -------------------------------------


def test_the_schema_offers_exactly_the_registrys_tasks(registry):
    schema = build_interpretation_schema(registry)
    offered = set(schema["properties"]["task"]["enum"]) - {None}
    assert offered == {tool.task for tool in registry.all()}


def test_the_schema_forbids_unknown_slots(registry):
    schema = build_interpretation_schema(registry)
    assert schema["properties"]["slots"]["additionalProperties"] is False


# --- ordinary operation ------------------------------------------------------------------


@pytest.mark.asyncio
async def test_a_read_request_is_passed_through(registry):
    nlu = MedGemmaNlu(registry, client=fake_vllm(answer(task=TASK_SEARCH_PATIENT, slots={"name": "walter white"})))

    result = await nlu.ainterpret("cherche le patient walter white", {})

    assert result.task == TASK_SEARCH_PATIENT
    assert result.slots["name"] == "walter white"
    assert not result.needs_clarification


@pytest.mark.asyncio
async def test_a_yes_is_settled_without_calling_the_model(registry):
    """A confirmation is unambiguous and is the most common turn there is.

    Spending a GPU call and a second of latency on "oui" would make the assistant feel slower for no
    gain in understanding. The fake would raise if it were called.
    """
    nlu = MedGemmaNlu(registry, client=fake_vllm(fail=True))

    assert (await nlu.ainterpret("oui", {})).intent == INTENT_CONFIRM


# --- the two safety rules, enforced whatever the model said ------------------------------


@pytest.mark.asyncio
async def test_descriptive_phrasing_is_not_executed_even_when_the_model_says_it_is(registry):
    """The risk section 0 of the architecture names, with the model actively getting it wrong.

    "Le GCS s'est aggrave a 6" reports a course. Acting on it would put a number in a patient's
    record that nobody asked for.
    """
    nlu = MedGemmaNlu(
        registry,
        client=fake_vllm(answer(task="record_neuro_assessment", slots={"gcs": "6"})),
    )

    result = await nlu.ainterpret("Le GCS s'est aggrave a 6", {})

    assert result.needs_clarification
    assert "enregistre" in result.clarification.lower()


@pytest.mark.asyncio
async def test_a_fabricated_slot_is_dropped_rather_than_written(registry):
    """A hallucinated identifier or birth date is the most damaging thing this component can emit.

    The model is told not to invent values; this is what happens when it does anyway. Dropping the
    value turns a fabrication into a question, which is the failure the whole design prefers.
    """
    nlu = MedGemmaNlu(
        registry,
        client=fake_vllm(
            answer(
                task=TASK_CREATE_PATIENT,
                slots={"name": "Amine Benali", "identifier": "99999X", "birthdate": "1978-04-03"},
            )
        ),
    )

    result = await nlu.ainterpret('cree un patient nomme "Amine Benali", ne le 03/04/1978', {})

    assert result.slots.get("name") == "Amine Benali"
    assert result.slots.get("birthdate") == "1978-04-03"
    # Never appeared in the sentence.
    assert "identifier" not in result.slots


@pytest.mark.asyncio
async def test_slots_are_not_filtered_on_a_read(registry):
    """Filtering exists to protect writes. A search for a name the extractor cannot parse is
    harmless and should still work - that breadth is the reason for having a model at all."""
    nlu = MedGemmaNlu(
        registry,
        client=fake_vllm(answer(task=TASK_SEARCH_PATIENT, slots={"name": "Benali"})),
    )

    result = await nlu.ainterpret("le monsieur que j'ai vu hier, Benali je crois", {})

    assert result.slots.get("name") == "Benali"


# --- degradation -------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_an_unreachable_model_falls_back_to_the_rules(registry):
    """A stopped GPU must narrow the assistant's language understanding, not take the chat down."""
    nlu = MedGemmaNlu(registry, client=fake_vllm(fail=True))

    result = await nlu.ainterpret("cherche le patient walter white", {})

    assert result.task == TASK_SEARCH_PATIENT
    assert result.slots.get("name") == "walter white"


@pytest.mark.asyncio
async def test_unparseable_output_falls_back_to_the_rules(registry):
    nlu = MedGemmaNlu(registry, client=fake_vllm(raw="not json at all"))

    result = await nlu.ainterpret("cherche le patient walter white", {})

    assert result.task == TASK_SEARCH_PATIENT


@pytest.mark.asyncio
async def test_a_task_outside_the_registry_is_refused(registry):
    """Structured output should make this impossible. If it ever happens, the constraint is not doing
    what this design assumes, so the answer is discarded rather than trusted."""
    nlu = MedGemmaNlu(registry, client=fake_vllm(answer(task="delete_all_patients")))

    result = await nlu.ainterpret("supprime tous les patients", {})

    # Falls back to the rules, which have no such task either.
    assert result.task != "delete_all_patients"


@pytest.mark.asyncio
async def test_an_out_of_scope_request_is_reported_as_such(registry):
    nlu = MedGemmaNlu(
        registry,
        client=fake_vllm(answer(intent=INTENT_UNSUPPORTED, clarification="Je ne peux pas faire cela.")),
    )

    result = await nlu.ainterpret("commande une pizza", {})

    assert result.intent == INTENT_UNSUPPORTED


@pytest.mark.asyncio
async def test_the_sync_entry_point_uses_the_rules_rather_than_blocking(registry):
    """``interpret`` exists only to satisfy the protocol. A blocking GPU call on the event loop would
    stall every other conversation in the hospital, so the sync path deliberately does not make one."""
    nlu = MedGemmaNlu(registry, client=fake_vllm(fail=True))

    result = nlu.interpret("cherche le patient walter white", {})

    assert result.task == TASK_SEARCH_PATIENT


# --- what the first run against real weights taught us -----------------------------------


@pytest.mark.asyncio
async def test_a_restated_request_is_not_treated_as_a_question(registry):
    """Measured, not imagined: MedGemma answered "cherche le patient walter white" with
    clarification="Rechercher le patient Walter White." — a restatement, not a question. Honouring it
    turned every clear instruction into a needless clarifying turn and made the assistant unusable."""
    nlu = MedGemmaNlu(
        registry,
        client=fake_vllm(answer(task=TASK_SEARCH_PATIENT, slots={"name": "walter white"},
                                clarification="Rechercher le patient Walter White.")),
    )

    result = await nlu.ainterpret("cherche le patient walter white", {})

    assert result.task == TASK_SEARCH_PATIENT
    assert not result.needs_clarification


@pytest.mark.asyncio
async def test_a_real_question_is_still_honoured(registry):
    nlu = MedGemmaNlu(
        registry,
        client=fake_vllm(answer(task=TASK_CREATE_PATIENT, slots={"name": "Karim Saidi"},
                                clarification="Quel est le sexe du patient ?")),
    )

    result = await nlu.ainterpret('cree un patient nomme "Karim Saidi"', {})

    assert result.needs_clarification


@pytest.mark.asyncio
async def test_text_is_kept_when_there_is_no_task_to_act_on(registry):
    """With no task, the text is all there is - punctuation must not discard it."""
    nlu = MedGemmaNlu(
        registry,
        client=fake_vllm(answer(intent=INTENT_UNSUPPORTED,
                                clarification="Je ne peux pas traiter cette demande.")),
    )

    result = await nlu.ainterpret("commande une pizza", {})

    assert result.clarification == "Je ne peux pas traiter cette demande."


@pytest.mark.asyncio
async def test_a_question_for_values_the_sentence_already_gave_is_dropped(registry):
    """Measured against real weights: the model asked for the sex and birth date of a sentence that
    stated both. Making a clinician repeat themselves is the main thing that made the model unusable."""
    nlu = MedGemmaNlu(
        registry,
        client=fake_vllm(answer(
            task=TASK_CREATE_PATIENT,
            slots={"name": "Ahmed Ziani"},
            clarification="Quel est le sexe et la date de naissance du patient ?")),
    )

    result = await nlu.ainterpret('cree un patient nomme "Ahmed Ziani", homme, ne le 07/11/1965', {})

    assert not result.needs_clarification
    assert result.slots["gender"] == "M"
    assert result.slots["birthdate"] == "1965-11-07"


@pytest.mark.asyncio
async def test_a_question_survives_when_something_really_is_missing(registry):
    nlu = MedGemmaNlu(
        registry,
        client=fake_vllm(answer(
            task=TASK_CREATE_PATIENT, slots={"name": "Karim Saidi"},
            clarification="Quel est le sexe du patient ?")),
    )

    result = await nlu.ainterpret('cree un patient nomme "Karim Saidi"', {})

    assert result.needs_clarification


@pytest.mark.asyncio
async def test_a_coded_slot_cannot_be_inferred_by_the_model(registry):
    """gender="M" was invented from the first name "Ahmed" in a sentence about a GCS score, and a
    substring check waved it through because "m" occurs in almost any French sentence."""
    nlu = MedGemmaNlu(
        registry,
        client=fake_vllm(answer(task="record_neuro_assessment",
                                slots={"name": "Ahmed Ziani", "gender": "M"})),
    )

    result = await nlu.ainterpret("enregistre un GCS a 12 pour Ahmed Ziani", {})

    assert "gender" not in result.slots
    assert result.slots.get("gcs_total") == 12


@pytest.mark.asyncio
async def test_suppression_can_never_drop_a_descriptive_phrasing_question(registry):
    """The regression that made UNSAFE jump from 0 to 7.

    Redundant-question suppression used to run after the descriptive-phrasing check had declined to
    add a question (the model had already asked one), so it dropped that question and turned
    "le GCS s'est aggrave a 6" into a write plan - with the score filled in by the extractor, so every
    required slot looked present. Descriptive phrasing is now decided last and returns directly.
    """
    nlu = MedGemmaNlu(
        registry,
        client=fake_vllm(answer(
            task="record_neuro_assessment",
            slots={"name": "Ahmed Ziani"},
            clarification="Voulez-vous enregistrer ce score ?")),
    )

    result = await nlu.ainterpret("le GCS s'est aggrave a 6 pour Ahmed Ziani", {})

    assert result.needs_clarification, "a description was turned into a write plan"


@pytest.mark.asyncio
async def test_the_model_keeps_the_name_it_read(registry):
    """The extractor cuts names on word counts and swallows politeness: it produced
    "madame Ziani s'il" from "...de madame Ziani s'il vous plait" and overrode the model's "Ziani".
    Structured slots still defer to the extractor; names do not."""
    nlu = MedGemmaNlu(
        registry,
        client=fake_vllm(answer(task=TASK_SEARCH_PATIENT, slots={"name": "Ziani"})),
    )

    result = await nlu.ainterpret("retrouve moi le dossier de madame Ziani s'il vous plait", {})

    assert result.slots["name"] == "Ziani"


@pytest.mark.asyncio
async def test_a_structured_slot_still_defers_to_the_extractor(registry):
    nlu = MedGemmaNlu(
        registry,
        client=fake_vllm(answer(task=TASK_CREATE_PATIENT,
                                slots={"name": "Ahmed Ziani", "birthdate": "1965-01-01"})),
    )

    result = await nlu.ainterpret('cree un patient nomme "Ahmed Ziani", homme, ne le 07/11/1965', {})

    assert result.slots["birthdate"] == "1965-11-07"


@pytest.mark.asyncio
async def test_a_question_and_a_statement_get_different_replies(registry):
    """Finding 24. Both are correctly refused as writes, but "faut-il noter un GCS a 6 ?" was told
    "cette phrase decrit un etat" - which the clinician, having just asked a question, already knows."""
    statement = MedGemmaNlu(registry, client=fake_vllm(answer(task="record_neuro_assessment")))
    question = MedGemmaNlu(registry, client=fake_vllm(answer(task="record_neuro_assessment")))

    a = await statement.ainterpret("le GCS s'est aggrave a 6", {})
    b = await question.ainterpret("faut-il noter un GCS a 6 ?", {})

    assert a.needs_clarification and b.needs_clarification
    assert a.clarification != b.clarification
