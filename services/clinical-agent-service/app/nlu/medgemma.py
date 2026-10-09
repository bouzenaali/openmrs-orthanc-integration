"""MedGemma behind the same interface the rules engine implements (Phase 3).

The model's job is narrow: read one French sentence and say which registered task it means and which
slots it filled. It never composes a URL, never chooses an endpoint, and never decides whether the
clinician may do the thing. The tool registry turns its answer into concrete calls, the confirmation
gate shows those to the clinician, and OpenMRS settles permission. Swapping the interpreter does not
move any of that.

Two properties are enforced here rather than hoped for:

*Structured output.* vLLM is asked for a response matching :mod:`app.nlu.schema`, so a task name
outside the registry is not something the model is able to emit.

*Two safety rules, in the prompt **and** in code.* A 4B model will not honour either reliably from
instructions alone, and both have the same failure mode - a value written to a patient record that
nobody asked for:

1. Descriptive phrasing is not an instruction. "Le GCS s'est aggrave a 6" reports a course;
   "note un GCS a 6" requests a write.
2. A turn that matches two task families is ambiguous, full stop. Asking costs one turn; guessing
   wrong writes to the wrong place.

If the model is unreachable, slow or answers unusably, interpretation falls back to the rules engine
rather than failing the turn. A dead GPU should degrade the assistant's understanding of language,
not take the chat offline.
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any, Dict, List, Optional

import httpx

from ..config import settings
from ..tools.registry import ToolRegistry, missing_slots
from .base import (
    INTENT_CANCEL,
    INTENT_CONFIRM,
    INTENT_TASK,
    INTENT_UNSUPPORTED,
    Interpretation,
)
from .rules import RuleBasedNlu, classify_answer, extract_slots, reads_as_description
from .schema import build_interpretation_schema, describe_tools_for_prompt

log = logging.getLogger(__name__)

# Slots whose values are drawn from a small coded set, so a substring check proves nothing about
# whether the sentence really said them.
_EXTRACTOR_ONLY_SLOTS = {"gender", "gcs_total", "karnofsky"}

# Below this, a substring match is coincidence rather than evidence.
_MIN_CORROBORATION_CHARS = 3


def _has_no_letters(value: str) -> bool:
    """True for a value that cannot possibly be a person's name.

    A name always has at least one letter; a bare phone number does not. Needed because the
    substring corroboration check below proves too much on its own: "mets a jour son telephone a
    0666777888" has no name in it at all, but the model returned slots={"name": "0666777888",
    "phone": "0666777888"} - and "0666777888" *is* a substring of the sentence, so corroboration
    alone waved the phone number through as a name, which then got searched as a patient
    identifier instead of the anaphora target the sentence actually meant.
    """
    return not any(char.isalpha() for char in value)

# Free-text slots where the model's answer is kept over the extractor's. The regexes cut names on
# fixed word counts and swallow politeness ("madame Ziani s'il"); the model reads the name.
_MODEL_WINS_SLOTS = {"name"}

# The model is told, in its own prompt, that it is "un classificateur de phrases, pas un
# assistant". That framing must never reach a clinician - measured, it leaked into the
# clarification for an out-of-scope turn ("que peux-tu faire ?" -> "Je suis un classificateur de
# phrases..."), which is confusing and exposes the prompt engineering. A clarification containing
# this vocabulary is dropped rather than shown (Finding 33).
_FRAMING_LEAK_RE = re.compile(
    r"classificateur|classifie|categorie|un autre programme|je ne rends aucun service", re.IGNORECASE
)

SYSTEM_PROMPT = """Tu es un CLASSIFICATEUR de phrases, pas un assistant. Tu ne rends aucun service \
et tu ne refuses jamais rien : tu lis une phrase en francais et tu produis un objet JSON qui la \
range dans une categorie. Un autre programme fait le travail ensuite.

Ne dis jamais « je ne peux pas ». Ce n'est pas toi qui agis.

## Categories (champ "task")
{tools}

## Champs (champ "slots")
- name : le nom du patient tel qu'ecrit. Jamais un mot comme « patient », « nomme », « dossier », \
jamais un titre (« madame »), jamais un pronom, jamais un identifiant.
- identifier : un identifiant de dossier, p.ex. 10007F. Contient des chiffres.
- gender : "M" ou "F".
- birthdate : AAAA-MM-JJ, seulement si la phrase parle de naissance.
- phone : chiffres uniquement.
- dates, time : date AAAA-MM-JJ et heure HH:MM d'un rendez-vous.
- gcs_total : 3 a 15. karnofsky : 0 a 100.

N'invente aucune valeur. Un champ absent de la phrase reste absent. Ne deduis pas le sexe d'un prenom.

## Regles de classement

1. « chercher », « qui est », « existe-t-il », « retrouver » -> search_patient. « afficher le \
dossier », « montrer les informations », « resume » -> get_patient_summary. « liste », « tous les \
patients », « toutes les patientes » SEULS, sans aucune autre precision de nom -> list_patients \
(le champ gender filtre si la phrase precise le sexe). Si la phrase precise en plus un debut de \
nom, une lettre ou toute autre precision sur le nom ("dont les noms commencent par W") -> \
search_patient, avec cette precision dans slots.name. Dans le doute entre chercher un patient \
nomme et lister plusieurs : search_patient.

2. Une phrase qui DECRIT ou SUPPOSE n'est pas un ordre. « Le GCS s'est aggrave a 6 », « le patient \
semble confus », « je pense que c'est Benali », « faut-il noter un GCS a 6 ? » -> pose une question \
dans "clarification". Seul un ordre explicite (« cree », « note », « enregistre », « mets a jour », \
« programme ») est une ecriture.

3. Deux categories possibles dans la meme phrase -> pose une question, ne choisis pas.

4. intent = "unsupported" UNIQUEMENT si la phrase ne concerne pas un patient (meteo, cuisine...) ou \
demande une suppression. Une demande claire concernant un patient n'est JAMAIS "unsupported".

5. "clarification" : absent si la phrase donne ce qu'il faut. Sinon, une vraie question, en francais \
courant, terminee par « ? ». N'y mets jamais un nom de categorie ni un nom de champ : le clinicien \
qui la lit ne les connait pas.

Reponds uniquement par l'objet JSON."""


# Demonstrations, not just rules. A 4B model follows an example far more reliably than a paragraph,
# and the first measurement showed exactly which mistake needed demonstrating away: the model
# understood every sentence and then put its understanding in `clarification` instead of leaving it
# null, so every clear request became a needless question. Each example below pins one behaviour.
FEW_SHOT: List[Dict[str, str]] = [
    # A plain read: task filled, clarification null. The mistake this corrects is a restatement
    # ("Rechercher le patient Walter White.") landing in clarification.
    {
        "user": "cherche le patient walter white",
        "assistant": json.dumps(
            {"intent": "task", "task": "search_patient",
             "slots": {"name": "walter white"}, "clarification": None},
            ensure_ascii=False),
    },
    # A question in form but a lookup in intent. Interrogative phrasing is not ambiguity.
    {
        "user": "est-ce qu'on a un dossier pour Ahmed Ziani ?",
        "assistant": json.dumps(
            {"intent": "task", "task": "search_patient",
             "slots": {"name": "Ahmed Ziani"}, "clarification": None},
            ensure_ascii=False),
    },
    # A complete write: every value present, so nothing to ask. Corrects "gender and birthdate are
    # missing" for a sentence that stated both.
    {
        "user": "cree un patient nomme \"Fatima Cherif\", femme, nee le 12/03/1980",
        "assistant": json.dumps(
            {"intent": "task", "task": "create_patient",
             "slots": {"name": "Fatima Cherif", "gender": "F", "birthdate": "1980-03-12"},
             "clarification": None},
            ensure_ascii=False),
    },
    # A write missing a value: ask for the one thing that is absent, and invent nothing.
    {
        "user": "cree un patient nomme \"Karim Saidi\"",
        "assistant": json.dumps(
            {"intent": "task", "task": "create_patient", "slots": {"name": "Karim Saidi"},
             "clarification": "Quel est le sexe et la date de naissance du patient ?"},
            ensure_ascii=False),
    },
    # A description, not an instruction. The rule that matters most.
    {
        "user": "le GCS s'est aggrave a 6",
        "assistant": json.dumps(
            {"intent": "task", "task": "record_neuro_assessment", "slots": {"gcs_total": "6"},
             "clarification": "Cette phrase decrit un etat. Souhaitez-vous que je l'enregistre dans le dossier ?"},
            ensure_ascii=False),
    },
    # A destructive request. Measured: the model mapped "supprime tous les patients" to
    # update_patient_demographics, which is both wrong and the worst possible direction to guess in.
    {
        "user": "supprime tous les patients",
        "assistant": json.dumps(
            {"intent": "unsupported", "task": None, "slots": {},
             "clarification": "Je ne peux pas supprimer de dossiers."},
            ensure_ascii=False),
    },
    # Out of scope.
    {
        "user": "quelle est la meteo aujourd'hui",
        "assistant": json.dumps(
            {"intent": "unsupported", "task": None, "slots": {},
             "clarification": "Je ne peux pas traiter cette demande."},
            ensure_ascii=False),
    },
    # A list, not a lookup of one named patient - no name in the sentence, so search_patient would
    # ask for one nobody meant to give.
    {
        "user": "donne moi toutes les patientes de sexe feminin",
        "assistant": json.dumps(
            {"intent": "task", "task": "list_patients", "slots": {"gender": "F"}, "clarification": None},
            ensure_ascii=False),
    },
    # "tous les patients" here still qualifies a name (a prefix), so this is a search with that
    # prefix as the name to match - not an unfiltered list. list_patients has no way to filter by
    # how a name starts.
    {
        "user": "cherche tous les patients dont les noms commencent par W",
        "assistant": json.dumps(
            {"intent": "task", "task": "search_patient", "slots": {"name": "W"}, "clarification": None},
            ensure_ascii=False),
    },
]


class MedGemmaNlu:
    """Interprets a turn with MedGemma, constrained to the registry's own schema."""

    def __init__(
        self,
        registry: ToolRegistry,
        fallback: Optional[Any] = None,
        client: Optional[httpx.AsyncClient] = None,
    ) -> None:
        self._registry = registry
        self._fallback = fallback if fallback is not None else RuleBasedNlu()
        self._client = client
        self._schema = build_interpretation_schema(registry)
        self._system_prompt = SYSTEM_PROMPT.format(tools=describe_tools_for_prompt(registry))

    # ------------------------------------------------------------------ sync entry point

    def interpret(self, prompt: str, context: Dict[str, Any]) -> Interpretation:
        """Present so this class satisfies :class:`NluEngine`.

        The real path is :meth:`ainterpret`; a model call is network I/O and must not block the
        event loop. Anything calling this synchronously gets the deterministic engine, which is the
        safe reading rather than a silent blocking call.
        """
        return self._fallback.interpret(prompt, context)

    # ------------------------------------------------------------------ the model path

    async def ainterpret(self, prompt: str, context: Dict[str, Any]) -> Interpretation:
        # A plain yes or no is settled by a rule. It is unambiguous, it is the most common turn in
        # any confirmation flow, and spending a GPU call plus a second of latency on it would make
        # the assistant feel slower for no gain in understanding.
        answer = classify_answer(prompt)
        if answer in (INTENT_CONFIRM, INTENT_CANCEL) and len(prompt.split()) <= 4:
            return Interpretation(intent=answer)

        try:
            payload = await self._ask_model(prompt)
        except Exception as exc:  # noqa: BLE001 - any model failure degrades, never fails the turn
            log.warning("MedGemma unavailable, falling back to the rules engine: %s", exc)
            return self._fallback.interpret(prompt, context)

        interpretation = self._to_interpretation(payload)
        if interpretation is None:
            log.warning("MedGemma returned an unusable answer, falling back to the rules engine")
            return self._fallback.interpret(prompt, context)

        interpretation = self._prefer_rules_over_a_refusal(prompt, context, interpretation)
        return self._apply_safety_rules(prompt, interpretation)

    def _prefer_rules_over_a_refusal(
        self, prompt: str, context: Dict[str, Any], interpretation: Interpretation
    ) -> Interpretation:
        """Falls back to the deterministic reading when the model declines a sentence the rules can read.

        Measured, repeatedly and across three prompt rewrites: MedGemma answers plain instructions with
        "Je ne peux pas mettre a jour le numero de telephone d'un patient" - it role-plays the assistant
        being asked to act, and refuses, instead of labelling the sentence. Telling it not to, in the
        prompt, does not stop it at 4B.

        So this is settled in code rather than by asking more nicely. The division of labour is the one
        the rest of this class already uses: the model exists for phrasing the regexes cannot parse, and
        where the regexes *can* parse a sentence into a concrete task with nothing missing, they are
        simply right and a refusal is noise.

        Safety is unaffected. The rules engine applies its own descriptive-phrasing check before
        returning anything, so a hedged sentence cannot be promoted into a write here, and
        :meth:`_apply_safety_rules` still runs afterwards on whichever reading wins.
        """
        model_declined = interpretation.intent == INTENT_UNSUPPORTED or interpretation.needs_clarification
        if not model_declined:
            return interpretation

        deterministic = self._fallback.interpret(prompt, context)
        if deterministic.intent != INTENT_TASK or deterministic.task is None:
            return interpretation
        if deterministic.needs_clarification:
            return interpretation

        tool = self._registry.for_task(deterministic.task, _no_capabilities())
        if tool is None or missing_slots(tool.tool, deterministic.slots):
            return interpretation

        log.info(
            "Preferring the deterministic reading of %r: the model declined a sentence the rules parse "
            "completely as %s", prompt, deterministic.task,
        )
        return deterministic

    def _messages(self, prompt: str) -> List[Dict[str, str]]:
        """The turns sent to the model, few-shot examples first and the rules last.

        **There is no system message.** Gemma 3 - and so MedGemma - has no ``system`` turn in its chat
        template, and vLLM silently discards one: a 2178-character instruction block tokenised to
        *four* tokens when sent as ``role: system`` and 564 when sent as ``role: user``. Every rule in
        this file was therefore being thrown away on every call, and the model's behaviour up to this
        point came from the few-shot examples alone.

        The instructions are attached to the final user turn rather than the first, because
        instruction adherence in a 4B model falls off with distance: the rules now sit immediately
        before the sentence they govern. The examples come first and teach the output shape.
        """
        messages: List[Dict[str, str]] = []
        for shot in FEW_SHOT:
            messages.append({"role": "user", "content": shot["user"]})
            messages.append({"role": "assistant", "content": shot["assistant"]})
        messages.append({"role": "user", "content": f"{self._system_prompt}\n\n---\n\nPhrase a interpreter :\n{prompt}"})
        return messages

    async def _ask_model(self, prompt: str) -> Dict[str, Any]:
        body = {
            "model": settings.llm_model,
            "messages": self._messages(prompt),
            # Deterministic on purpose: the same sentence must be read the same way twice. A
            # clinician who rephrases nothing and gets a different task the second time has no way
            # to trust the thing.
            "temperature": 0.0,
            "max_tokens": settings.llm_max_tokens,
            "response_format": {
                "type": "json_schema",
                "json_schema": {"name": "interpretation", "schema": self._schema, "strict": True},
            },
        }

        if settings.log_prompts:
            # The few-shot block is the bulk of every call (see `_messages`'s own docstring) and
            # adds nothing worth re-reading turn after turn; only the engineered final turn - the
            # rules plus the clinician's actual sentence, exactly as the model receives it after
            # prompt construction - is logged. Same toggle `main.py` already uses to log the raw
            # clinician prompt, so turning on LOG_PROMPTS shows the whole chain: what was typed,
            # what was actually sent to the model, and what it said back.
            log.info("MedGemma request (final turn): %s", body["messages"][-1]["content"])

        client = self._client
        if client is None:
            async with httpx.AsyncClient(timeout=settings.llm_timeout_seconds) as owned:
                response = await owned.post(f"{settings.llm_base_url}/chat/completions", json=body)
        else:
            response = await client.post(f"{settings.llm_base_url}/chat/completions", json=body)

        response.raise_for_status()
        content = response.json()["choices"][0]["message"]["content"]
        if settings.log_prompts:
            log.info("MedGemma raw response: %s", content)
        return json.loads(content)

    # ------------------------------------------------------------------ answer handling

    def _to_interpretation(self, payload: Dict[str, Any]) -> Optional[Interpretation]:
        """Turns the model's object into an :class:`Interpretation`, or None if it cannot be trusted.

        The schema guarantees the shape, not the sense: a well-formed answer can still name a task
        while leaving ``intent`` as unsupported. Rather than repair a contradiction by guessing which
        half was meant, this refuses it and lets the caller fall back.
        """
        intent = payload.get("intent")
        task = payload.get("task")
        clarification = payload.get("clarification")

        if intent not in (INTENT_TASK, INTENT_CONFIRM, INTENT_CANCEL, INTENT_UNSUPPORTED):
            return None

        if intent == INTENT_TASK:
            if not task or self._registry.for_task(task, _no_capabilities()) is None:
                # Naming a task the registry does not have should be impossible under the schema.
                # Treated as an unusable answer rather than trusted, because if it ever happens the
                # constraint is not doing what this design assumes.
                log.warning("MedGemma named a task outside the registry: %r", task)
                return None
        else:
            task = None

        slots = {
            key: value
            for key, value in (payload.get("slots") or {}).items()
            if value not in (None, "")
        }

        return Interpretation(
            intent=intent,
            task=task,
            slots=slots,
            clarification=self._usable_clarification(clarification, intent, task),
        )

    @staticmethod
    def _usable_clarification(text: Optional[str], intent: str, task: Optional[str]) -> Optional[str]:
        """Keeps a clarification only when it is actually a question.

        The first measurement against real weights found the model restating the request into this
        field - "Rechercher le patient Walter White." for a sentence that needed nothing clarified -
        which turned every clear instruction into a needless question and made the assistant unusable.
        A statement offered alongside a task it has already identified is not a request for
        information, so it is dropped.

        This does not weaken either safety rule. Both are enforced in code afterwards, from the
        sentence rather than from the model's opinion: descriptive phrasing is caught by
        :func:`reads_as_description`, and a task the registry does not have is refused outright. What
        this drops is chatter, not caution - and when there is no task to act on, the text is kept
        whatever its punctuation, because then it is the only thing there is to say.
        """
        if not text or not text.strip():
            return None
        cleaned = text.strip()
        if _FRAMING_LEAK_RE.search(cleaned):
            log.info("Dropping a clarification that leaked the classifier framing: %r", cleaned)
            return None
        if intent != INTENT_TASK or task is None:
            return cleaned
        return cleaned if "?" in cleaned else None

    def _apply_safety_rules(self, prompt: str, interpretation: Interpretation) -> Interpretation:
        """The two rules, re-checked in code after the model has answered.

        Not distrust for its own sake: both rules protect against the same thing, a value written to
        a patient record that nobody asked for, and a 4B model following an instruction "usually" is
        not a control.

        Order matters here, and got it wrong once. An earlier version suppressed redundant questions
        *after* the descriptive-phrasing check had declined to add one (because the model had already
        asked), which dropped the model's question and turned "le GCS s'est aggrave a 6" into a write
        plan. The measurement caught it - UNSAFE went from 0 to 7 - which is the whole reason that
        column is the go/no-go. Descriptive phrasing is now decided last and returns directly, so no
        later step can undo it.
        """
        if interpretation.intent != INTENT_TASK or interpretation.task is None:
            return interpretation

        tool = self._registry.for_task(interpretation.task, _no_capabilities())
        writes = bool(tool and tool.tool.writes)

        # Slots the model reported that the sentence does not contain are dropped on the write path.
        # A hallucinated identifier or date of birth is the most damaging thing this component can
        # produce, and the deterministic extractor is the arbiter of what was actually said.
        if writes:
            interpretation.slots = self._drop_unsupported_slots(prompt, interpretation.slots)
        elif interpretation.slots.get("name"):
            # _MODEL_WINS_SLOTS trusts the model's name uncritically, which is right for a write
            # (any value invented there would show up in a summary the clinician approves), but on
            # a read it is the value used to search for the patient - a fabricated first name
            # turns a findable patient into a false "not found" ("Ziani" became "Ahmed Ziani",
            # Finding 34). Require the name to actually be in the sentence outside writes too.
            name = str(interpretation.slots["name"]).strip().lower()
            if name and (name not in prompt.lower() or _has_no_letters(name)):
                log.info("Dropping an unusable name: %r", interpretation.slots["name"])
                interpretation.slots.pop("name", None)

        # The extractor's findings are merged in, but *which* source wins depends on the slot, because
        # the two are good at opposite halves of the job.
        #
        # Structured values - a date, a phone number, a score - the extractor reads exactly, and the
        # model overlooked all three in sentences that stated them plainly. It wins those.
        #
        # Free text - a name - the model reads out of phrasing no pattern covers, and letting the
        # extractor win produced a patient called "madame Ziani s'il" from "retrouve moi le dossier de
        # madame Ziani s'il vous plait", where the model had correctly answered "Ziani". So for names
        # the model wins, and the extractor only fills a gap.
        for slot, value in extract_slots(prompt).items():
            if slot in _MODEL_WINS_SLOTS and interpretation.slots.get(slot):
                continue
            interpretation.slots[slot] = value

        if writes and reads_as_description(prompt):
            # A description, a hedge or a question - never a write. Keep the model's own question if
            # it asked one, since it is likely better phrased for this sentence; otherwise ask ours.
            # Returned directly: nothing after this point may drop it.
            log.info("Refusing a write on descriptive phrasing: %r", prompt)
            return Interpretation(
                intent=INTENT_TASK,
                task=interpretation.task,
                slots=interpretation.slots,
                clarification=interpretation.clarification or _descriptive_question(prompt),
            )

        if writes and not interpretation.needs_clarification:
            # Rule 3 in the prompt ("deux categories possibles -> pose une question") has nothing
            # enforcing it in code, unlike rule 2 above - and measured, it failed: "dossier et
            # rendez-vous pour Benali" came back as a confident book_appointment with no question,
            # which the deterministic matcher used by the rules engine still reads as naming two
            # task families. That matcher is reused here as the same backstop rule 2 already gets,
            # because guessing which of two actions was meant is exactly the failure this file
            # exists to prevent, and it costs nothing to ask when unsure.
            deterministic = self._fallback.interpret(prompt, {})
            if deterministic.needs_clarification and deterministic.intent == INTENT_TASK:
                log.info(
                    "Forcing a clarification the model skipped: the deterministic matcher still "
                    "reads %r as ambiguous", prompt,
                )
                return Interpretation(
                    intent=INTENT_TASK,
                    task=interpretation.task,
                    slots=interpretation.slots,
                    clarification=deterministic.clarification,
                )

        # A question asking for something the sentence already gave is not worth a turn. Measured: the
        # model answered 'cree un patient nomme "Ahmed Ziani", homme, ne le 07/11/1965' with "Quel est
        # le sexe et la date de naissance du patient ?" - both stated plainly. Forwarding that makes a
        # clinician repeat themselves, which is the main thing standing between this model and being
        # usable.
        #
        # The trade: a sentence the model found genuinely ambiguous, whose slots happen to be complete,
        # proceeds instead of asking. Acceptable because this is not the last line of defence - a write
        # still shows a plain-language summary and waits for an explicit yes (CA5) - and because the
        # descriptive check above has already returned, so it can never be reached for those.
        if interpretation.needs_clarification and tool is not None:
            if not missing_slots(tool.tool, interpretation.slots):
                log.info("Dropping a clarification for %r: every required slot is present", prompt)
                interpretation.clarification = None

        return interpretation

    def _drop_unsupported_slots(self, prompt: str, slots: Dict[str, Any]) -> Dict[str, Any]:
        """Keeps only slot values that appear in the sentence, for writes.

        The rules engine's extractor is narrow - that is why the model is here - so its output is
        used as corroboration, not as a replacement: a value it also found is kept as-is, and a value
        it did not find is kept only if it appears verbatim in the sentence. Anything else was
        invented and is dropped, which turns a fabricated value into a question.
        """
        extracted = extract_slots(prompt)
        haystack = prompt.lower()
        kept: Dict[str, Any] = {}

        for key, value in slots.items():
            if key in extracted:
                kept[key] = extracted[key]
                continue
            if key in _EXTRACTOR_ONLY_SLOTS:
                # A coded value cannot be corroborated by looking for it in the sentence: "M" appears
                # inside almost any French sentence by accident. Measured - the model inferred
                # gender="M" from the first name "Ahmed" in a sentence about a GCS score, and the
                # substring test waved it through. Only the extractor may fill these.
                log.info("Dropping slot %s=%r: coded slots come from the extractor only", key, value)
                continue
            text = str(value).strip()
            if text.lower() == haystack.strip():
                # A confused model sometimes echoes the whole prompt back as a slot's value - "cree
                # un patient" (no name at all) came back with slots={"name": "cree un patient"}.
                # That trivially passes the substring check below (the whole prompt is, of course,
                # a substring of itself), so it needs its own guard: the entire sentence is never a
                # legitimate value for a single field.
                log.info("Dropping slot %s=%r: it is the whole prompt, not a value in it", key, value)
                continue
            if key == "name" and _has_no_letters(text):
                # A phone number or an identifier is a substring of the sentence by definition
                # when the clinician typed it - that alone is not evidence it was meant as a name.
                # Measured: "mets a jour son telephone a 0666777888" came back with slots={"name":
                # "0666777888", "phone": "0666777888"}, and the digits then got searched as an
                # identifier instead of the anaphora target the sentence actually meant.
                log.info("Dropping slot name=%r: no letters, cannot be a person's name", value)
                continue
            if len(text) >= _MIN_CORROBORATION_CHARS and text.lower() in haystack:
                kept[key] = value
                continue
            log.info("Dropping slot %s=%r: not supported by the sentence", key, value)

        return kept


def _descriptive_question(prompt: str) -> str:
    """The question to ask about a sentence that describes rather than instructs.

    Two different sentence types were getting one identical reply: "le GCS s'est aggrave a 6" is the
    clinician reporting a course, while "faut-il noter un GCS a 6 ?" is the clinician already asking.
    Answering the second with "cette phrase decrit un etat" tells them something they plainly know.
    """
    if prompt.strip().endswith("?"):
        return "Oui, je peux l'enregistrer. Confirmez-vous que je l'ajoute au dossier ?"
    return (
        "Cette phrase decrit un etat plutot qu'une action. Souhaitez-vous que j'enregistre cette "
        "information dans le dossier ?"
    )


def _no_capabilities():
    """Capabilities as far as interpretation is concerned: irrelevant.

    Whether the deployment exposes a resource decides whether a task can be *executed*, and the
    orchestrator checks that immediately afterwards with the real capability statement. Interpretation
    only needs to know the task exists, so passing the live statement in here would couple reading a
    sentence to the state of an HTTP fetch.
    """
    from ..capabilities import FhirCapabilities

    return FhirCapabilities(resources={}, fetched_at=0.0, error=None)
