"""The identity and channel checks - the part where getting it wrong is a patient-safety issue."""

from __future__ import annotations

import base64
import time

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa

from app.security import ChannelAuthError, TokenError, verify_channel_secret, verify_delegated_token
from tests.conftest import CHANNEL_SECRET


def test_channel_secret_accepts_the_configured_value():
    verify_channel_secret(CHANNEL_SECRET)


@pytest.mark.parametrize("presented", [None, "", "wrong", CHANNEL_SECRET + "x", CHANNEL_SECRET[:-1]])
def test_channel_secret_rejects_anything_else(presented):
    with pytest.raises(ChannelAuthError):
        verify_channel_secret(presented)


def test_a_valid_token_yields_the_clinician_it_names(mint):
    user = verify_delegated_token(mint(username="dr.benali", may_write=True))
    assert user.username == "dr.benali"
    assert user.may_write is True
    assert user.purpose == "chat"


def test_may_write_defaults_to_false_rather_than_true(mint):
    assert verify_delegated_token(mint(may_write=False)).may_write is False


def test_expired_tokens_are_refused(mint):
    with pytest.raises(TokenError):
        verify_delegated_token(mint(issued_at=int(time.time()) - 3600, ttl_seconds=60))


def test_tokens_for_another_audience_are_refused(mint):
    with pytest.raises(TokenError):
        verify_delegated_token(mint(audience="some-other-service"))


def test_tokens_from_another_issuer_are_refused(mint):
    with pytest.raises(TokenError):
        verify_delegated_token(mint(issuer="somebody-else"))


def test_a_rollback_token_cannot_drive_a_conversation(mint):
    """Purposes are not interchangeable: each is authorised by a different privilege."""
    with pytest.raises(TokenError):
        verify_delegated_token(mint(purpose="rollback"))
    with pytest.raises(TokenError):
        verify_delegated_token(mint(purpose="internal_read"))


def test_a_token_signed_by_a_different_key_is_refused(key_pair):
    """The signature is what makes the identity trustworthy - anyone can write the claims."""
    _, public_key_b64 = key_pair
    impostor_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    now = int(time.time())
    forged = jwt.encode(
        {
            "iss": "openmrs-agentgateway",
            "aud": "clinical-agent-service",
            "sub": "dr.chief",
            "iat": now,
            "exp": now + 300,
            "may_write": True,
            "purpose": "chat",
        },
        impostor_key,
        algorithm="RS256",
    )
    assert base64.b64decode(public_key_b64)  # the real key is unrelated to the forged signature
    with pytest.raises(TokenError):
        verify_delegated_token(forged)


def test_an_unsigned_token_is_refused():
    """The classic bypass: claims that ask to be trusted without a signature at all."""
    now = int(time.time())
    unsigned = jwt.encode(
        {
            "iss": "openmrs-agentgateway",
            "aud": "clinical-agent-service",
            "sub": "dr.chief",
            "iat": now,
            "exp": now + 300,
            "may_write": True,
            "purpose": "chat",
        },
        key="",
        algorithm="none",
    )
    with pytest.raises(TokenError):
        verify_delegated_token(unsigned)


def test_a_missing_token_is_refused():
    with pytest.raises(TokenError):
        verify_delegated_token(None)


# --- the configuration mistake that cost this deployment a week ------------------------
#
# The signing PRIVATE key was pasted into OPENMRS_JWT_PUBLIC_KEY. Nothing rejected it: the service
# started cleanly and every turn then died with an uncaught ValueError from load_der_public_key,
# i.e. a 500 that looked like a broken assistant rather than one wrong setting. Both halves of the
# fix are pinned here - it is refused at startup, and if it somehow gets past that, a turn is
# refused rather than answered with a 500.


def _private_key_b64() -> str:
    """A PKCS#8 private key, base64'd - exactly what the OpenMRS settings page offers one field up."""
    from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    der = key.private_bytes(Encoding.DER, PrivateFormat.PKCS8, NoEncryption())
    return base64.b64encode(der).decode("ascii")


@pytest.mark.parametrize(
    "bad_key",
    [
        pytest.param(_private_key_b64(), id="the-private-key-by-mistake"),
        pytest.param("not-base64-at-all!!", id="not-base64"),
        pytest.param(base64.b64encode(b"random bytes").decode("ascii"), id="base64-but-not-a-key"),
    ],
)
def test_startup_refuses_a_key_that_is_not_a_public_key(bad_key, monkeypatch):
    from app.config import Settings

    settings = Settings()
    monkeypatch.setattr(settings, "channel_secret", "anything")
    monkeypatch.setattr(settings, "jwt_public_key_b64", bad_key)
    monkeypatch.setattr(settings, "openmrs_ca_bundle", "")

    with pytest.raises(RuntimeError) as raised:
        settings.validate()
    assert "OPENMRS_JWT_PUBLIC_KEY" in str(raised.value)


def test_startup_names_the_private_key_mistake_specifically(monkeypatch):
    from app.config import Settings

    settings = Settings()
    monkeypatch.setattr(settings, "channel_secret", "anything")
    monkeypatch.setattr(settings, "jwt_public_key_b64", _private_key_b64())
    monkeypatch.setattr(settings, "openmrs_ca_bundle", "")

    with pytest.raises(RuntimeError) as raised:
        settings.validate()
    # The operator needs to be told which field to copy, not that a DER parse failed.
    assert "Signing Public Key" in str(raised.value)
    assert "MIIBIjANBg" in str(raised.value)


def test_an_unusable_key_refuses_the_turn_instead_of_raising_valueerror(mint, monkeypatch):
    """A running process whose key goes bad must still fail as a TokenError, not a 500."""
    token = mint()
    monkeypatch.setattr("app.security.settings.jwt_public_key_b64", _private_key_b64())

    with pytest.raises(TokenError):
        verify_delegated_token(token)


def test_delegated_calls_go_through_the_relay_prefix():
    """The path shape is load-bearing, not cosmetic (Finding 7).

    A direct call to /ws/fhir2/R4/... is answered 401 by fhir2's own authentication filter, which
    runs before agentgateway's on the real deployment. If this prefix is ever dropped the assistant
    silently loses every FHIR call again, so it is pinned here rather than left to a code comment.
    """
    from app.openmrs_client import RELAY_PATH_PREFIX

    assert RELAY_PATH_PREFIX == "/module/agentgateway/relay"


def test_a_404_on_a_search_is_not_reported_as_a_missing_patient():
    """Finding 17. A FHIR search that matches nothing returns 200 with an empty Bundle, so a 404 means
    the request never reached the API. Reporting it as "introuvable" turned an infrastructure fault
    into a clinical fact and hid a broken audit filter for a week."""
    from app.openmrs_client import explain_failure

    searching = explain_failure(404, None, searching=True)
    assert searching != explain_failure(404, None), "a search 404 reads the same as a missing record"
    assert "technique" in searching.lower()
    assert "pas un patient introuvable" in searching.lower()

    # A read of one named record still means what it says.
    assert explain_failure(404, None) == "Le dossier demande est introuvable dans OpenMRS."
