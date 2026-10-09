"""Channel trust and user trust - the two independent checks every turn must pass (ADR-9).

*Channel trust* proves a request came from this hospital's OpenMRS instance and nothing else that
can reach this port. *User trust* establishes which clinician the turn is on behalf of, and it is
read from one place only: the verified payload of the signed token (ADR-13). There is deliberately
no code path here that accepts an identity asserted any other way - not a request field, not a
header, not a conversation the caller claims to be continuing.
"""

from __future__ import annotations

import base64
import hmac
from dataclasses import dataclass
from typing import Any, Dict, Optional

import jwt
from cryptography.hazmat.primitives.serialization import load_der_public_key

from .config import settings


class ChannelAuthError(Exception):
    """The caller did not prove it is the hospital's OpenMRS instance."""


class TokenError(Exception):
    """The delegated token is missing, malformed, unsigned by OpenMRS, or expired."""


@dataclass(frozen=True)
class ActingUser:
    """Who this turn runs as. Only ever built from a signature-verified token."""

    username: str
    user_uuid: Optional[str]
    conversation_id: Optional[str]
    may_write: bool
    purpose: str


def verify_channel_secret(presented: Optional[str]) -> None:
    """Constant-time comparison, so the endpoint cannot be used to guess the secret."""
    if not presented or not hmac.compare_digest(presented, settings.channel_secret):
        raise ChannelAuthError("The caller did not present a valid channel key")


def _public_key():
    """The verification key, as a :class:`TokenError` rather than a bare ``ValueError``.

    :func:`Settings.validate` rejects an unparseable key at startup, so reaching this path means
    the configuration changed under a running process. Either way the turn must be refused, and
    refused through the one exception type the endpoint knows how to answer - an uncaught
    ``ValueError`` here would become a 500 on every request and hide the cause.
    """
    try:
        return load_der_public_key(base64.b64decode(settings.jwt_public_key_b64))
    except Exception as exc:
        raise TokenError(f"The token verification key is unusable: {type(exc).__name__}: {exc}") from exc


def verify_delegated_token(token: Optional[str]) -> ActingUser:
    """Verifies the token and returns the clinician it names.

    The algorithm list is pinned to RS256. Passing the public key while allowing a symmetric
    algorithm would let anyone who has the (public!) key sign their own tokens - the classic JWT
    algorithm-confusion attack - so the one algorithm this service was designed for is the only
    one it will accept.
    """
    if not token:
        raise TokenError("No delegated token was presented")

    try:
        claims: Dict[str, Any] = jwt.decode(
            token,
            _public_key(),
            algorithms=["RS256"],
            audience=settings.jwt_audience,
            issuer=settings.jwt_issuer,
            options={"require": ["exp", "iat", "sub", "aud", "iss"]},
        )
    except jwt.ExpiredSignatureError as exc:
        raise TokenError("The delegated token has expired") from exc
    except jwt.InvalidTokenError as exc:
        raise TokenError(f"The delegated token could not be trusted: {exc}") from exc

    username = claims.get("sub")
    if not isinstance(username, str) or not username.strip():
        raise TokenError("The delegated token carries no usable subject")

    purpose = claims.get("purpose")
    if purpose != "chat":
        # A token minted for an administrator's rollback, or for an internal state read, must not
        # be usable to drive a conversation - each purpose is authorised by a different privilege.
        raise TokenError("The delegated token was not minted for a chat turn")

    return ActingUser(
        username=username.strip(),
        user_uuid=claims.get("user_uuid"),
        conversation_id=claims.get("cid"),
        may_write=bool(claims.get("may_write")),
        purpose=purpose,
    )
