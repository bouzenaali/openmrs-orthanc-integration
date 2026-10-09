"""The seam between "what did the clinician mean" and everything else.

Phase 2 fills this with deterministic rules (see :mod:`app.nlu.rules`). Phase 3 replaces the
implementation with MedGemma constrained against the tool schemas, and nothing downstream has to
change: the orchestrator, the confirmation gate, the tool registry and the audit trail all work
from an :class:`Interpretation`, not from a model.

Keeping the boundary here is what makes the security model testable without a GPU, and what
means a model that starts hallucinating a task cannot reach OpenMRS any more directly than the
rules can - it still has to name a registered tool, fill its declared slots, and pass the same
confirmation gate.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, Optional, Protocol

# --- what the clinician's turn is, at the coarsest level -----------------------------------
INTENT_TASK = "task"
INTENT_CONFIRM = "confirm"
INTENT_CANCEL = "cancel"
INTENT_UNSUPPORTED = "unsupported"

# --- task families (CA2). "unsupported" is a first-class answer, not a failure. ------------
TASK_SEARCH_PATIENT = "search_patient"
TASK_GET_PATIENT_SUMMARY = "get_patient_summary"
TASK_CREATE_PATIENT = "create_patient"
TASK_UPDATE_PATIENT = "update_patient_demographics"
TASK_BOOK_APPOINTMENT = "book_appointment"
TASK_RECORD_NEURO_ASSESSMENT = "record_neuro_assessment"
TASK_LIST_PATIENTS = "list_patients"


@dataclass
class Interpretation:
    """What the assistant believes a turn is asking for, and how sure it is.

    ``clarification`` being set is the signal to stop and ask (CA3) rather than proceed on a
    guess. It is set both when nothing matched and when something matched but the phrasing does
    not read as an instruction - "le GCS s'est aggrave a 6" is a clinician describing a patient's
    course, and reading it as "set the GCS to 6" would put a number in the record that nobody
    asked for.
    """

    intent: str
    task: Optional[str] = None
    slots: Dict[str, Any] = field(default_factory=dict)
    clarification: Optional[str] = None

    @property
    def needs_clarification(self) -> bool:
        return self.clarification is not None


class NluEngine(Protocol):
    """Anything that can turn a clinician's sentence into an :class:`Interpretation`."""

    def interpret(self, prompt: str, context: Dict[str, Any]) -> Interpretation:  # pragma: no cover - protocol
        ...
