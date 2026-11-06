import os
import re
from dotenv import load_dotenv
from pydantic_settings import BaseSettings, SettingsConfigDict

load_dotenv()  # makes .env visible to os.environ (used for GEMINI_API_KEY_n scanning)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = ""
    supabase_url: str = ""
    supabase_anon_key: str = ""
    supabase_service_role_key: str = ""
    storage_bucket: str = "documents"
    admin_token: str = ""

    gemini_model: str = "gemini-2.5-flash"
    gemini_embed_model: str = "gemini-embedding-001"
    embed_dim: int = 768

    ai_timeout_sec: float = 90.0
    ai_max_attempts: int = 4
    ai_max_inline_wait_sec: float = 15.0
    key_health_interval_sec: int = 600

    max_upload_mb: int = 25
    max_pages: int = 400
    max_ocr_pages: int = 40


settings = Settings()


def gemini_keys_from_env(environ=None) -> list[str]:
    """Collect GEMINI_API_KEY_<n> (sorted by n), GEMINI_API_KEYS (comma list) and legacy GEMINI_API_KEY.

    Adding a key = adding one more environment variable; no code change.
    """
    environ = os.environ if environ is None else environ
    numbered = []
    for name, value in environ.items():
        m = re.fullmatch(r"GEMINI_API_KEY_(\d+)", name)
        if m and value.strip():
            numbered.append((int(m.group(1)), value.strip()))
    numbered.sort()
    keys = [v for _, v in numbered]
    keys += [k.strip() for k in environ.get("GEMINI_API_KEYS", "").split(",") if k.strip()]
    legacy = environ.get("GEMINI_API_KEY", "").strip()
    if legacy:
        keys.append(legacy)
    seen, out = set(), []
    for k in keys:
        if k not in seen:
            seen.add(k)
            out.append(k)
    return out
