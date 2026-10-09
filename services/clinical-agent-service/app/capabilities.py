"""What the deployed OpenMRS actually supports, read from it rather than assumed.

`fhir2`'s resource coverage varies by module version. Hardcoding a list here would work on the
day it was written and quietly rot afterwards - the assistant would keep offering a task that
started failing at some upgrade, and the failure would surface as an unexplained error in a
clinician's chat rather than as a tool that is honestly reported as unavailable.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any, Dict, Optional, Set

from .config import settings
from .openmrs_client import fetch_capability_statement

log = logging.getLogger(__name__)


@dataclass
class FhirCapabilities:
    """Which {resource: {interaction, ...}} pairs the deployed fhir2 advertises."""

    resources: Dict[str, Set[str]] = field(default_factory=dict)
    fetched_at: Optional[float] = None
    error: Optional[str] = None

    @property
    def known(self) -> bool:
        return self.fetched_at is not None and not self.error

    def supports(self, resource: str, interaction: str) -> bool:
        return interaction in self.resources.get(resource, set())

    def describe(self) -> Dict[str, Any]:
        return {
            "known": self.known,
            "error": self.error,
            "fetched_at": self.fetched_at,
            "resources": {name: sorted(codes) for name, codes in sorted(self.resources.items())},
        }


def parse_capability_statement(statement: Dict[str, Any]) -> Dict[str, Set[str]]:
    resources: Dict[str, Set[str]] = {}
    for rest in statement.get("rest", []) or []:
        for resource in rest.get("resource", []) or []:
            name = resource.get("type")
            if not name:
                continue
            codes = {
                interaction.get("code")
                for interaction in (resource.get("interaction") or [])
                if interaction.get("code")
            }
            resources.setdefault(name, set()).update(codes)
    return resources


class CapabilityRegistry:
    """Holds the last known capability statement and decides when to go and look again."""

    def __init__(self) -> None:
        self._capabilities = FhirCapabilities()

    @property
    def current(self) -> FhirCapabilities:
        return self._capabilities

    def _is_stale(self) -> bool:
        if self._capabilities.fetched_at is None:
            return True
        return time.time() - self._capabilities.fetched_at > settings.capability_refresh_seconds

    async def refresh(self, delegated_token: Optional[str] = None, force: bool = False) -> FhirCapabilities:
        if not force and not self._is_stale():
            return self._capabilities

        statement = await fetch_capability_statement(delegated_token)
        if statement is None:
            # Keep whatever was known before rather than blanking it: a momentary network blip
            # should not take every FHIR-backed task offline.
            self._capabilities.error = "The capability statement could not be read from OpenMRS"
            if self._capabilities.fetched_at is None:
                log.warning("Starting with no known FHIR capabilities; FHIR-backed tasks stay disabled until one is read")
            return self._capabilities

        self._capabilities = FhirCapabilities(
            resources=parse_capability_statement(statement), fetched_at=time.time(), error=None
        )
        log.info("Read FHIR capabilities: %s", ", ".join(sorted(self._capabilities.resources)) or "(none)")
        return self._capabilities


registry = CapabilityRegistry()
