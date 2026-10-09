"""The short-lived conversation buffer.

It exists for exactly two things: remembering what the assistant asked a clarifying question
about, and holding a pending write between the moment its summary is shown and the moment the
clinician confirms it. Nothing is persisted, entries expire, and the durable record of what
actually happened is written on the OpenMRS side (§1.3) - this process is not a system of record
and must not become one.
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional  # noqa: F401  (List used in PendingAction)

from .config import settings


@dataclass
class PendingOperation:
    """One concrete call that has been described to the clinician and is awaiting a yes."""

    method: str
    path: str
    body: Optional[Dict[str, Any]]
    summary: str
    task_type: str
    # Carried through the wait for confirmation, not just the body. A plan whose later call needs a
    # value from an earlier one (creating a patient reserves an identifier first) would otherwise
    # lose that builder while parked here and be sent with an empty body once the clinician says
    # yes - a write that fails after they approved it, which is the worst moment to fail.
    body_from_results: Optional[Callable[[List[Any]], Dict[str, Any]]] = None


@dataclass
class PendingAction:
    summary: str
    operations: List[PendingOperation]
    task_type: str
    # The user this was summarised for. A pending action is only ever executable by the same
    # clinician who was shown it, even if someone else guesses the conversation id.
    username: str


@dataclass
class ConversationState:
    conversation_id: str
    username: str
    pending: Optional[PendingAction] = None
    last_patient_uuid: Optional[str] = None
    # What the assistant was in the middle of when it asked a clarifying question, so the answer
    # completes that request instead of starting a new one (CA3 loops back into the pipeline).
    draft_task: Optional[str] = None
    draft_slots: Dict[str, Any] = field(default_factory=dict)
    # Which slot the last question was about, so the answer lands in *that* slot and is allowed to
    # replace what is already there. Without it a clarifying question whose answer refines an
    # existing slot can never be answered: the old value survives, the same question comes back, and
    # the conversation cannot move (observed live - "Precisez l'identifiant" repeated forever while
    # the stale name kept winning).
    awaiting_slot: Optional[str] = None
    updated_at: float = field(default_factory=time.time)


class ConversationStore:
    """In-memory, TTL-bounded, size-bounded, thread-safe."""

    def __init__(self, ttl_seconds: Optional[int] = None, max_entries: Optional[int] = None) -> None:
        self._ttl = ttl_seconds if ttl_seconds is not None else settings.conversation_ttl_seconds
        self._max = max_entries if max_entries is not None else settings.max_conversations
        self._entries: Dict[str, ConversationState] = {}
        self._lock = threading.Lock()

    def get(self, conversation_id: str, username: str) -> ConversationState:
        """Returns this conversation, creating it if needed.

        A conversation is keyed by id *and* owner: if the id is already in use by a different
        clinician the caller gets a fresh state rather than the other person's, so a guessed or
        leaked conversation id can never surface someone else's pending action or patient context.
        """
        now = time.time()
        with self._lock:
            self._evict_expired(now)
            state = self._entries.get(conversation_id)
            if state is None or state.username != username:
                state = ConversationState(conversation_id=conversation_id, username=username)
                self._entries[conversation_id] = state
            state.updated_at = now
            self._evict_overflow()
            return state

    def clear_pending(self, conversation_id: str) -> None:
        with self._lock:
            state = self._entries.get(conversation_id)
            if state is not None:
                state.pending = None

    def forget(self, conversation_id: str) -> None:
        with self._lock:
            self._entries.pop(conversation_id, None)

    def _evict_expired(self, now: float) -> None:
        expired = [key for key, state in self._entries.items() if now - state.updated_at > self._ttl]
        for key in expired:
            del self._entries[key]

    def _evict_overflow(self) -> None:
        while len(self._entries) > self._max:
            oldest = min(self._entries.items(), key=lambda item: item[1].updated_at)[0]
            del self._entries[oldest]


store = ConversationStore()
