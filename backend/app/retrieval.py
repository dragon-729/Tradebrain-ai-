import logging

from . import ai, db
from .textutil import to_vector

log = logging.getLogger("tradebrain.retrieval")


def _scope_filter(scope: str, user_id: str):
    if scope == "personal":
        return "d.user_id = %s", [user_id]
    if scope == "public":
        return "d.visibility = 'public'", []
    if scope == "both":
        return "(d.user_id = %s or d.visibility = 'public')", [user_id]
    raise ValueError(scope)


def search(user_id: str, query: str, scope: str, k: int = 6) -> list[dict]:
    """Hybrid (semantic + keyword) retrieval with reciprocal-rank fusion. Authorization is enforced in SQL."""
    if scope == "general" or not query.strip():
        return []
    flt, fp = _scope_filter(scope, user_id)
    base = ("select c.id, c.document_id, c.page, c.content, d.title from document_chunks c "
            "join documents d on d.id = c.document_id where d.status = 'ready' and " + flt)
    sem = []
    try:
        vec = to_vector(ai.embed([query], "RETRIEVAL_QUERY")[0])
        sem = db.fetch_all(base + " order by c.embedding <=> %s::vector limit 20", fp + [vec])
    except ai.AIError as e:  # degrade to keyword-only rather than failing the whole question
        log.warning("semantic search unavailable: %s", e)
    kw = db.fetch_all(base + " and c.tsv @@ websearch_to_tsquery('english', %s) "
                      "order by ts_rank(c.tsv, websearch_to_tsquery('english', %s)) desc limit 20",
                      fp + [query, query])
    score, rows = {}, {}
    for lst in (sem, kw):
        for rank, r in enumerate(lst):
            score[r["id"]] = score.get(r["id"], 0.0) + 1.0 / (60 + rank)
            rows[r["id"]] = r
    top = sorted(score, key=score.get, reverse=True)[:k]
    return [rows[i] for i in top]
