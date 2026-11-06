import hashlib
import logging
import math
import re
import secrets
import threading
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal

from fastapi import BackgroundTasks, Depends, FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import ai, analysis, chat, db, extract, pipeline, ratelimit, storage
from .auth import current_user
from .config import settings

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("tradebrain")

DOC_COLS = ("id,title,filename,mime,size_bytes,category,summary,concepts,visibility,status,error,"
            "page_count,created_at")
IMG = {"image/jpeg", "image/png", "image/webp"}
_stop = threading.Event()


def _event_sink(e: dict):
    db.execute("insert into api_key_events (key_id, fingerprint, kind, status, message) values (%s,%s,%s,%s,%s)",
               (e["key_id"], e["key"], e["kind"], e["status"], e["message"]))


@asynccontextmanager
async def lifespan(app: FastAPI):
    db.init()
    ai.pool.set_sink(_event_sink)
    if ai.pool.size() == 0:
        log.warning("No GEMINI_API_KEY_n configured: AI endpoints will return 503")
    else:
        log.info("Loaded %d Gemini API key(s)", ai.pool.size())
    ai.start_monitor(_stop)
    yield
    _stop.set()
    db.close()


app = FastAPI(title="TradeBrain AI API", version="0.2.0", lifespan=lifespan)


# ---- error mapping ------------------------------------------------------
@app.exception_handler(ai.AINotConfigured)
async def _nc(_, e):
    return JSONResponse({"detail": str(e)}, status_code=503)


@app.exception_handler(ai.AIRateLimited)
async def _rl(_, e):
    return JSONResponse({"detail": str(e)}, status_code=429, headers={"Retry-After": str(math.ceil(e.retry_after))})


@app.exception_handler(ai.AIUnavailable)
async def _un(_, e):
    h = {"Retry-After": str(math.ceil(e.retry_after))} if e.retry_after else {}
    return JSONResponse({"detail": str(e)}, status_code=503, headers=h)


@app.exception_handler(ai.AIError)
async def _ai(_, e):
    return JSONResponse({"detail": str(e)}, status_code=502)


@app.middleware("http")
async def _admin_headers(request: Request, call_next):
    resp = await call_next(request)
    if request.url.path.startswith("/admin"):
        resp.headers["Content-Security-Policy"] = ("default-src 'self'; style-src 'self' 'unsafe-inline'; "
                                                   "frame-ancestors 'none'")
        resp.headers["Cache-Control"] = "no-store"
    return resp


@app.get("/health")
def health():
    return {"status": "ok", "ai": ai.pool.snapshot()["connection"]}


# ---- documents ----------------------------------------------------------
@app.post("/v1/documents", status_code=202)
def upload_document(background: BackgroundTasks, file: UploadFile = File(...), visibility: str = Form("private"),
                    user_id: str = Depends(current_user)):
    ratelimit.check(user_id, "upload", 20, 60)
    if visibility not in ("private", "public"):
        raise HTTPException(400, "Invalid visibility")
    name = file.filename or "document"
    mime = extract.detect_mime(name, file.content_type)
    if not extract.supported(mime, name):
        raise HTTPException(415, "Unsupported file type. Use PDF, TXT, JPG, PNG or WEBP.")
    limit = settings.max_upload_mb * 1024 * 1024
    data = file.file.read(limit + 1)
    if not data:
        raise HTTPException(400, "The file is empty")
    if len(data) > limit:
        raise HTTPException(413, f"File too large (max {settings.max_upload_mb} MB)")
    digest = hashlib.sha256(data).hexdigest()
    if db.fetch_one("select 1 from documents where user_id=%s and sha256=%s", (user_id, digest)):
        raise HTTPException(409, "You already uploaded this file")
    doc_id = str(uuid.uuid4())
    safe = re.sub(r"[^A-Za-z0-9._-]", "_", name)[:100]
    path = storage.upload(f"{user_id}/{doc_id}/{safe}", data, mime)
    row = db.fetch_one(
        "insert into documents (id,user_id,title,filename,mime,size_bytes,sha256,visibility,status,storage_path) "
        f"values (%s,%s,%s,%s,%s,%s,%s,%s,'processing',%s) returning {DOC_COLS}",
        (doc_id, user_id, name.rsplit(".", 1)[0][:120], name, mime, len(data), digest, visibility, path))
    background.add_task(pipeline.process_document, doc_id, user_id, data, mime, name)
    return row


@app.get("/v1/documents")
def list_documents(user_id: str = Depends(current_user)):
    return db.fetch_all(f"select {DOC_COLS} from documents where user_id=%s order by created_at desc limit 200",
                        (user_id,))


class VisibilityIn(BaseModel):
    visibility: Literal["private", "public"]


@app.patch("/v1/documents/{doc_id}")
def set_visibility(doc_id: str, body: VisibilityIn, user_id: str = Depends(current_user)):
    row = db.fetch_one(f"update documents set visibility=%s, updated_at=now() where id=%s and user_id=%s "
                       f"returning {DOC_COLS}", (body.visibility, doc_id, user_id))
    if not row:
        raise HTTPException(404, "Document not found")
    return row


@app.post("/v1/documents/{doc_id}/reprocess", status_code=202)
def reprocess(doc_id: str, background: BackgroundTasks, user_id: str = Depends(current_user)):
    ratelimit.check(user_id, "upload", 20, 60)
    d = db.fetch_one("select id, mime, filename, storage_path from documents where id=%s and user_id=%s",
                     (doc_id, user_id))
    if not d:
        raise HTTPException(404, "Document not found")
    data = storage.download(d["storage_path"])
    if data is None:
        raise HTTPException(409, "The original file is not available; please upload it again.")
    db.execute("update documents set status='processing', error=null where id=%s", (doc_id,))
    background.add_task(pipeline.process_document, doc_id, user_id, data, d["mime"], d["filename"])
    return {"id": doc_id, "status": "processing"}


@app.delete("/v1/documents/{doc_id}", status_code=204)
def delete_document(doc_id: str, user_id: str = Depends(current_user)):
    d = db.fetch_one("select storage_path from documents where id=%s and user_id=%s", (doc_id, user_id))
    if not d:
        raise HTTPException(404, "Document not found")
    storage.remove(d["storage_path"])
    db.execute("delete from documents where id=%s and user_id=%s", (doc_id, user_id))  # cascades chunks/embeddings


# ---- analyses -----------------------------------------------------------
@app.post("/v1/analyses")
def create_analysis(image: UploadFile = File(...), user_id: str = Depends(current_user)):
    ratelimit.check(user_id, "ai", 30, 60)
    mime = image.content_type or ""
    if mime not in IMG:
        raise HTTPException(415, "Unsupported image type. Use JPG, PNG or WEBP.")
    data = image.file.read(10 * 1024 * 1024 + 1)
    if not data or len(data) > 10 * 1024 * 1024:
        raise HTTPException(413, "Image must be between 1 byte and 10 MB")
    return analysis.run(user_id, data, mime)


@app.get("/v1/analyses")
def list_analyses(user_id: str = Depends(current_user)):
    return db.fetch_all("select id, title, created_at, result->'patterns'->0->>'name' as pattern from chart_analyses "
                        "where user_id=%s order by created_at desc limit 100", (user_id,))


@app.get("/v1/analyses/{aid}")
def get_analysis(aid: str, user_id: str = Depends(current_user)):
    return analysis.get(user_id, aid)


class AskIn(BaseModel):
    question: str = Field(min_length=1, max_length=2000)


@app.post("/v1/analyses/{aid}/messages")
def ask_about(aid: str, body: AskIn, user_id: str = Depends(current_user)):
    ratelimit.check(user_id, "ai", 30, 60)
    return analysis.ask(user_id, aid, body.question)


@app.delete("/v1/analyses/{aid}", status_code=204)
def delete_analysis(aid: str, user_id: str = Depends(current_user)):
    db.execute("delete from chart_analyses where id=%s and user_id=%s", (aid, user_id))


# ---- assistant ----------------------------------------------------------
class ChatIn(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    conversation_id: str | None = None
    scope: Literal["personal", "public", "both", "general"] = "personal"


@app.post("/v1/chat")
def chat_endpoint(body: ChatIn, user_id: str = Depends(current_user)):
    ratelimit.check(user_id, "ai", 30, 60)
    return chat.answer(user_id, body.message, body.scope, body.conversation_id)


@app.get("/v1/conversations")
def conversations(user_id: str = Depends(current_user)):
    return db.fetch_all("select id, title, scope, created_at from ai_conversations where user_id=%s "
                        "order by created_at desc limit 100", (user_id,))


@app.get("/v1/conversations/{cid}")
def conversation(cid: str, user_id: str = Depends(current_user)):
    if not db.fetch_one("select 1 from ai_conversations where id=%s and user_id=%s", (cid, user_id)):
        raise HTTPException(404, "Conversation not found")
    return db.fetch_all("select role, content, sources, created_at from ai_messages where conversation_id=%s "
                        "order by id", (cid,))


@app.delete("/v1/conversations/{cid}", status_code=204)
def delete_conversation(cid: str, user_id: str = Depends(current_user)):
    db.execute("delete from ai_conversations where id=%s and user_id=%s", (cid, user_id))


@app.get("/v1/stats")
def stats(user_id: str = Depends(current_user)):
    return db.fetch_one(
        "select (select count(*) from documents where user_id=%s)::int as documents, "
        "(select count(*) from documents where user_id=%s and status='ready')::int as ready_documents, "
        "(select count(*) from chart_analyses where user_id=%s)::int as analyses, "
        "(select coalesce(sum(jsonb_array_length(concepts)),0) from documents where user_id=%s)::int as concepts",
        (user_id,) * 4)


# ---- admin: API key management -----------------------------------------
def require_admin(request: Request, x_admin_token: str = Header(default="")):
    if not settings.admin_token:
        raise HTTPException(503, "Admin API is disabled (ADMIN_TOKEN is not set)")
    ratelimit.check(request.client.host if request.client else "?", "admin", 60, 60)
    if not secrets.compare_digest(x_admin_token.encode(), settings.admin_token.encode()):
        raise HTTPException(401, "Invalid admin token")


@app.get("/v1/admin/keys", dependencies=[Depends(require_admin)])
def admin_keys():
    return ai.pool.snapshot()


@app.post("/v1/admin/keys/test", dependencies=[Depends(require_admin)])
def admin_test_all():
    return {"results": ai.test_all(), "status": ai.pool.snapshot()}


@app.post("/v1/admin/keys/{key_id}/test", dependencies=[Depends(require_admin)])
def admin_test_one(key_id: str):
    try:
        res = ai.test_key(key_id)
    except KeyError:
        raise HTTPException(404, "Unknown key id")
    return {"result": res, "status": ai.pool.snapshot()}


@app.get("/admin", include_in_schema=False)
def admin_redirect():
    return RedirectResponse("/admin/")


app.mount("/admin", StaticFiles(directory=Path(__file__).parent / "static", html=True), name="admin")
