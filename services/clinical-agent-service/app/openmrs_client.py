"""The only way this service touches OpenMRS.

Every call goes out under the clinician's own delegated token, so OpenMRS's privilege checks are
the final word on whether it is allowed (CA7) - this service never holds an OpenMRS account of its
own and cannot widen anybody's access. Every call is also tagged with the conversation, the task
and the clinician's original words, which is what lets the gateway write an audit row an
administrator can actually make sense of later.
"""

from __future__ import annotations

import logging
import ssl
from dataclasses import dataclass
from typing import Any, Dict, Optional, Union
from urllib.parse import quote

import httpx

from .config import settings

log = logging.getLogger(__name__)

# Every delegated call goes through agentgateway's relay path rather than straight at
# /ws/fhir2/R4/... The reason is not style: fhir2 registers its own authentication filter on
# /ws/fhir2/*, and because fhir2 is a bundled module it starts - and so registers that filter -
# before agentgateway. Module filters run in start order, so fhir2 answers 401 before agentgateway
# can authenticate the clinician. The relay lands on a path fhir2 does not guard; agentgateway
# authenticates there and forwards to the real servlet, which module filters do not re-run on.
#
# Must match AgentGatewayConstants.RELAY_PATH_PREFIX on the OpenMRS side.
RELAY_PATH_PREFIX = "/module/agentgateway/relay"

_ssl_contexts: Dict[str, Union[bool, ssl.SSLContext]] = {}


def tls_verification() -> Union[bool, ssl.SSLContext]:
    """What to pass httpx as ``verify``.

    An internal CA has to be supplied as an explicit SSL context: httpx pins certifi's bundle for
    ``verify=True``, so the usual ``SSL_CERT_FILE`` environment variable is silently ignored, and
    passing a bare path is deprecated in recent httpx. Building the context ourselves works across
    versions and keeps verification *on* against a private CA, which is the whole point.
    """
    if not settings.openmrs_verify_tls:
        return False
    if not settings.openmrs_ca_bundle:
        return True

    cached = _ssl_contexts.get(settings.openmrs_ca_bundle)
    if cached is None:
        cached = ssl.create_default_context(cafile=settings.openmrs_ca_bundle)
        _ssl_contexts[settings.openmrs_ca_bundle] = cached
    return cached

# Headers can be truncated or rejected by proxies when they get long. The full prompt is not
# needed for the audit trail to be useful - the first part identifies the turn, and the whole
# conversation is reconstructable from the rows sharing a conversation id.
MAX_PROMPT_HEADER_CHARS = 700


@dataclass
class ApiResult:
    status: int
    body: Any

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300


class OpenmrsUnavailable(Exception):
    """OpenMRS could not be reached at all. Distinct from OpenMRS refusing something."""


def explain_failure(status: int, body: Any, *, searching: bool = False) -> str:
    """Turns a status code into the specific, non-technical reason CA8 asks for.

    Deliberately not a generic "an error occurred": the reasons below are the ones a clinician can
    actually act on - ask an administrator, check the name, try again, or correct what they typed.

    ``searching`` marks a call whose "nothing matched" answer is a **200 with an empty result**, which
    is every FHIR search. For those a 404 cannot mean "no such patient" - it means the request never
    reached the API - and saying "introuvable" turns an infrastructure fault into a clinical fact.
    That mistranslation cost this project a week: for roughly 45 minutes the audit filter on Server 1
    was not intercepting, every relayed call fell through to a 404, and the assistant told clinicians
    their patients did not exist. The searches were correct; the endpoint was missing.
    """
    if status in (401, 403):
        return (
            "Vous n'avez pas les droits necessaires dans OpenMRS pour cette operation. "
            "Contactez un administrateur si vous pensez que c'est une erreur."
        )
    if status == 404:
        if searching:
            return (
                "Je n'ai pas pu interroger le dossier patient : OpenMRS n'a pas repondu a cette "
                "requete (404). Ce n'est pas un patient introuvable, c'est un probleme technique - "
                "signalez-le a un administrateur."
            )
        return "Le dossier demande est introuvable dans OpenMRS."
    if status == 409:
        return "OpenMRS a refuse l'operation car elle entre en conflit avec une donnee existante."
    if status in (400, 422):
        detail = _extract_message(body)
        base = "Les informations fournies ont ete refusees par OpenMRS"
        return f"{base} : {detail}." if detail else f"{base} (donnees invalides ou incompletes)."
    if status >= 500:
        return "OpenMRS a rencontre une erreur interne. Reessayez dans un instant."
    return "L'operation n'a pas abouti."


def _extract_message(body: Any) -> Optional[str]:
    """Pulls the human-readable part out of an OpenMRS or FHIR error, dropping the rest.

    The raw payload never reaches the chat (CA8); this takes the one sentence that is useful and
    leaves stack traces, class names and field paths in the server log.
    """
    if not isinstance(body, dict):
        return None
    error = body.get("error")
    if isinstance(error, dict) and isinstance(error.get("message"), str):
        return error["message"][:200]
    issues = body.get("issue")
    if isinstance(issues, list) and issues:
        first = issues[0]
        if isinstance(first, dict):
            details = first.get("details")
            if isinstance(details, dict) and isinstance(details.get("text"), str):
                return details["text"][:200]
            if isinstance(first.get("diagnostics"), str):
                return first["diagnostics"][:200]
    return None


class OpenmrsClient:
    """Issues calls for one conversation turn, as one clinician."""

    def __init__(
        self,
        delegated_token: str,
        conversation_id: str,
        task_type: str = "query",
        prompt: str = "",
    ) -> None:
        self._token = delegated_token
        self._conversation_id = conversation_id
        self._task_type = task_type
        self._prompt = prompt

    def _headers(self) -> Dict[str, str]:
        headers = {
            "X-OpenMRS-Agent-Token": self._token,
            "X-OpenMRS-Agent-Conversation": self._conversation_id,
            "X-OpenMRS-Agent-Task": self._task_type,
            "Accept": "application/json",
        }
        if self._prompt:
            headers["X-OpenMRS-Agent-Prompt"] = quote(self._prompt[:MAX_PROMPT_HEADER_CHARS], safe="")
        return headers

    async def call(self, method: str, path: str, body: Optional[Dict[str, Any]] = None) -> ApiResult:
        url = f"{settings.openmrs_base_url}{RELAY_PATH_PREFIX}{path}"
        try:
            async with httpx.AsyncClient(
                timeout=settings.openmrs_timeout_seconds, verify=tls_verification()
            ) as client:
                response = await client.request(method, url, headers=self._headers(), json=body)
        except httpx.HTTPError as exc:
            log.warning("OpenMRS could not be reached for %s %s: %s", method, path, exc)
            raise OpenmrsUnavailable(str(exc)) from exc

        try:
            parsed: Any = response.json()
        except ValueError:
            parsed = response.text

        if not (200 <= response.status_code < 300):
            log.info("OpenMRS refused %s %s with HTTP %s", method, path, response.status_code)
        return ApiResult(status=response.status_code, body=parsed)


async def fetch_capability_statement(delegated_token: Optional[str] = None) -> Optional[Dict[str, Any]]:
    """Reads the deployed fhir2 module's own capability statement.

    This is the source of truth for which FHIR resources the assistant may target - never a list
    written down here, which would drift silently out of date the first time fhir2 is upgraded
    (ADR-10, open question #5).

    Deliberately **not** through the relay prefix, unlike every other call. ``/metadata`` is one of
    the two paths fhir2's own authentication filter exempts, so it can be read without a token -
    which is what lets the service discover its capabilities at startup, before any clinician has
    said anything. Routing it through the relay would need a token that does not exist yet.
    """
    url = f"{settings.openmrs_base_url}/ws/fhir2/R4/metadata"
    headers = {"Accept": "application/fhir+json"}
    if delegated_token:
        headers["X-OpenMRS-Agent-Token"] = delegated_token
    try:
        async with httpx.AsyncClient(
            timeout=settings.openmrs_timeout_seconds, verify=tls_verification()
        ) as client:
            response = await client.get(url, headers=headers)
    except httpx.HTTPError as exc:
        log.warning("Could not read the FHIR capability statement: %s", exc)
        return None

    if response.status_code != 200:
        log.warning("The FHIR capability statement returned HTTP %s", response.status_code)
        return None
    try:
        return response.json()
    except ValueError:
        log.warning("The FHIR capability statement was not valid JSON")
        return None
