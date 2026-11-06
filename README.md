# TradeBrain AI

AI chart analysis · personal trading-knowledge library (RAG) · source-cited assistant.
Built and delivered **only through GitHub** — no Android Studio, no local builds.

Credits: GitHub [dragon-729](https://github.com/dragon-729) · Telegram [@YogeshBhai729](https://t.me/YogeshBhai729) (also shown inside the app).

## Layout
| Path | What |
|---|---|
| `android/` | Kotlin + Jetpack Compose + Material 3 app (Gradle) |
| `backend/` | FastAPI API: auth check, PDF/OCR pipeline, pgvector RAG, chart analysis, multi-key Gemini manager, `/admin` dashboard |
| `backend/migrations/001_init.sql` | PostgreSQL + pgvector schema with RLS |
| `.github/workflows/` | `android-build.yml` (APK artifact), `release.yml` (GitHub Release), `backend-ci.yml` (pytest) |

## Setup (all in the browser)
1. **Supabase**: create a project → SQL Editor → run `backend/migrations/001_init.sql`. Create a Storage bucket `documents` (private).
2. **Backend host** (Render/Fly/Railway, Docker from `backend/`): set the env vars from `backend/.env.example` as host secrets. Add as many `GEMINI_API_KEY_n` as you have (n = 1, 2, 3 …) — no code change. Set a long random `ADMIN_TOKEN`.
3. **GitHub secrets** (Settings → Secrets and variables → Actions): `API_BASE_URL` (your backend URL), `SUPABASE_URL`, `SUPABASE_ANON_KEY`. These three are public client config; **never** put Gemini or service-role keys in GitHub for the app.
4. Push to `main` → **Actions → Android Build → Artifacts → TradeBrainAI-debug-apk**.
5. Release: push a tag like `v0.2.0` (optional signing secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

## Multi-API-key system (backend only)
* Keys come from `GEMINI_API_KEY_1…n` (also `GEMINI_API_KEYS=a,b,c`). Never in the APK, never in git, never shown in full (dashboard shows `K1 …a1b2`).
* Selection: least-recently-used among healthy keys (even spread).
* `invalid`/permission errors → key marked invalid, **not retried automatically**; request fails over to another key. Admin "Test" can revive it.
* Timeouts/network/5xx → backoff + retry; 3 consecutive failures cool a key down.
* **HTTP 429 → that key cools down for the provider's `Retry-After`. The request is not re-sent to a different key.** Short waits (≤15 s) are honoured on the same key; longer ones return `429 + Retry-After` to the app. Keys exist for availability, not to bypass quotas — use only keys/projects you are authorized to use.
* Dashboard: `https://<backend>/admin/` (enter `ADMIN_TOKEN`): totals, active/invalid/cooling keys, connection state, last success, per-key counters/tokens, error log, "Test key / Test all". The Gemini API exposes no quota endpoint, so usage = local counters.
* Background health check every `KEY_HEALTH_INTERVAL_SEC` (default 600; 0 disables). Errors are also written to `api_key_events` (no key material).

## API (all `/v1/*` need `Authorization: Bearer <Supabase JWT>`)
`POST/GET /documents`, `PATCH /documents/{id}`, `POST /documents/{id}/reprocess`, `DELETE /documents/{id}` ·
`POST/GET /analyses`, `GET /analyses/{id}`, `POST /analyses/{id}/messages`, `DELETE /analyses/{id}` ·
`POST /chat` (scope: personal|public|both|general), `GET/DELETE /conversations…` · `GET /stats` ·
Admin (`X-Admin-Token`): `GET /v1/admin/keys`, `POST /v1/admin/keys/test`, `POST /v1/admin/keys/{id}/test` · `GET /health`.
Interactive docs: `/docs`.
