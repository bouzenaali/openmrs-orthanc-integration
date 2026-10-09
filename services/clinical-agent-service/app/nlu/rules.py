"""Deterministic French/English interpretation of a clinician's turn (Phase 2 - no LLM).

Rules rather than a model, on purpose and only for now. Phase 2's whole point is to prove the
security and audit model end to end; doing that with an interpreter whose behaviour is fully
enumerable means a failing test points at a rule, not at a sampling temperature. Phase 3 swaps
this out for MedGemma behind the same interface.

Two behaviours here are not merely "phase 2 simplifications" and should survive that swap:

* **Descriptive phrasing is not an instruction.** "Le GCS s'est aggrave a 6" reports a course;
  "note un GCS a 6" requests a write. Anything hedged, reported or interrogative is sent back as
  a clarifying question instead of being acted on. This is the concrete risk the architecture
  document flags in section 0, and it belongs in whatever interpreter is in use.
* **Never guess between task families.** A turn matching two families is ambiguous, full stop.
"""

from __future__ import annotations

import re
import unicodedata
from typing import Any, Dict, List, Optional, Tuple

from .base import (
    INTENT_CANCEL,
    INTENT_CONFIRM,
    INTENT_TASK,
    INTENT_UNSUPPORTED,
    TASK_BOOK_APPOINTMENT,
    TASK_CREATE_PATIENT,
    TASK_GET_PATIENT_SUMMARY,
    TASK_LIST_PATIENTS,
    TASK_RECORD_NEURO_ASSESSMENT,
    TASK_SEARCH_PATIENT,
    TASK_UPDATE_PATIENT,
    Interpretation,
)

# --------------------------------------------------------------------------- text normalisation


def normalise(text: str) -> str:
    """Lower-cased and accent-stripped, so "creer" and "créer" are the same word to a rule."""
    decomposed = unicodedata.normalize("NFD", text.lower())
    return "".join(char for char in decomposed if unicodedata.category(char) != "Mn")


# --------------------------------------------------------------------------- yes / no


_CONFIRM_RE = re.compile(r"\b(oui|confirme[rz]?|je confirme|d'accord|daccord|ok|valide[rz]?|yes|confirm)\b")
_CANCEL_RE = re.compile(r"\b(non|annule[rz]?|annulation|stop|cancel|laisse tomber|abandonne[rz]?)\b")


def classify_answer(prompt: str) -> Optional[str]:
    """Reads a turn purely as an answer to a pending confirmation.

    Cancellation is checked first and wins any tie: "non, pas ok" contains a token from both
    lists, and the safe reading of an unclear answer to "shall I save this?" is no.
    """
    text = normalise(prompt)
    if _CANCEL_RE.search(text):
        return INTENT_CANCEL
    if _CONFIRM_RE.search(text):
        return INTENT_CONFIRM
    return None


# --------------------------------------------------------------------------- hedging

# Phrasings that report or speculate rather than instruct. Applied only to writes: a hedged
# question is a perfectly ordinary way to ask for a lookup.
_HEDGE_PATTERNS = [
    r"\bs'?est (aggrave|ameliore|degrade|deteriore)",
    r"\ba (l'air|l'aire)\b",
    r"\bsemble\b",
    r"\bpeut[- ]etre\b",
    r"\bje (crois|pense|dirais)\b",
    r"\bil me semble\b",
    r"\bapparemment\b",
    r"\bprobablement\b",
    r"\best passe a\b",
    r"\bseems\b",
    r"\bmaybe\b",
    r"\bi think\b",
]
_HEDGE_RE = re.compile("|".join(_HEDGE_PATTERNS))


def reads_as_description(prompt: str) -> bool:
    text = normalise(prompt).strip()
    if _HEDGE_RE.search(text):
        return True
    # A question mark on a write request ("faut-il noter un GCS a 6 ?") is the clinician asking,
    # not instructing.
    return text.endswith("?")


# --------------------------------------------------------------------------- task matching

_TASK_PATTERNS: List[Tuple[str, re.Pattern]] = [
    (
        TASK_CREATE_PATIENT,
        # The noun has to follow the verb almost immediately - only determiners may sit between
        # them. A looser window matched "enregistre un GCS a 6 pour ce patient" as a patient
        # creation, because the sentence happens to contain both a create verb and the word
        # "patient" a few words apart.
        re.compile(
            r"\b(cre(e|er|ez)|ajoute[rz]?|enregistre[rz]?|inscri[rs](e|ez)?|create|register|add)\b"
            r"(?:\s+(?:un|une|le|la|ce|cette|nouveau|nouvelle|nouveaux)){0,3}"
            r"\s+(patient|patiente|dossier)\b"
        ),
    ),
    (
        TASK_UPDATE_PATIENT,
        re.compile(
            r"\b(modifie[rz]?|met[s]? a jour|mettre a jour|mise a jour|corrige[rz]?|change[rz]?|update|correct)\b"
        ),
    ),
    (
        TASK_BOOK_APPOINTMENT,
        re.compile(r"\b(rendez[- ]vous|rdv|appointment|consultation)\b|\b(programme[rz]?|planifie[rz]?|book|schedule)\b"),
    ),
    (TASK_RECORD_NEURO_ASSESSMENT, re.compile(r"\b(gcs|glasgow|karnofsky)\b")),
    (
        TASK_GET_PATIENT_SUMMARY,
        re.compile(r"\b(dossier|resume|synthese|informations?|infos?|donnees|summary|show|affiche[rz]?)\b"),
    ),
    (
        TASK_SEARCH_PATIENT,
        re.compile(r"\b(cherche[rz]?|recherche[rz]?|trouve[rz]?|retrouve[rz]?|search|find|lookup|qui est)\b"),
    ),
    (
        TASK_LIST_PATIENTS,
        # "liste"/"donne moi" plus "tous les patients"/"toutes les patientes" - a request for several
        # records at once, not one named patient. Kept separate from search_patient because that tool
        # requires a name and would ask for one nobody meant to give (capability gap, not a bug).
        re.compile(
            r"\b(liste[rz]?|list)\b.{0,20}\bpatient"
            r"|\b(tous les patients|toutes les patientes|all patients)\b"
            r"|\bdonne[rz]?[\s-]moi\b.{0,20}\bpatient"
        ),
    ),
]

# Deletion is recognised on purpose, so it can be refused *by name*. Falling through to the generic
# "je n'ai pas compris" told the clinician the sentence was unintelligible when in fact it was
# perfectly clear and simply not offered - and, worse, an unrecognised turn is indistinguishable from
# an answer to a pending question, so "supprime tous les patients" was absorbed into a half-finished
# create instead of being answered at all.
_DELETE_RE = re.compile(r"\b(supprime[rz]?|efface[rz]?|retire[rz]?|enleve[rz]?|delete|remove)\b")

DELETION_REFUSAL = (
    "Je ne peux pas supprimer de dossier : l'assistant n'a aucune capacite de suppression. "
    "Un dossier se retire uniquement dans OpenMRS, par une personne habilitee."
)


def reads_as_deletion(prompt: str) -> bool:
    return bool(_DELETE_RE.search(normalise(prompt)))


WRITE_TASKS = {
    TASK_CREATE_PATIENT,
    TASK_UPDATE_PATIENT,
    TASK_BOOK_APPOINTMENT,
    TASK_RECORD_NEURO_ASSESSMENT,
}


def _match_tasks(text: str) -> List[str]:
    matched = [task for task, pattern in _TASK_PATTERNS if pattern.search(text)]
    if TASK_LIST_PATIENTS in matched and _DELETE_RE.search(text):
        # "supprime tous les patients" carries the list vocabulary too ("tous les patients") but is
        # a deletion request, not a listing one. Deletion is refused outright (reads_as_deletion is
        # checked before interpretation ever runs) and must never be read as an ordinary task.
        matched = [task for task in matched if task != TASK_LIST_PATIENTS]
    return matched


def matches_a_task(prompt: str) -> bool:
    """Whether this sentence carries the vocabulary of a fresh instruction, any task family.

    Used by the orchestrator to tell a genuinely new request apart from a bare reply to a
    question it just asked ("Nadia Belkacem", "masculin", a bare date) - the model has no memory
    of the question between turns, and taking its confident-but-uninformed guess at face value
    was silently abandoning a half-finished request (Finding 30).
    """
    return bool(_match_tasks(normalise(prompt)))


# --------------------------------------------------------------------------- slot extraction

_NAME_AFTER_KEYWORD_RE = re.compile(
    r"(?:nomm[ée]{1,2}|appel[ée]{1,2}|patient(?:e)?|dossier de|dossier d'|de la patiente|du patient|pour"
    r"|telephone de|tel de|numero de|adresse de|nom de|naissance de)[,\s]+"
    r"((?:[A-ZÀ-Ý][\w'’\-]+)(?:\s+[A-ZÀ-Ý][\w'’\-]+)*)"
)
_QUOTED_RE = re.compile(r"[\"«“]([^\"»”]{2,60})[\"»”]")

# The same shape, but case-blind. Clinicians type "cherche le patient walter white" as often as they
# type it capitalised, and the capitalised-only form above silently found nothing - costing a whole
# extra turn to ask for a name that was already on screen. Case cannot be the thing that decides
# whether a name was given, so a lowercase run of words is accepted too, guarded by the stopword
# list below so that "le patient avec un GCS bas" does not yield a patient called "avec un".
_NAME_AFTER_KEYWORD_LOOSE_RE = re.compile(
    # Comma tolerance is deliberately *not* added here, unlike the capitalised regex above: a
    # comma after "patient" is at least as often a clause break as a name introduction - "le
    # telephone du patient, tel 0555 12 34 56" would otherwise capture "tel 0555 12" as a name.
    # The capitalised form carries its own evidence (a capital letter) that what follows really is
    # a name; a lower-case run of words after a comma does not, and is left unmatched instead.
    r"(?:nomm[ée]{1,2}|appel[ée]{1,2}|patient(?:e)?|dossier de|dossier d'|de la patiente|du patient|pour"
    r"|telephone de|tel de|numero de|adresse de|nom de|naissance de)\s+"
    r"((?:[\w'’\-]+)(?:\s+[\w'’\-]+){0,2})",
    re.IGNORECASE,
)

# Words that are never part of a name. Anything from here on is not the patient.
_NAME_STOPWORDS = {
    "le", "la", "les", "un", "une", "des", "du", "de", "d", "au", "aux", "et", "ou",
    "avec", "sans", "pour", "dans", "sur", "son", "sa", "ses", "qui", "que", "dont",
    "ne", "pas", "plus", "moins", "est", "sont", "a", "as", "ai", "il", "elle", "on",
    "gcs", "glasgow", "karnofsky", "dossier", "patient", "patiente", "rendez", "vous",
    "identifiant", "matricule", "id", "numero", "nom", "prenom", "ans", "an",
    "aujourd", "hui", "demain", "hier", "svp", "merci",
    # The trigger words themselves. `re.search` takes the *leftmost* trigger, so in "Cree nouveau
    # patient nomme rachid ghezal" it matches "patient" and then captures the next three words -
    # "nomme rachid ghezal". A patient was created in the live database called "nomme rachid ghezal".
    # A trigger word can never be part of the name that follows it.
    "nomme", "nommee", "appele", "appelee", "appelle", "sappelle", "monsieur", "madame",
    "mr", "mme", "mlle", "docteur", "dr",
    # English pronouns: the extractor searched OpenMRS for patients called "he" and "his".
    "he", "his", "him", "she", "her", "hers", "they", "them", "its",
    # Politeness that trails a name: "de madame Ziani s'il vous plait" yielded "Ziani s'il".
    "s'il", "sil", "vous", "plait", "please",
}
# What makes a date a *birth* date. "ne"/"nee" only counts immediately before "le", because bare
# "ne" is the French negation and would match half of everything.
_BIRTH_CUE_RE = re.compile(r"\bnee?\s+le\b|\bdate de naissance\b|\bnaissance\b|\bborn\b")
_DATE_DMY_RE = re.compile(r"\b(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{4})\b")
_DATE_ISO_RE = re.compile(r"\b(\d{4})-(\d{2})-(\d{2})\b")
_TIME_RE = re.compile(r"\b(\d{1,2})\s*(?:h|:)\s*(\d{2})?\b")
# The gap between the cue word and the number is generous on purpose: "mets a jour le telephone
# de Test Neurochir a 0555123456" puts a whole patient name in between, and the old 12-character
# limit meant the number was silently not extracted - the update then asked for something the
# clinician had already given.
_PHONE_RE = re.compile(r"(?:tel|telephone|numero|phone)\D{0,40}?(\+?\d[\d\s.\-]{6,17}\d)")
_GCS_RE = re.compile(r"\b(?:gcs|glasgow)\b\D{0,12}(\d{1,2})\b")
_KARNOFSKY_RE = re.compile(r"\bkarnofsky\b\D{0,12}(\d{1,3})\b")
_EVM_RE = re.compile(r"\be\s*=?\s*(\d)\b.{0,10}\bv\s*=?\s*(\d)\b.{0,10}\bm\s*=?\s*(\d)\b")
_IDENTIFIER_RE = re.compile(r"\b(?:identifiant|matricule|id|numero de dossier)\s*:?\s*([A-Z0-9][A-Z0-9\-]{2,19})\b", re.I)

BARE_IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9\-]{2,19}$")


def identifier_shaped(value: Optional[str]) -> Optional[str]:
    """The value as an identifier, if that is plainly what it is.

    OpenMRS identifiers here look like 1000C6 - short, no spaces, digits present. A name never
    does. Checked because both the extractor and the model put "1000C6" in `name` when the
    clinician wrote "le patient 1000C6", and a FHIR name search cannot match an identifier, so the
    record was reported as not existing. Shared between `_resolve_patient` (which already applied
    it) and `search_patient`'s own build function (which did not - "cherche le patient 10002T"
    searched name=10002T and found nothing).
    """
    if not value:
        return None
    text = str(value).strip()
    if " " in text or not BARE_IDENTIFIER_RE.match(text):
        return None
    return text.upper() if any(char.isdigit() for char in text) else None
_GENDER_M_RE = re.compile(r"\b(homme|masculin|male|garcon|monsieur|mr)\b")
# "patiente" and "nouvelle patiente" are feminine in French and are how a clinician states the sex
# without a separate word for it. Measured: "inscris une nouvelle patiente, Fatima Cherif" left
# gender unset, so the assistant asked for something the sentence had already said.
_GENDER_F_RE = re.compile(r"\b(femme|feminin|female|fille|madame|mme|patiente)\b")

_MONTHS = {
    "janvier": 1, "fevrier": 2, "mars": 3, "avril": 4, "mai": 5, "juin": 6,
    "juillet": 7, "aout": 8, "septembre": 9, "octobre": 10, "novembre": 11, "decembre": 12,
}
_DATE_WORDS_RE = re.compile(r"\b(\d{1,2})\s+(" + "|".join(_MONTHS) + r")\s+(\d{4})\b")


def _extract_name(original: str) -> Optional[str]:
    quoted = _QUOTED_RE.search(original)
    if quoted:
        return quoted.group(1).strip()
    # Capitalised first: when the clinician did capitalise, that is the strongest signal available
    # and needs no stopword guessing.
    keyed = _NAME_AFTER_KEYWORD_RE.search(original)
    if keyed:
        return keyed.group(1).strip()
    loose = _NAME_AFTER_KEYWORD_LOOSE_RE.search(original)
    if loose:
        name = _trim_to_name(loose.group(1))
        if name:
            return name
    return None


def _trim_to_name(candidate: str) -> Optional[str]:
    """Keeps the leading run of words that can plausibly be a name.

    Stops at the first stopword rather than dropping the whole match, so "patient walter white avec
    un GCS bas" still yields "walter white". Returns None when nothing survives - an empty name is
    worse than no name, because it would search for everyone.
    """
    tokens = candidate.split()
    # Leading stopwords are *skipped*, not treated as the end of the name. The leftmost trigger wins
    # in the pattern above, so "patient nomme rachid ghezal" hands this "nomme rachid ghezal":
    # stopping at the first stopword loses the name entirely, and keeping it produced a patient
    # called "nomme rachid ghezal" in the live database. Titles behave the same way - "madame Ziani"
    # is Ziani.
    start = 0
    while start < len(tokens) and normalise(tokens[start]).strip("'’-") in _NAME_STOPWORDS:
        start += 1

    kept: List[str] = []
    for token in tokens[start:]:
        if normalise(token).strip("'’-") in _NAME_STOPWORDS:
            break
        kept.append(token)
    return " ".join(kept) if kept else None


def _extract_dates(original: str, text: str) -> List[str]:
    """Every date in the turn, ISO-formatted, in the order they appear."""
    found: List[Tuple[int, str]] = []
    for match in _DATE_DMY_RE.finditer(original):
        day, month, year = match.groups()
        found.append((match.start(), f"{year}-{int(month):02d}-{int(day):02d}"))
    for match in _DATE_ISO_RE.finditer(original):
        found.append((match.start(), match.group(0)))
    for match in _DATE_WORDS_RE.finditer(text):
        day, month_name, year = match.groups()
        found.append((match.start(), f"{year}-{_MONTHS[month_name]:02d}-{int(day):02d}"))
    return [value for _, value in sorted(found)]


def extract_slots(original: str) -> Dict[str, Any]:
    text = normalise(original)
    slots: Dict[str, Any] = {}

    name = _extract_name(original)
    if name:
        slots["name"] = name

    if _GENDER_M_RE.search(text):
        slots["gender"] = "M"
    elif _GENDER_F_RE.search(text):
        slots["gender"] = "F"

    dates = _extract_dates(original, text)
    if dates:
        slots["dates"] = dates
        # A date is a *birth* date only when the sentence says so. The previous version set it for
        # any date at all - both branches of its ternary returned dates[0], so the condition it
        # appeared to test did nothing - which meant "programme un rendez-vous le 12/09/2026"
        # produced birthdate=2026-09-12, and an update turn mentioning any date could have
        # overwritten a real patient's date of birth. A tool that wants a plain date reads `dates`;
        # only an explicit birth cue fills `birthdate`.
        if _BIRTH_CUE_RE.search(text):
            slots["birthdate"] = dates[0]

    time_match = _TIME_RE.search(text)
    if time_match:
        hour, minute = time_match.groups()
        slots["time"] = f"{int(hour):02d}:{minute or '00'}"

    phone = _PHONE_RE.search(text)
    if phone:
        slots["phone"] = re.sub(r"[\s.\-]", "", phone.group(1))

    identifier = _IDENTIFIER_RE.search(original)
    if identifier:
        slots["identifier"] = identifier.group(1)

    gcs = _GCS_RE.search(text)
    if gcs:
        slots["gcs_total"] = int(gcs.group(1))

    karnofsky = _KARNOFSKY_RE.search(text)
    if karnofsky:
        slots["karnofsky"] = int(karnofsky.group(1))

    evm = _EVM_RE.search(text)
    if evm:
        slots["eye_response"] = int(evm.group(1))
        slots["verbal_response"] = int(evm.group(2))
        slots["motor_response"] = int(evm.group(3))

    return slots


# --------------------------------------------------------------------------- the engine


class RuleBasedNlu:
    """Phase 2's interpreter. Stateless; the conversation state lives in the orchestrator."""

    def interpret(self, prompt: str, context: Dict[str, Any]) -> Interpretation:
        text = normalise(prompt)
        matched = _match_tasks(text)

        if not matched:
            return Interpretation(
                intent=INTENT_UNSUPPORTED,
                clarification=(
                    "Je n'ai pas compris la demande. Je peux rechercher un patient, afficher son "
                    "dossier, creer ou mettre a jour un patient, noter un score neurologique, ou "
                    "programmer un rendez-vous. Que souhaitez-vous faire ?"
                ),
            )

        # Two different families in one sentence is genuinely ambiguous - asking costs one turn,
        # guessing wrong writes to the wrong place.
        distinct = list(dict.fromkeys(matched))
        if len(distinct) > 1 and not _is_benign_overlap(distinct):
            return Interpretation(
                intent=INTENT_TASK,
                task=distinct[0],
                slots=extract_slots(prompt),
                clarification=(
                    "Votre demande peut correspondre a plusieurs actions ("
                    + ", ".join(_label(task) for task in distinct)
                    + "). Laquelle souhaitez-vous ?"
                ),
            )

        task = distinct[0]
        slots = extract_slots(prompt)

        if task in WRITE_TASKS and reads_as_description(prompt):
            return Interpretation(
                intent=INTENT_TASK,
                task=task,
                slots=slots,
                clarification=(
                    "Votre message decrit une situation plutot qu'il ne demande un enregistrement. "
                    "Voulez-vous que je l'enregistre dans le dossier ? Reformulez par exemple : "
                    '"enregistre un GCS a 6 pour ce patient".'
                ),
            )

        return Interpretation(intent=INTENT_TASK, task=task, slots=slots)


def _is_benign_overlap(tasks: List[str]) -> bool:
    """Some pairs are not really two intents.

    "affiche le dossier du patient Benali" hits both the summary and the search vocabulary, but
    they are the same request at different stages - a summary is a search followed by a read.
    Likewise "cherche tous les patients dont les noms commencent par W" hits both search and list
    vocabulary ("tous les patients" names the scope, not a second request) - all three are
    read-only lookups that differ only in how many records come back.
    """
    return set(tasks) <= {TASK_GET_PATIENT_SUMMARY, TASK_SEARCH_PATIENT, TASK_LIST_PATIENTS}


_LABELS = {
    TASK_SEARCH_PATIENT: "rechercher un patient",
    TASK_GET_PATIENT_SUMMARY: "afficher un dossier",
    TASK_CREATE_PATIENT: "creer un patient",
    TASK_UPDATE_PATIENT: "mettre a jour un patient",
    TASK_BOOK_APPOINTMENT: "programmer un rendez-vous",
    TASK_RECORD_NEURO_ASSESSMENT: "enregistrer un score neurologique",
    TASK_LIST_PATIENTS: "lister des patients",
}


def _label(task: str) -> str:
    return _LABELS.get(task, task)
