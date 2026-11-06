# TradeBrain AI — Development Status

_Last updated: 2026-11-06. **Nothing here has been compiled or run against live services yet** (the authoring
environment had no network/Android SDK). First GitHub Actions run is the real build test._

## Verified so far
| Check | Result |
|---|---|
| Backend Python syntax (`py_compile`, all `app/` + `tests/`) | ✅ pass |
| Key pool logic smoke test (LRU spread, 429 cooldown, invalid exclusion, no key leakage, `textutil`) | ✅ pass (stdlib-only script) |
| `pytest` suite (`tests/`, 21 tests incl. admin API) | ⏳ written, **not run** (deps unavailable offline) → runs in `backend-ci.yml` |
| Android compile / unit tests / APK | ⏳ **not run** → runs in `android-build.yml` |
| Live Gemini / Supabase integration | ❌ not tested (needs your keys/project) |

## Multi-API-key system — implemented (backend)
- [x] `GEMINI_API_KEY_1..n` (+ `GEMINI_API_KEYS`) env loading, no limit on count
- [x] LRU selection, per-key status (active / cooldown / invalid), counters, token usage
- [x] Invalid-key detection (400 API_KEY_INVALID, 401/403) — never auto-retried
- [x] Timeout / network / 5xx retry with backoff; cooldown after 3 consecutive failures
- [x] 429: provider Retry-After honoured; **no cross-key rerouting of the limited request**; 429+Retry-After returned to app
- [x] Secret redaction in logs/errors; `api_key_events` audit table
- [x] Admin API + dashboard (`/admin/`), masked keys, test key / test all, background health monitor
- [x] Android shows friendly rate-limit / backend-unreachable messages
- Tests written for: one key, five keys, missing key, invalid key, all invalid, network failure, timeout, rate limit (long + short), bad request, redaction, connection test, env scanning, admin auth/masking; Android: backend unreachable, rate-limit message, error parsing.

## Product modules
| Module | State |
|---|---|
| Auth (email/password, reset, encrypted token storage, refresh) | implemented, untested |
| Distribution: upload PDF/TXT/image, OCR (Gemini vision), chunking, embeddings, summary, delete cascade, reprocess, public/private | implemented, untested |
| Personal AI assistant: hybrid RAG (pgvector + FTS, RRF), scopes, citation validation, history in DB | implemented, untested |
| Analyze: image → structured analysis, library matches, follow-up chat, history/delete | implemented, untested |
| Home dashboard (stats, recent analyses) | implemented, untested |
| Credits (GitHub + Telegram) in Profile and login screen | done |
| DB schema + RLS | written, not applied |

## Pending (not built — do not treat as done)
Community (posts, comments, likes, follow, moderation) · Google Sign-In · camera capture & image crop/rotate ·
DOCX/PPTX · folders/tags/notes/rename · global search UI · notifications/push · selected-users sharing ·
conversation-history UI (API exists) · answer regeneration · Room/Hilt/WorkManager (plain coroutines used) ·
account deletion/data export · malware scanning · Android instrumented tests · AI image test set · live market data.

## Known limitations
- Key counters/cooldowns are in-memory (reset on restart; multiple backend instances don't share state).
- Original chart images are stored in Postgres (`bytea`); move to Storage for scale.
- `EMBED_DIM` must equal the `vector(768)` column.
