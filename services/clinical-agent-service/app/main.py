"""The Clinical Agent Service - one process, one externally reachable endpoint.

`POST /chat` is the entire surface OpenMRS talks to, which keeps the Server 1 to Server 2 boundary
a single thing to firewall, monitor and reason about. `/health` and `/capabilities` are
operational endpoints for the administrator, not part of the clinical path.

This service holds no OpenMRS credentials of its own and no durable store. Everything it can do,
it does as the clinician whose signed token arrived with the turn.
"""

from __future__ import annotations

import logging
import uuid
from typing import Any, Dict, Optional

from fastapi import FastAPI, Header, HTTPException, Request
from pydantic import BaseModel, Field

from .capabilities import registry as capability_registry
from .config import settings
from .orchestrator import Orchestrator
from .security import ChannelAuthError, TokenError, verify_channel_secret, verify_delegated_token
from .tools.catalog import build_registry

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
log = logging.getLogger("clinical-agent")

app = FastAPI(
    title="Clinical Agent Service",
    version="1.0.0",
    description="Conversational access to OpenMRS for the CHU Blida neurosurgery department.",
)

orchestrator = Orchestrator()
tool_registry = build_registry(settings.patientview_tools_enabled)


class ChatContext(BaseModel):
    patient_uuid: Optional[str] = None
    locale: str = "fr"


class ChatRequest(BaseModel):
    """What agentgateway relays.

    Note what is absent: any user id. Identity comes from the signed token and nowhere else
    (ADR-13). A second, unsigned assertion of who the caller is would be one more thing that could
    be believed by mistake.
    """

    conversation_id: str = Field(..., min_length=1, max_length=64)
    prompt: str = Field(..., min_length=1, max_length=4000)
    delegated_token: str = Field(..., min_length=1)
    context: ChatContext = Field(default_factory=ChatContext)


@app.on_event("startup")
async def on_startup() -> None:
    settings.validate()
    # Best-effort: the capability statement is read again on the first turn if OpenMRS was not up
    # yet. Failing to read it disables the FHIR-backed tools with an explicit reason rather than
    # letting them fail obscurely later.
    await capability_registry.refresh(force=True)
    log.info(
        "Clinical Agent Service ready. OpenMRS: %s. Neurosurgery-specific tools: %s.",
        settings.openmrs_base_url,
        "enabled" if settings.patientview_tools_enabled else "disabled",
    )


@app.get("/health")
async def health() -> Dict[str, Any]:
    capabilities = capability_registry.current
    return {
        "status": "ok",
        "openmrs_base_url": settings.openmrs_base_url,
        "fhir_capabilities_known": capabilities.known,
        "fhir_capabilities_error": capabilities.error,
    }


@app.get("/capabilities")
async def capabilities(
    channel_key: Optional[str] = Header(default=None, alias="X-Agent-Channel-Key"),
) -> Dict[str, Any]:
    """What the assistant can and cannot do on this installation, and why.

    Gated on the channel secret: it describes the hospital's OpenMRS configuration, which is not
    something to hand out to anything that can reach the port.
    """
    _require_channel(channel_key)
    current = capability_registry.current
    return {"fhir": current.describe(), "tools": tool_registry.describe(current)}


@app.post("/chat")
async def chat(
    payload: ChatRequest,
    request: Request,
    channel_key: Optional[str] = Header(default=None, alias="X-Agent-Channel-Key"),
) -> Dict[str, Any]:
    _require_channel(channel_key)

    try:
        user = verify_delegated_token(payload.delegated_token)
    except TokenError as exc:
        log.warning("Rejected a turn from %s: %s", request.client.host if request.client else "?", exc)
        raise HTTPException(status_code=401, detail="The delegated token could not be trusted") from exc

    # The conversation id is only ever a grouping key. It is never trusted to carry identity or
    # permission - both of those are re-derived from the token on every single turn.
    conversation_id = payload.conversation_id or str(uuid.uuid4())

    if settings.log_prompts:
        log.info("[%s] %s: %s", conversation_id, user.username, payload.prompt)
    else:
        log.info("[%s] turn from %s (%d chars)", conversation_id, user.username, len(payload.prompt))

    # Re-read the capability statement when it has gone stale, or was never readable at startup.
    await capability_registry.refresh(delegated_token=payload.delegated_token)

    result = await orchestrator.handle_turn(
        prompt=payload.prompt,
        delegated_token=payload.delegated_token,
        user=user,
        conversation_id=conversation_id,
        context=payload.context.model_dump(),
    )

    log.info("[%s] -> %s (%s)", conversation_id, result.state, result.task_type)
    return result.to_response(conversation_id)


def _require_channel(channel_key: Optional[str]) -> None:
    try:
        verify_channel_secret(channel_key)
    except ChannelAuthError as exc:
        raise HTTPException(status_code=403, detail="Unrecognised caller") from exc
