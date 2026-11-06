"""Gemini client on top of the key pool.

Failure policy
- invalid / permission errors  -> key marked invalid (never auto-retried), request fails over
                                  to another configured key
- timeouts / network / 5xx     -> short backoff, retried (other keys naturally used first because
                                  selection is least-recently-used); repeated failures cool a key down
- 429                          -> that key cools down for the provider's Retry-After. The request is NOT
                                  re-sent to a different key. Short waits are honoured on the SAME key;
                                  long waits are returned to the caller as HTTP 429 + Retry-After.
- other 4xx (bad request, 404) -> caller's problem; no penalty to the key
"""
import base64
import json
import logging
import math
import threading
import time

import httpx

from . import keypool as kp
from .config import gemini_keys_from_env, settings

log = logging.getLogger("tradebrain.ai")
BASE = "https://generativelanguage.googleapis.com/v1beta"

pool = kp.KeyPool(gemini_keys_from_env())
_http_client: httpx.Client | None = None
_sleep = time.sleep


class AIError(Exception):
    """Generic AI failure (HTTP 502)."""


class AINotConfigured(AIError):
    """No keys configured (HTTP 503)."""


class AIUnavailable(AIError):
    def __init__(self, msg, retry_after: float | None = None):
        super().__init__(msg)
        self.retry_after = retry_after


class AIRateLimited(AIError):
    def __init__(self, retry_after: float, msg: str | None = None):
        self.retry_after = max(float(retry_after), 1.0)
        super().__init__(msg or f"AI is rate-limited. Please retry in about {math.ceil(self.retry_after)} seconds.")


def _http() -> httpx.Client:
    global _http_client
    if _http_client is None:
        _http_client = httpx.Client()
    return _http_client


# ---- response classification -------------------------------------------
def _error_message(text: str) -> str:
    try:
        return str(json.loads(text).get("error", {}).get("message") or text[:200])
    except Exception:
        return text[:200]


def _retry_after(headers, text: str) -> float | None:
    raw = headers.get("retry-after")
    if raw:
        try:
            return float(raw)
        except ValueError:
            pass  # HTTP-date form is ignored; fall back to body / default
    try:
        for d in json.loads(text).get("error", {}).get("details", []):
            if str(d.get("@type", "")).endswith("RetryInfo") and d.get("retryDelay"):
                return float(str(d["retryDelay"]).rstrip("s"))
    except Exception:
        pass
    return None


def _classify(status: int, headers, text: str):
    """-> (kind, retry_after, detail). kind: ok|rate_limit|invalid|server|client"""
    if status == 200:
        return "ok", None, ""
    msg = _error_message(text)
    low = (msg + text).lower()
    if status == 429:
        return "rate_limit", _retry_after(headers, text), msg
    if status in (401, 403) or (status == 400 and ("api key not valid" in low or "api_key_invalid" in low
                                                  or "api key expired" in low)):
        return "invalid", None, msg
    if status >= 500 or status == 408:
        return "server", None, msg
    return "client", None, msg


def _tokens(data: dict) -> int:
    try:
        return int(data.get("usageMetadata", {}).get("totalTokenCount", 0))
    except Exception:
        return 0


# ---- core request loop ---------------------------------------------------
def _post(path: str, body: dict, timeout: float | None = None) -> dict:
    timeout = timeout or settings.ai_timeout_sec
    attempts, pinned, waited = 0, None, False
    while attempts < max(settings.ai_max_attempts, 1):
        try:
            lease = pool.acquire(only=pinned)
        except kp.NotConfigured:
            raise AINotConfigured("AI is not configured on the server (no Gemini API keys set).")
        except kp.AllKeysInvalid:
            raise AIUnavailable("All configured AI keys are invalid. The administrator must replace them.")
        except kp.AllKeysCoolingDown as e:
            if e.kind == "rate_limit":
                raise AIRateLimited(e.wait or 1)
            raise AIUnavailable("AI service is temporarily unavailable. Please retry shortly.", e.wait or 30)
        pinned = None
        attempts += 1
        try:
            r = _http().post(f"{BASE}/{path}", json=body, headers={"x-goog-api-key": lease.key},
                             timeout=httpx.Timeout(timeout, connect=10.0))
        except httpx.TimeoutException:
            pool.failure(lease.id, "timeout", "request timed out")
            _sleep(0.5 * attempts)
            continue
        except httpx.HTTPError as e:
            pool.failure(lease.id, "network", type(e).__name__)
            _sleep(0.5 * attempts)
            continue

        kind, retry, detail = _classify(r.status_code, r.headers, r.text)
        if kind == "ok":
            data = r.json()
            pool.success(lease.id, tokens=_tokens(data))
            return data
        if kind == "client":
            log.warning("AI request rejected (%s): %s", r.status_code, kp.redact(detail))
            raise AIError(f"AI request rejected: {kp.redact(detail)}")
        pool.failure(lease.id, kind, detail, retry_after=retry, status=r.status_code)
        log.warning("AI key %s -> %s (%s)", lease.id, kind, r.status_code)
        if kind == "rate_limit":
            wait = retry if retry is not None else 60.0
            if not waited and wait <= settings.ai_max_inline_wait_sec:
                waited, pinned = True, lease.id  # honour Retry-After, retry on the SAME key once
                attempts -= 1
                _sleep(wait + 0.25)
                continue
            raise AIRateLimited(wait)
        if kind == "server":
            _sleep(0.5 * attempts)
        # kind == "invalid": key is now excluded by the pool; loop fails over to another key
    raise AIUnavailable("AI service is temporarily unavailable. Please retry shortly.")


# ---- public helpers ------------------------------------------------------
def text_part(t: str) -> dict:
    return {"text": t}


def image_part(data: bytes, mime: str) -> dict:
    return {"inlineData": {"mimeType": mime, "data": base64.b64encode(data).decode()}}


def generate(system: str, contents: list[dict], json_mode: bool = False) -> str:
    body = {"systemInstruction": {"parts": [{"text": system}]}, "contents": contents,
            "generationConfig": {"temperature": 0.3}}
    if json_mode:
        body["generationConfig"]["responseMimeType"] = "application/json"
    data = _post(f"models/{settings.gemini_model}:generateContent", body)
    try:
        text = "".join(p.get("text", "") for p in data["candidates"][0]["content"]["parts"])
    except (KeyError, IndexError, TypeError):
        raise AIError("The AI returned no content (the request may have been blocked).")
    return text


def embed(texts: list[str], task: str = "RETRIEVAL_DOCUMENT") -> list[list[float]]:
    out, model = [], settings.gemini_embed_model
    for i in range(0, len(texts), 50):
        batch = texts[i:i + 50]
        body = {"requests": [{"model": f"models/{model}", "content": {"parts": [{"text": t[:8000]}]},
                              "taskType": task, "outputDimensionality": settings.embed_dim} for t in batch]}
        data = _post(f"models/{model}:batchEmbedContents", body)
        out.extend(e["values"] for e in data["embeddings"])
    return out


# ---- connection tests & monitoring --------------------------------------
def test_key(key_id: str) -> dict:
    """Live connectivity test of one key (lists 1 model; no generation cost). Updates key state."""
    key = pool.key_for(key_id)  # KeyError if unknown
    try:
        r = _http().get(f"{BASE}/models", params={"pageSize": 1}, headers={"x-goog-api-key": key},
                        timeout=httpx.Timeout(15.0, connect=10.0))
    except httpx.TimeoutException:
        pool.failure(key_id, "timeout", "connection test timed out")
        return {"id": key_id, "ok": False, "result": "timeout"}
    except httpx.HTTPError as e:
        pool.failure(key_id, "network", type(e).__name__)
        return {"id": key_id, "ok": False, "result": "network_error"}
    kind, retry, detail = _classify(r.status_code, r.headers, r.text)
    if kind == "ok":
        pool.success(key_id, revive=True)
        return {"id": key_id, "ok": True, "result": "ok"}
    if kind != "client":
        pool.failure(key_id, kind, detail, retry_after=retry, status=r.status_code)
    return {"id": key_id, "ok": False, "result": kind}


def test_all() -> list[dict]:
    return [test_key(i) for i in pool.ids()]


def start_monitor(stop: threading.Event):
    """Periodic availability check of keys that are neither invalid nor cooling down."""
    interval = settings.key_health_interval_sec
    if interval <= 0 or pool.size() == 0:
        return None

    def loop():
        while not stop.wait(interval):
            snap = pool.snapshot()
            for k in snap["keys"]:
                if k["status"] == "active":
                    try:
                        test_key(k["id"])
                    except Exception:
                        log.exception("health check failed")

    t = threading.Thread(target=loop, name="key-monitor", daemon=True)
    t.start()
    return t
