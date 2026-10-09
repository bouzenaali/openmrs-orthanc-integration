"""Interpretation rules, including the ones that exist to stop the assistant acting on a report."""

from __future__ import annotations

import pytest

from app.nlu.base import (
    INTENT_CANCEL,
    INTENT_CONFIRM,
    INTENT_TASK,
    INTENT_UNSUPPORTED,
    TASK_BOOK_APPOINTMENT,
    TASK_CREATE_PATIENT,
    TASK_RECORD_NEURO_ASSESSMENT,
    TASK_SEARCH_PATIENT,
    TASK_UPDATE_PATIENT,
)
from app.nlu.rules import RuleBasedNlu, classify_answer, extract_slots, normalise, reads_as_description

nlu = RuleBasedNlu()


def test_accents_do_not_change_the_meaning_of_a_rule():
    assert normalise("Créer un patient") == "creer un patient"


@pytest.mark.parametrize(
    "prompt,expected",
    [
        ("Cree un nouveau patient", TASK_CREATE_PATIENT),
        ("Créer un dossier patient", TASK_CREATE_PATIENT),
        ("cherche le patient Benali", TASK_SEARCH_PATIENT),
        ("programme un rendez-vous le 12/09/2026", TASK_BOOK_APPOINTMENT),
        ("enregistre un GCS a 12", TASK_RECORD_NEURO_ASSESSMENT),
        ("mets a jour le telephone du patient", TASK_UPDATE_PATIENT),
    ],
)
def test_task_families_are_recognised(prompt, expected):
    interpretation = nlu.interpret(prompt, {})
    assert interpretation.task == expected
    assert interpretation.intent == INTENT_TASK


def test_an_unrecognised_request_asks_rather_than_guesses():
    interpretation = nlu.interpret("quelle est la meteo aujourd'hui", {})
    assert interpretation.intent == INTENT_UNSUPPORTED
    assert interpretation.needs_clarification


@pytest.mark.parametrize(
    "prompt",
    [
        "le GCS s'est aggrave a 6",
        "le glasgow semble etre a 7",
        "je crois que le karnofsky est a 40",
        "faut-il noter un GCS a 6 ?",
    ],
)
def test_a_report_is_never_read_as_an_instruction_to_write(prompt):
    """Section 0's worry, made concrete: describing a decline must not set a score."""
    interpretation = nlu.interpret(prompt, {})
    assert interpretation.needs_clarification, f"{prompt!r} was taken as an instruction"


def test_an_explicit_instruction_with_the_same_number_is_accepted():
    interpretation = nlu.interpret("enregistre un GCS a 6 pour ce patient", {})
    assert interpretation.task == TASK_RECORD_NEURO_ASSESSMENT
    assert not interpretation.needs_clarification
    assert interpretation.slots["gcs_total"] == 6


def test_a_hedged_lookup_is_still_a_lookup():
    """The hedging guard applies to writes only - a question is a normal way to ask for a read."""
    interpretation = nlu.interpret("peux-tu chercher le patient Benali ?", {})
    assert interpretation.task == TASK_SEARCH_PATIENT
    assert not interpretation.needs_clarification


def test_two_different_task_families_in_one_sentence_are_ambiguous():
    interpretation = nlu.interpret("cree un patient et programme un rendez-vous", {})
    assert interpretation.needs_clarification


def test_searching_and_summarising_are_not_treated_as_a_conflict():
    interpretation = nlu.interpret("affiche le dossier du patient Benali", {})
    assert not interpretation.needs_clarification


@pytest.mark.parametrize("prompt", ["oui", "je confirme", "OK", "d'accord", "confirmer"])
def test_confirmations_are_recognised(prompt):
    assert classify_answer(prompt) == INTENT_CONFIRM


@pytest.mark.parametrize("prompt", ["non", "annuler", "stop", "non, pas ca"])
def test_cancellations_are_recognised(prompt):
    assert classify_answer(prompt) == INTENT_CANCEL


def test_an_answer_containing_both_words_is_read_as_a_refusal():
    """The safe reading of an unclear answer to "shall I save this?" is no."""
    assert classify_answer("non, pas ok") == INTENT_CANCEL


def test_an_unrelated_sentence_is_not_an_answer():
    assert classify_answer("le patient est arrive ce matin") is None


class TestSlotExtraction:
    def test_name_after_a_keyword(self):
        assert extract_slots("cherche le patient Amine Benali")["name"] == "Amine Benali"

    def test_quoted_name_wins(self):
        assert extract_slots('cree un patient nomme "Fatima Zohra Cherif"')["name"] == "Fatima Zohra Cherif"

    def test_french_dates_become_iso(self):
        assert extract_slots("ne le 03/04/1978")["birthdate"] == "1978-04-03"

    def test_written_out_dates_become_iso(self):
        assert extract_slots("rendez-vous le 12 septembre 2026")["dates"] == ["2026-09-12"]

    def test_gender_words(self):
        assert extract_slots("un patient de sexe masculin")["gender"] == "M"
        assert extract_slots("une patiente, sexe feminin")["gender"] == "F"

    def test_time_of_day(self):
        assert extract_slots("rendez-vous a 14h30")["time"] == "14:30"

    def test_glasgow_components(self):
        slots = extract_slots("enregistre E3 V4 M5")
        assert (slots["eye_response"], slots["verbal_response"], slots["motor_response"]) == (3, 4, 5)

    def test_phone_numbers_are_normalised(self):
        assert extract_slots("son telephone est 0555 12 34 56")["phone"] == "0555123456"

    def test_nothing_is_invented_when_nothing_is_said(self):
        assert extract_slots("bonjour") == {}


# --- lowercase names, from the first live test ------------------------------------------
#
# "cherche le patient walter white" produced "Quel est le nom du patient a rechercher ?" against a
# real deployment, because the name pattern required capitalised words. Costing a turn to ask for
# something already on screen is the kind of friction that makes clinicians stop using the thing.


def test_a_lowercase_name_is_extracted():
    result = nlu.interpret("cherche le patient walter white", {})
    assert result.task == "search_patient"
    assert result.slots.get("name") == "walter white"
    assert not result.needs_clarification


def test_a_capitalised_name_still_wins():
    assert nlu.interpret("cherche le patient Walter White", {}).slots.get("name") == "Walter White"


def test_trailing_clinical_words_are_not_taken_as_part_of_the_name():
    result = nlu.interpret("cherche le patient walter white avec un gcs bas", {})
    assert result.slots.get("name") == "walter white"


def test_a_keyword_with_no_name_after_it_yields_no_name():
    result = nlu.interpret("cherche le patient", {})
    assert not result.slots.get("name")


def test_a_phone_number_is_found_across_an_intervening_name():
    """From the first live update attempt, which lost the number the clinician had given."""
    slots = extract_slots("mets a jour le telephone de Test Neurochir a 0555123456")
    assert slots.get("phone") == "0555123456"


# --- a date is not automatically a birth date --------------------------------------------


def test_an_appointment_date_is_not_recorded_as_a_birth_date():
    """Found by the evaluation harness. Every date used to become a birthdate, because both branches
    of the ternary that appeared to test for a birth cue returned the same value. On an update turn
    that could have overwritten a real patient's date of birth with an unrelated date."""
    slots = extract_slots("programme un rendez-vous pour Ahmed Ziani le 12/09/2026 a 10h")

    assert slots["dates"] == ["2026-09-12"]
    assert "birthdate" not in slots


@pytest.mark.parametrize(
    "prompt,expected",
    [
        ('cree un patient nomme "Ahmed Ziani", homme, ne le 07/11/1965', "1965-11-07"),
        ("inscris une patiente, Fatima Cherif, nee le 12/03/1980", "1980-03-12"),
        ("corrige la date de naissance de Benali, c'est le 03/04/1978", "1978-04-03"),
    ],
)
def test_an_explicit_birth_cue_still_fills_the_birth_date(prompt, expected):
    assert extract_slots(prompt).get("birthdate") == expected


# --- Finding 18: a trigger word is never part of the name ---------------------------------


@pytest.mark.parametrize(
    "prompt,expected",
    [
        # The live defect: created patient 1000C6 named "nomme rachid ghezal".
        ("Crée nouveau patient nommé rachid ghezal", "rachid ghezal"),
        ("cree un patient nomme Ahmed Ziani", "Ahmed Ziani"),
        ("retrouve le dossier de madame Ziani", "Ziani"),
        ("cherche le patient appelé Benali", "Benali"),
    ],
)
def test_the_trigger_word_is_not_captured_into_the_name(prompt, expected):
    assert extract_slots(prompt).get("name") == expected


def test_english_pronouns_are_not_patient_names():
    """The extractor searched OpenMRS for patients called "he" and "his"."""
    for prompt in ("cherche le patient he", "affiche le dossier du patient his"):
        assert not extract_slots(prompt).get("name")


# --- Finding 21: deletion is recognised so it can be refused by name ----------------------


@pytest.mark.parametrize(
    "prompt",
    ["Supprime le patient avec ID 1000C6", "supprime un patient", "efface ce dossier",
     "supprime tous les patients"],
)
def test_deletion_is_recognised(prompt):
    from app.nlu.rules import reads_as_deletion
    assert reads_as_deletion(prompt)


@pytest.mark.parametrize("prompt", ["cree un patient nomme Ahmed", "cherche Benali", "mets a jour le telephone"])
def test_ordinary_requests_are_not_read_as_deletion(prompt):
    from app.nlu.rules import reads_as_deletion
    assert not reads_as_deletion(prompt)
