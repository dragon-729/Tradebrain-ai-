import logging
import urllib.parse

import httpx

from .config import settings

log = logging.getLogger("tradebrain.storage")


def _enabled() -> bool:
    return bool(settings.supabase_url and settings.supabase_service_role_key)


def _headers(mime: str | None = None) -> dict:
    h = {"Authorization": f"Bearer {settings.supabase_service_role_key}",
         "apikey": settings.supabase_service_role_key}
    if mime:
        h["Content-Type"] = mime
    return h


def _url(path: str) -> str:
    return f"{settings.supabase_url}/storage/v1/object/{settings.storage_bucket}/{urllib.parse.quote(path)}"


def upload(path: str, data: bytes, mime: str) -> str | None:
    if not _enabled():
        return None
    try:
        r = httpx.post(_url(path), content=data, headers=_headers(mime), timeout=120)
        if r.status_code in (200, 201):
            return path
        log.warning("storage upload failed: %s", r.status_code)
    except httpx.HTTPError as e:
        log.warning("storage upload error: %s", type(e).__name__)
    return None


def download(path: str) -> bytes | None:
    if not _enabled() or not path:
        return None
    try:
        r = httpx.get(_url(path), headers=_headers(), timeout=120)
        return r.content if r.status_code == 200 else None
    except httpx.HTTPError:
        return None


def remove(path: str | None):
    if not _enabled() or not path:
        return
    try:
        httpx.delete(_url(path), headers=_headers(), timeout=30)
    except httpx.HTTPError:
        pass
