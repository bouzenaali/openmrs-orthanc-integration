"""Deployment settings, all from the environment.

Nothing here has a default that would let the service start up insecure: the channel secret and
the token verification key have no fallbacks, and :func:`Settings.validate` refuses to run without
them. A misconfigured assistant that refuses to start is an outage; one that starts and accepts
unsigned identities is a patient-safety incident.
"""

from __future__ import annotations

import base64
import os
import ssl
from dataclasses import dataclass, field

from cryptography.hazmat.primitives.serialization import load_der_public_key


@dataclass
class Settings:
    """Everything the service reads from its environment, resolved once at import time."""

    # --- OpenMRS -------------------------------------------------------------
    openmrs_base_url: str = field(
        default_factory=lambda: os.environ.get("OPENMRS_BASE_URL", "http://localhost:8080/openmrs").rstrip("/")
    )
    openmrs_timeout_seconds: float = field(
        default_factory=lambda: float(os.environ.get("OPENMRS_TIMEOUT_SECONDS", "20"))
    )
    openmrs_verify_tls: bool = field(
        default_factory=lambda: os.environ.get("OPENMRS_VERIFY_TLS", "true").lower() != "false"
    )
    # Path to the CA certificate that signed Server 1's certificate. A hospital LAN hostname
    # cannot get a certificate from a public CA, so without this the only way to talk to OpenMRS
    # over HTTPS would be to turn verification off - which is not TLS, it is encryption with no
    # idea who is on the other end. Point this at the internal CA instead.
    openmrs_ca_bundle: str = field(default_factory=lambda: os.environ.get("OPENMRS_CA_BUNDLE", ""))

    # --- Channel trust (OpenMRS <-> agent, server to server) -----------------
    channel_secret: str = field(default_factory=lambda: os.environ.get("AGENT_CHANNEL_SECRET", ""))

    # --- User trust (the delegated token) ------------------------------------
    # Base64 X.509 public key, exactly as agentgateway's global property holds it. Preferred over
    # fetching it, so the service can start with no dependency on OpenMRS being up.
    jwt_public_key_b64: str = field(default_factory=lambda: os.environ.get("OPENMRS_JWT_PUBLIC_KEY", ""))
    jwt_issuer: str = field(default_factory=lambda: os.environ.get("JWT_ISSUER", "openmrs-agentgateway"))
    jwt_audience: str = field(default_factory=lambda: os.environ.get("JWT_AUDIENCE", "clinical-agent-service"))

    # --- Conversation buffer --------------------------------------------------
    # Short-lived and in memory: the durable record of what happened lives in OpenMRS's
    # agentgateway_operation_log, not here. This server keeps no long-term store of PHI.
    conversation_ttl_seconds: int = field(
        default_factory=lambda: int(os.environ.get("CONVERSATION_TTL_SECONDS", "900"))
    )
    max_conversations: int = field(default_factory=lambda: int(os.environ.get("MAX_CONVERSATIONS", "500")))

    # --- Capability discovery -------------------------------------------------
    # ADR-10 / open question #5: what fhir2 actually supports is read from the deployed instance's
    # own capability statement, never assumed from documentation or baked into a list here.
    capability_refresh_seconds: int = field(
        default_factory=lambda: int(os.environ.get("CAPABILITY_REFRESH_SECONDS", "3600"))
    )

    # --- Optional department-specific surface (ADR-10, second tool family) ----
    # Off by default: agentgateway is generic, and so is this service's core. Turning it on
    # requires the matching prefix to be added to agentgateway.auditedPathPrefixes as well.
    patientview_tools_enabled: bool = field(
        default_factory=lambda: os.environ.get("PATIENTVIEW_TOOLS_ENABLED", "false").lower() == "true"
    )

    # --- interpretation (Phase 3) --------------------------------------------
    # "rules" is the deterministic engine; "medgemma" calls the local model and falls back to the
    # rules on any failure. Defaults to rules so that a deployment which has not stood up a GPU
    # keeps working rather than failing every turn.
    nlu_engine: str = field(default_factory=lambda: os.environ.get("NLU_ENGINE", "rules").lower())
    # vLLM's OpenAI-compatible base, on the private docker network. Never published: the model
    # endpoint is unauthenticated, and anything that could reach it could drive it.
    llm_base_url: str = field(
        default_factory=lambda: os.environ.get("LLM_BASE_URL", "http://vllm:8000/v1").rstrip("/")
    )
    llm_model: str = field(default_factory=lambda: os.environ.get("LLM_MODEL", "medgemma-4b-it"))
    llm_timeout_seconds: float = field(default_factory=lambda: float(os.environ.get("LLM_TIMEOUT_SECONDS", "25")))
    # One interpretation is a small JSON object. A generous cap only buys the chance to run long.
    llm_max_tokens: int = field(default_factory=lambda: int(os.environ.get("LLM_MAX_TOKENS", "512")))

    log_prompts: bool = field(default_factory=lambda: os.environ.get("LOG_PROMPTS", "false").lower() == "true")

    def validate(self) -> None:
        missing = []
        if not self.channel_secret:
            missing.append("AGENT_CHANNEL_SECRET")
        if not self.jwt_public_key_b64:
            missing.append("OPENMRS_JWT_PUBLIC_KEY")
        if missing:
            raise RuntimeError(
                "The clinical agent service cannot start without: "
                + ", ".join(missing)
                + ". Both come from the agentgateway module's settings in OpenMRS."
            )

        # A key that is not a public key at all would otherwise raise deep inside the verify
        # path on every single turn and surface as a 500 - which reads like the assistant being
        # broken rather than one setting being wrong. Pasting the "Signing Private Key" field by
        # mistake is the documented way to get here, and it has happened in this deployment, so
        # the message names that specific mistake rather than reporting a decoding error.
        try:
            load_der_public_key(base64.b64decode(self.jwt_public_key_b64))
        except Exception as exc:
            raise RuntimeError(
                "OPENMRS_JWT_PUBLIC_KEY is not a usable base64 X.509 public key "
                f"({type(exc).__name__}: {exc}). Copy the 'Signing Public Key' field from "
                "OpenMRS: about 390 characters, beginning MIIBIjANBg. A value beginning MIIEvA "
                "is the *private* key, which must never leave Server 1 - if that is what is "
                "configured here, rotate it before doing anything else."
            ) from exc

        # A CA bundle that is missing or unreadable would otherwise fail on every single turn,
        # as a 500 with an SSL error in the log - which reads like the assistant being broken
        # rather than a mounted file being wrong. Fail at startup instead, where it is obvious.
        if self.openmrs_ca_bundle:
            if not os.path.isfile(self.openmrs_ca_bundle):
                raise RuntimeError(
                    f"OPENMRS_CA_BUNDLE points at {self.openmrs_ca_bundle}, which is not a file. "
                    "Check that the CA certificate is mounted into the container."
                )
            try:
                ssl.create_default_context(cafile=self.openmrs_ca_bundle)
            except ssl.SSLError as exc:
                raise RuntimeError(
                    f"OPENMRS_CA_BUNDLE ({self.openmrs_ca_bundle}) is not a readable PEM "
                    f"certificate: {exc}"
                ) from exc


settings = Settings()
