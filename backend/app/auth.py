import time

import httpx
from fastapi import Header, HTTPException

from .config import settings

_cache: dict[str, tuple[str, float]] = {}


def current_user(authorization: str = Header(default="")) -> str:
    """Validates the Supabase access token server-side and returns the user id."""
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "Missing credentials")
    token = authorization[7:]
    hit = _cache.get(token)
    if hit and hit[1] > time.time():
        return hit[0]
    try:
        r = httpx.get(f"{settings.supabase_url}/auth/v1/user", timeout=10,
                      headers={"Authorization": f"Bearer {token}", "apikey": settings.supabase_anon_key})
    except httpx.HTTPError:
        raise HTTPException(503, "Authentication service unreachable")
    if r.status_code != 200:
        raise HTTPException(401, "Session expired. Please sign in again.")
    uid = r.json()["id"]
    if len(_cache) > 2000:
        _cache.clear()
    _cache[token] = (uid, time.time() + 60)
    return uid
