import httpx
import pytest

from app import ai
from app.config import gemini_keys_from_env
from app.keypool import KeyPool, redact

OK = {"candidates": [{"content": {"parts": [{"text": "hello"}]}}], "usageMetadata": {"totalTokenCount": 7}}
INVALID = {"error": {"code": 400, "message": "API key not valid. Please pass a valid API key.",
                     "status": "INVALID_ARGUMENT"}}
KEY = lambda i: f"AIzaSyFAKEKEYFORTESTING{i:02d}abcdef"


class Clock:
    t = 1000.0

    def __call__(self):
        return self.t


@pytest.fixture
def env(monkeypatch):
    clock = Clock()
    calls = []
    sleeps = []

    def setup(n_keys, handler):
        pool = KeyPool([KEY(i) for i in range(n_keys)], now=clock)

        def wrapped(request):
            calls.append(request.headers["x-goog-api-key"])
            return handler(request)

        monkeypatch.setattr(ai, "pool", pool)
        monkeypatch.setattr(ai, "_http_client", httpx.Client(transport=httpx.MockTransport(wrapped)))

        def fake_sleep(s):
            sleeps.append(s)
            clock.t += s

        monkeypatch.setattr(ai, "_sleep", fake_sleep)
        return pool

    return setup, calls, sleeps, clock


def ok(_):
    return httpx.Response(200, json=OK)


def test_missing_key(env):
    setup, *_ = env
    setup(0, ok)
    with pytest.raises(ai.AINotConfigured):
        ai.generate("s", [])


def test_single_key_success_and_usage(env):
    setup, calls, *_ = env
    pool = setup(1, ok)
    assert ai.generate("s", [{"role": "user", "parts": [{"text": "hi"}]}]) == "hello"
    snap = pool.snapshot()
    assert snap["total"] == 1 and snap["active"] == 1 and snap["keys"][0]["tokens"] == 7
    assert calls == [KEY(0)]


def test_five_keys_spread_evenly(env):
    setup, calls, *_ = env
    setup(5, ok)
    for _ in range(10):
        ai.generate("s", [])
    assert sorted(set(calls)) == [KEY(i) for i in range(5)]
    assert all(calls.count(KEY(i)) == 2 for i in range(5))


def test_invalid_key_marked_and_never_retried(env):
    setup, calls, *_ = env

    def handler(req):
        if req.headers["x-goog-api-key"] == KEY(0):
            return httpx.Response(400, json=INVALID)
        return httpx.Response(200, json=OK)

    pool = setup(2, handler)
    assert ai.generate("s", []) == "hello"       # failed over to the valid key
    for _ in range(5):
        ai.generate("s", [])
    assert calls.count(KEY(0)) == 1               # invalid key hit exactly once
    snap = pool.snapshot()
    assert snap["invalid"] == 1 and snap["keys"][0]["status"] == "invalid"


def test_all_keys_invalid(env):
    setup, *_ = env
    setup(3, lambda r: httpx.Response(403, json={"error": {"message": "permission denied"}}))
    with pytest.raises(ai.AIUnavailable):
        ai.generate("s", [])


def test_rate_limit_long_retry_after_not_rerouted(env):
    setup, calls, _, clock = env
    pool = setup(2, lambda r: httpx.Response(429, headers={"retry-after": "120"}, json={"error": {"message": "quota"}}))
    with pytest.raises(ai.AIRateLimited) as e:
        ai.generate("s", [])
    assert e.value.retry_after == 120 and len(calls) == 1      # request was NOT re-sent to the other key
    snap = pool.snapshot()
    assert snap["keys"][0]["status"] == "cooldown" and snap["keys"][0]["cooldown_remaining_sec"] == 120
    clock.t += 121
    assert pool.snapshot()["keys"][0]["status"] == "active"


def test_rate_limit_short_retry_after_waits_on_same_key(env):
    setup, calls, sleeps, _ = env
    state = {"n": 0}

    def handler(req):
        state["n"] += 1
        if state["n"] == 1:
            return httpx.Response(429, json={"error": {"message": "slow down", "details": [
                {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "2s"}]}})
        return httpx.Response(200, json=OK)

    setup(2, handler)
    assert ai.generate("s", []) == "hello"
    assert calls[0] == calls[1] and sleeps and 2 <= sleeps[0] < 3


def test_network_failure(env):
    setup, calls, *_ = env

    def boom(req):
        raise httpx.ConnectError("down")

    pool = setup(1, boom)
    with pytest.raises(ai.AIUnavailable):
        ai.generate("s", [])
    assert len(calls) == 3                                   # 3 failures -> key cooled down, no hammering
    assert pool.snapshot()["keys"][0]["status"] == "cooldown"


def test_timeout_fails_over_to_next_key(env):
    setup, calls, *_ = env

    def handler(req):
        if req.headers["x-goog-api-key"] == KEY(0):
            raise httpx.ReadTimeout("slow")
        return httpx.Response(200, json=OK)

    setup(2, handler)
    assert ai.generate("s", []) == "hello" and calls == [KEY(0), KEY(1)]


def test_bad_request_does_not_penalise_key(env):
    setup, *_ = env
    pool = setup(1, lambda r: httpx.Response(400, json={"error": {"message": "Invalid JSON payload"}}))
    with pytest.raises(ai.AIError):
        ai.generate("s", [])
    assert pool.snapshot()["active"] == 1 and pool.snapshot()["keys"][0]["failures"] == 0


def test_snapshot_never_contains_full_keys_or_leaks_in_errors(env):
    setup, *_ = env
    pool = setup(2, lambda r: httpx.Response(500, text=f"boom key={r.headers['x-goog-api-key']} {KEY(0)}"))
    with pytest.raises(ai.AIError):
        ai.generate("s", [])
    dump = str(pool.snapshot())
    assert KEY(0) not in dump and KEY(1) not in dump and "…" in dump


def test_connection_test_revives_invalid_key(env):
    setup, *_ = env
    mode = {"valid": False}

    def handler(req):
        if mode["valid"]:
            return httpx.Response(200, json={"models": []})
        return httpx.Response(400, json=INVALID)

    pool = setup(1, handler)
    assert ai.test_key("K1")["result"] == "invalid" and pool.snapshot()["invalid"] == 1
    mode["valid"] = True
    assert ai.test_key("K1")["ok"] and pool.snapshot()["active"] == 1


def test_env_scanning_supports_any_number_of_keys():
    keys = gemini_keys_from_env({"GEMINI_API_KEY_2": "b", "GEMINI_API_KEY_1": "a", "GEMINI_API_KEY_10": "j",
                                 "GEMINI_API_KEY_3": " ", "GEMINI_API_KEYS": "x, a ,y", "UNRELATED": "z"})
    assert keys == ["a", "b", "j", "x", "y"]


def test_redact():
    assert "AIza" not in redact("failed https://x?key=AIzaSyABCDEFGHIJKLMNOPQRSTUV123")
