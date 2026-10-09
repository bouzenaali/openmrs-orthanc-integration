"""The versioned list of things the assistant is able to do, and nothing else.

Every action the assistant can take is a registered tool with a declared shape: which task family
it serves, which slots it needs, whether it writes, and which OpenMRS capability it depends on.
That declaration is what makes task identification grounded rather than open-ended - the
interpreter's job is to name a tool and fill its slots, not to invent an endpoint - and it is
what a Phase 3 model will be constrained against instead of generating free-form calls.

A tool whose dependency the deployed OpenMRS does not advertise is *disabled with a reason*, not
quietly broken. That distinction matters in a clinical setting: "je ne peux pas programmer de
rendez-vous sur cette installation" is actionable, an unexplained failure three steps later is not.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Tuple

from ..capabilities import FhirCapabilities


@dataclass
class PlannedOperation:
    """One concrete HTTP call against OpenMRS's own API, ready to be shown or issued."""

    method: str
    path: str
    body: Optional[Dict[str, Any]] = None
    summary: str = ""
    # Set instead of ``body`` when this call needs a value produced by an earlier call in the same
    # plan - creating a patient has to ask idgen for an identifier before it can send one. Given
    # the bodies of the operations already executed, in order, it returns this call's body.
    #
    # Deliberately narrow rather than a general templating scheme: the only thing that may vary is
    # the body, the plan is still fixed and inspectable before anything is sent, and the
    # confirmation summary the clinician approves is still written by the tool, not derived from a
    # response the clinician never saw.
    body_from_results: Optional[Callable[[List[Any]], Dict[str, Any]]] = None

    @property
    def writes(self) -> bool:
        return self.method.upper() not in ("GET", "HEAD", "OPTIONS")

    def resolved_body(self, prior_results: List[Any]) -> Optional[Dict[str, Any]]:
        return self.body_from_results(prior_results) if self.body_from_results else self.body

    def describe(self) -> Dict[str, Any]:
        return {"method": self.method, "path": self.path, "summary": self.summary}


@dataclass
class ToolSpec:
    name: str
    task: str
    writes: bool
    description: str
    # Slots the tool cannot run without. A missing one produces a clarifying question (CA3),
    # never a call with a blank field.
    required_slots: Tuple[str, ...] = ()
    # The question to ask for each slot, so the assistant asks for the missing thing specifically
    # rather than telling the clinician to rephrase.
    slot_questions: Dict[str, str] = field(default_factory=dict)
    requires_patient: bool = False
    fhir_resource: Optional[str] = None
    fhir_interaction: Optional[str] = None
    # Set for tools that target a module's own REST resources rather than FHIR (ADR-10's second
    # family). Availability of these cannot be discovered from a capability statement, so they
    # are gated on explicit configuration instead of being assumed present.
    needs_patientview: bool = False
    # Documented, not enforced here: OpenMRS itself is the authority on whether the user may do
    # this. Recorded so an administrator can see which privilege a tool expects to need.
    expected_privilege: str = ""
    build: Callable[[Dict[str, Any], Dict[str, Any]], List[PlannedOperation]] = None  # type: ignore[assignment]
    summarise: Callable[[Dict[str, Any], Dict[str, Any]], str] = None  # type: ignore[assignment]


@dataclass
class ToolAvailability:
    tool: ToolSpec
    available: bool
    reason: Optional[str] = None


class ToolRegistry:
    def __init__(self, tools: List[ToolSpec], patientview_enabled: bool = False) -> None:
        self._tools = {tool.name: tool for tool in tools}
        self._patientview_enabled = patientview_enabled

    def all(self) -> List[ToolSpec]:
        return list(self._tools.values())

    def get(self, name: str) -> Optional[ToolSpec]:
        return self._tools.get(name)

    def availability(self, tool: ToolSpec, capabilities: FhirCapabilities) -> ToolAvailability:
        if tool.needs_patientview and not self._patientview_enabled:
            return ToolAvailability(
                tool,
                False,
                "Les champs specifiques a la neurochirurgie ne sont pas encore exposes en REST sur "
                "cette installation (voir section 4.3 de l'architecture).",
            )
        if tool.fhir_resource:
            if not capabilities.known:
                return ToolAvailability(
                    tool, False, "Les capacites FHIR de cette installation n'ont pas encore pu etre lues."
                )
            if not capabilities.supports(tool.fhir_resource, tool.fhir_interaction or "read"):
                return ToolAvailability(
                    tool,
                    False,
                    f"Cette installation d'OpenMRS n'expose pas {tool.fhir_resource} "
                    f"({tool.fhir_interaction}) en FHIR.",
                )
        return ToolAvailability(tool, True)

    def for_task(self, task: str, capabilities: FhirCapabilities) -> Optional[ToolAvailability]:
        for tool in self._tools.values():
            if tool.task == task:
                return self.availability(tool, capabilities)
        return None

    def describe(self, capabilities: FhirCapabilities) -> List[Dict[str, Any]]:
        rows = []
        for tool in self._tools.values():
            status = self.availability(tool, capabilities)
            rows.append(
                {
                    "name": tool.name,
                    "task": tool.task,
                    "writes": tool.writes,
                    "description": tool.description,
                    "expected_privilege": tool.expected_privilege,
                    "available": status.available,
                    "reason": status.reason,
                }
            )
        return rows


def missing_slots(tool: ToolSpec, slots: Dict[str, Any]) -> List[str]:
    return [slot for slot in tool.required_slots if not slots.get(slot)]
