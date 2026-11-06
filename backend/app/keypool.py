"""Backend-side API key pool: selection, status tracking, cooldowns, secure error log.

Design rules
- Keys never leave this module except via `acquire()` to the HTTP layer. Snapshots expose
  only an id (K1..) and the last 4 characters.
- Invalid keys are never retried automatically (only an admin "test" can revive them).
- A provider 429 puts THAT key into cooldown for the provider's Retry-After; the pool does not
  hand the same request to another key (this is resilience, not quota evasion).
"""
import re
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime, timezone

ACTIVE, COOLDOWN, INVALID = "active", "cooldown", "invalid"

_RE_KEY = re.compile(r"AIza[0-9A-Za-z_\-]{20,}")
_RE_PARAM = re.compile(r"(?i)\b(key|token|api_key)=([^&\s\"']+)")


def redact(text, secrets=()) -> str:
    t = str(text)
    for s in secrets:
        if s:
            t = t.replace(s, "[redacted]")
    t = _RE_KEY.sub("[redacted]", t)
    t = _RE_PARAM.sub(r"\1=[redacted]", t)
    return t[:300]


class PoolError(Exception):
    pass


class NotConfigured(PoolError):
    pass


class AllKeysInvalid(PoolError):
    pass


class AllKeysCoolingDown(PoolError):
    def __init__(self, wait: float, kind: str):
        super().__init__(f"all keys cooling down ({kind}) for {wait:.0f}s")
        self.wait = max(wait, 0.0)
        self.kind = kind


@dataclass
class Lease:
    id: str
    key: str = field(repr=False)


@dataclass
class KeyState:
    id: str
    key: str = field(repr=False)
    invalid_reason: str | None = None
    cooldown_until: float = 0.0
    cooldown_kind: str | None = None
    consecutive_failures: int = 0
    rate_limit_streak: int = 0
    last_success_at: float | None = None
    last_error_at: float | None = None
    last_error: str | None = None
    last_error_kind: str | None = None
    last_used_seq: int = 0
    requests: int = 0
    successes: int = 0
    failures: int = 0
    rate_limited: int = 0
    tokens: int = 0


def _iso(ts):
    return datetime.fromtimestamp(ts, timezone.utc).isoformat() if ts else None


class KeyPool:
    def __init__(self, keys, now=time.monotonic, wall=time.time, sink=None):
        self._lock = threading.Lock()
        self._now, self._wall, self._sink = now, wall, sink
        self._states = [KeyState(id=f"K{i + 1}", key=k) for i, k in enumerate(keys)]
        self._seq = 0
        self._events: deque = deque(maxlen=100)

    def set_sink(self, sink):
        self._sink = sink

    def size(self) -> int:
        return len(self._states)

    def ids(self):
        return [s.id for s in self._states]

    def key_for(self, key_id: str) -> str:
        return self._get(key_id).key

    # ---- selection -----------------------------------------------------
    def acquire(self, only: str | None = None) -> Lease:
        with self._lock:
            if not self._states:
                raise NotConfigured("no API keys configured")
            now = self._now()
            scope = [s for s in self._states if only is None or s.id == only]
            usable = [s for s in scope if not s.invalid_reason]
            if not usable:
                raise AllKeysInvalid("all configured keys are invalid")
            ready = [s for s in usable if s.cooldown_until <= now]
            if not ready:
                soonest = min(usable, key=lambda s: s.cooldown_until)
                raise AllKeysCoolingDown(soonest.cooldown_until - now, soonest.cooldown_kind or "transient")
            s = min(ready, key=lambda x: x.last_used_seq)  # least recently used -> even spread
            self._seq += 1
            s.last_used_seq = self._seq
            s.requests += 1
            return Lease(s.id, s.key)

    # ---- outcome reporting --------------------------------------------
    def success(self, key_id: str, tokens: int = 0, revive: bool = False):
        with self._lock:
            s = self._get(key_id)
            s.successes += 1
            s.tokens += max(int(tokens or 0), 0)
            s.consecutive_failures = 0
            s.rate_limit_streak = 0
            s.last_success_at = self._wall()
            if revive:
                s.invalid_reason = None
                s.cooldown_until = 0.0
                s.cooldown_kind = None

    def failure(self, key_id: str, kind: str, detail: str = "", retry_after: float | None = None,
                status: int | None = None):
        """kind: invalid | rate_limit | timeout | network | server"""
        with self._lock:
            s = self._get(key_id)
            now = self._now()
            msg = redact(detail, secrets=(s.key,))
            s.failures += 1
            s.last_error, s.last_error_kind, s.last_error_at = msg, kind, self._wall()
            if kind == "invalid":
                s.invalid_reason = msg or "invalid key"
            elif kind == "rate_limit":
                s.rate_limited += 1
                s.rate_limit_streak += 1
                delay = retry_after if retry_after is not None else min(60 * 2 ** (s.rate_limit_streak - 1), 900)
                s.cooldown_until = now + min(max(delay, 1.0), 3600.0)
                s.cooldown_kind = "rate_limit"
            else:
                s.consecutive_failures += 1
                if s.consecutive_failures >= 3:
                    s.cooldown_until = now + min(30 * 2 ** (s.consecutive_failures - 3), 300)
                    s.cooldown_kind = "transient"
            event = {"time": _iso(self._wall()), "key_id": s.id, "key": self._fp(s), "kind": kind,
                     "status": status, "message": msg}
            self._events.appendleft(event)
        if self._sink:
            try:
                self._sink(event)
            except Exception:  # logging must never break a request
                pass

    # ---- reporting -----------------------------------------------------
    @staticmethod
    def _fp(s: KeyState) -> str:
        return f"…{s.key[-4:]}" if len(s.key) >= 8 else "…****"

    def _get(self, key_id: str) -> KeyState:
        for s in self._states:
            if s.id == key_id:
                return s
        raise KeyError(key_id)

    def _status(self, s: KeyState, now: float) -> str:
        if s.invalid_reason:
            return INVALID
        return COOLDOWN if s.cooldown_until > now else ACTIVE

    def snapshot(self) -> dict:
        with self._lock:
            now = self._now()
            keys = []
            for s in self._states:
                st = self._status(s, now)
                keys.append({
                    "id": s.id, "key": self._fp(s), "status": st,
                    "cooldown_remaining_sec": round(max(s.cooldown_until - now, 0)) if st == COOLDOWN else 0,
                    "cooldown_reason": s.cooldown_kind if st == COOLDOWN else None,
                    "invalid_reason": s.invalid_reason,
                    "last_success_at": _iso(s.last_success_at),
                    "last_error_at": _iso(s.last_error_at), "last_error": s.last_error,
                    "last_error_kind": s.last_error_kind,
                    "requests": s.requests, "successes": s.successes, "failures": s.failures,
                    "rate_limited": s.rate_limited, "tokens": s.tokens,
                    "consecutive_failures": s.consecutive_failures,
                })
            events = list(self._events)[:50]
        counts = {k: sum(1 for x in keys if x["status"] == k) for k in (ACTIVE, INVALID, COOLDOWN)}
        return {
            "total": len(keys), "active": counts[ACTIVE], "invalid": counts[INVALID],
            "temporarily_unavailable": counts[COOLDOWN],
            "connection": ("not_configured" if not keys else "healthy" if counts[ACTIVE]
                           else "degraded" if counts[COOLDOWN] else "down"),
            "keys": keys, "events": events,
            "usage_note": "Counters are local to this backend process (reset on restart). "
                          "The Gemini API does not expose remaining quota; check Google AI Studio for quotas.",
        }
