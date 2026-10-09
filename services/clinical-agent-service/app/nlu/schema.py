"""The JSON schema the model is constrained to, generated from the tool registry.

Generated, never hand-written. The registry already declares every task family, which slots each
one needs and what they mean; restating that in a prompt would create a second source of truth that
drifts the first time a tool changes. Here the enum of task names *is* the registry's task names, so
a tool added tomorrow is offered to the model automatically and a tool removed stops being
representable.

That last property is the point. With vLLM's structured output the model cannot emit a task name
outside this enum, so "the model invented an endpoint" is not a failure mode that exists - it is
excluded by construction rather than caught by validation. What the model can still get wrong is
*which* of the real tasks a sentence means, and which slot a value belongs in. Those are judgement
errors, and they are what the confirmation gate and the post-checks in ``medgemma.py`` are for.
"""

from __future__ import annotations

from typing import Any, Dict, List

from ..tools.registry import ToolRegistry

# What each slot means, in the words a clinician would use. Kept here rather than in the registry
# because it is guidance for a language model, not part of a tool's contract.
SLOT_DESCRIPTIONS: Dict[str, str] = {
    "name": "Nom complet du patient, tel qu'il apparait dans la phrase. Ne pas inventer.",
    "identifier": "Identifiant du dossier patient (ex. 10007F), si la phrase en contient un.",
    "gender": "M pour masculin, F pour feminin. Uniquement si la phrase le precise.",
    "birthdate": "Date de naissance au format AAAA-MM-JJ.",
    "phone": "Numero de telephone, chiffres uniquement.",
    "date": "Date concernee par la demande, au format AAAA-MM-JJ.",
    "time": "Heure au format HH:MM.",
    "gcs": "Score de Glasgow, entier de 3 a 15.",
    "karnofsky": "Indice de Karnofsky, entier de 0 a 100 par pas de 10.",
}

INTENT_VALUES = ["task", "confirm", "cancel", "unsupported"]


def build_interpretation_schema(registry: ToolRegistry) -> Dict[str, Any]:
    """The schema for one interpreted turn.

    ``task`` is an enum of exactly the registry's task families plus null, so an unsupported request
    has a way to be expressed that is not a wrong guess.
    """
    tasks = sorted({tool.task for tool in registry.all()})
    slots = sorted({slot for tool in registry.all() for slot in _slots_of(tool)})

    return {
        "type": "object",
        "additionalProperties": False,
        "required": ["intent", "task", "slots", "clarification"],
        "properties": {
            "intent": {
                "type": "string",
                "enum": INTENT_VALUES,
                "description": (
                    "task = la phrase demande une operation. confirm = c'est un oui a une question "
                    "posee. cancel = c'est un non. unsupported = la demande sort de ce que "
                    "l'assistant sait faire."
                ),
            },
            "task": {
                "type": ["string", "null"],
                "enum": tasks + [None],
                "description": "La famille de tache demandee, ou null si aucune ne correspond.",
            },
            "slots": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    slot: {
                        "type": ["string", "null"],
                        "description": SLOT_DESCRIPTIONS.get(slot, slot),
                    }
                    for slot in slots
                },
                "description": (
                    "Uniquement les valeurs presentes dans la phrase. Un champ absent doit rester "
                    "null : il sera demande au clinicien, ce qui est toujours preferable a une "
                    "valeur devinee."
                ),
            },
            "clarification": {
                "type": ["string", "null"],
                "description": (
                    "Question a poser au clinicien, en francais, si la demande est ambigue, si elle "
                    "decrit un etat au lieu de demander une action, ou si elle correspond a "
                    "plusieurs familles de tache. null sinon."
                ),
            },
        },
    }


def _slots_of(tool) -> List[str]:
    """Every slot a tool can use: the required ones plus any it has a question for.

    ``slot_questions`` is the wider set - a tool asks about optional slots too - so it is the better
    source for "what could this tool possibly want".
    """
    return list(dict.fromkeys(list(tool.required_slots) + list(tool.slot_questions.keys())))


def describe_tools_for_prompt(registry: ToolRegistry) -> str:
    """The task list as the model sees it: name, what it does, what it needs.

    Deliberately terse. This is a 4B model; a long catalogue with prose descriptions crowds out the
    clinician's actual sentence.
    """
    lines: List[str] = []
    for tool in sorted(registry.all(), key=lambda t: t.task):
        needs = ", ".join(tool.required_slots) or "-"
        kind = "ECRITURE" if tool.writes else "lecture"
        lines.append(f"- {tool.task} ({kind}) : {tool.description}. Champs requis : {needs}")
    return "\n".join(lines)
