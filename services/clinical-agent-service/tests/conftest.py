"""Test fixtures: a real RSA key pair, a real HTTP server, and real tokens.

Nothing about the security path is stubbed. The tokens the tests hand the service are signed with
a private key the tests hold and verified by the service against the matching public key, and the
mock OpenMRS verifies them again on arrival - so a test that passes has genuinely exercised the
mint/verify/delegate chain, not a mock of it.
"""

from __future__ import annotations

import base64
import os
import socket
import sys
import threading
import time
from pathlib import Path
from typing import Any, Dict, Optional

import jwt
import pytest
import uvicorn
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# Set before any app module is imported: the tool catalogue reads these once, at import time, and
# one of them changes a tool's required slots (with no identifier source configured, creating a
# patient must ask for an identifier instead of reserving one). Setting them in a fixture would be
# too late.
IDENTIFIER_TYPE = "OpenMRS ID"
IDGEN_SOURCE_UUID = "11111111-2222-3333-4444-555555555555"
os.environ.setdefault("OPENMRS_PATIENT_IDENTIFIER_TYPE", IDENTIFIER_TYPE)
os.environ.setdefault("OPENMRS_IDGEN_SOURCE_UUID", IDGEN_SOURCE_UUID)
IDENTIFIER_LOCATION_UUID = "99999999-8888-7777-6666-555555555555"
os.environ.setdefault("OPENMRS_IDENTIFIER_LOCATION_UUID", IDENTIFIER_LOCATION_UUID)
IDENTIFIER_TYPE_UUID = "05a29f94-c0ed-11e2-94be-8c13b969e334"
os.environ.setdefault("OPENMRS_PATIENT_IDENTIFIER_TYPE_UUID", IDENTIFIER_TYPE_UUID)

from tests.mock_openmrs import build_mock_openmrs  # noqa: E402

CHANNEL_SECRET = "test-channel-secret"

# What the mock's identifier source hands out. A check-digit-looking value on purpose: the reason
# the assistant reserves an identifier rather than inventing one is that real types validate it.
GENERATED_IDENTIFIER = "10023X"


def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


@pytest.fixture(scope="session")
def key_pair():
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public_der = private_key.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)
    return private_key, base64.b64encode(public_der).decode("ascii")


@pytest.fixture(scope="session")
def openmrs_server(key_pair):
    """A mock OpenMRS on a real port, so the service's HTTP client is exercised for real."""
    _, public_key_b64 = key_pair
    app = build_mock_openmrs(public_key_b64)
    port = _free_port()

    config = uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning")
    server = uvicorn.Server(config)
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()

    deadline = time.time() + 15
    while not server.started and time.time() < deadline:
        time.sleep(0.05)
    if not server.started:
        raise RuntimeError("The mock OpenMRS server did not start")

    yield {"app": app, "base_url": f"http://127.0.0.1:{port}"}

    server.should_exit = True
    thread.join(timeout=5)


@pytest.fixture(scope="session", autouse=True)
def configure_environment(openmrs_server, key_pair):
    """Points the service at the mock.

    The settings object is mutated rather than the environment, because the mock's port is only
    known once it is listening - by which time ``app.config`` has already been imported by the
    test modules and would not re-read the environment. Every consumer reads ``settings.x`` at
    call time, so mutating it is equivalent to having started the process with those values.
    """
    from app.config import settings

    _, public_key_b64 = key_pair
    os.environ["AGENT_CHANNEL_SECRET"] = CHANNEL_SECRET
    os.environ["OPENMRS_JWT_PUBLIC_KEY"] = public_key_b64

    settings.openmrs_base_url = openmrs_server["base_url"]
    settings.channel_secret = CHANNEL_SECRET
    settings.jwt_public_key_b64 = public_key_b64
    settings.patientview_tools_enabled = False
    settings.openmrs_verify_tls = False
    yield


@pytest.fixture
def mint(key_pair):
    """Mints a delegated token the same way agentgateway does."""
    private_key, _ = key_pair

    def _mint(
        username: str = "dr.benali",
        may_write: bool = True,
        purpose: str = "chat",
        conversation_id: str = "conv-1",
        ttl_seconds: int = 300,
        issuer: str = "openmrs-agentgateway",
        audience: str = "clinical-agent-service",
        issued_at: Optional[int] = None,
    ) -> str:
        now = issued_at if issued_at is not None else int(time.time())
        claims: Dict[str, Any] = {
            "iss": issuer,
            "aud": audience,
            "sub": username,
            "iat": now,
            "exp": now + ttl_seconds,
            "jti": f"jti-{now}",
            "user_uuid": f"uuid-{username}",
            "cid": conversation_id,
            "may_write": may_write,
            "purpose": purpose,
        }
        return jwt.encode(claims, private_key, algorithm="RS256")

    return _mint


@pytest.fixture(autouse=True)
def clean_conversations():
    from app.conversation import store

    store._entries.clear()  # noqa: SLF001 - resetting a process-local cache between tests
    yield
    store._entries.clear()  # noqa: SLF001


@pytest.fixture(autouse=True)
def mock_state(openmrs_server):
    """The mock's recorded calls and stored patients, reset before every test.

    Autouse so no test can pass because of a patient another test happened to leave behind.
    """
    state = openmrs_server["app"].state.mock
    state["calls"].clear()
    state["patients"].clear()
    # Cleared too, otherwise a test asserting that an identifier was reserved passes on one left
    # behind by an earlier test.
    state["generated_identifiers"].clear()
    return state
