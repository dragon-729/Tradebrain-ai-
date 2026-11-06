import json
import re


def chunk_page(text: str, size: int = 1200, overlap: int = 150) -> list[str]:
    paras = [p.strip() for p in re.split(r"\n\s*\n", text or "") if p.strip()]
    chunks, cur = [], ""
    for p in paras:
        if len(cur) + len(p) + 2 <= size:
            cur = f"{cur}\n\n{p}" if cur else p
        else:
            if cur:
                chunks.append(cur)
            while len(p) > size:
                chunks.append(p[:size])
                p = p[size - overlap:]
            cur = p
    if cur:
        chunks.append(cur)
    return chunks


_CITE = re.compile(r"\[S(\d+)\]")


def validate_citations(answer: str, n_sources: int) -> tuple[str, list[int]]:
    """Drop citations that do not refer to a provided source; return (clean answer, used source numbers)."""
    used: list[int] = []

    def repl(m):
        i = int(m.group(1))
        if 1 <= i <= n_sources:
            if i not in used:
                used.append(i)
            return m.group(0)
        return ""

    return _CITE.sub(repl, answer).strip(), used


def parse_json_loose(raw: str) -> dict:
    s = (raw or "").strip()
    if s.startswith("```"):
        s = re.sub(r"^```(?:json)?\s*|\s*```$", "", s)
    return json.loads(s)


def to_vector(values) -> str:
    return "[" + ",".join(f"{v:.6f}" for v in values) + "]"
