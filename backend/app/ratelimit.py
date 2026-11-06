import threading
import time
from collections import defaultdict, deque

from fastapi import HTTPException

_lock = threading.Lock()
_hits: dict = defaultdict(deque)


def check(subject: str, bucket: str, limit: int, window: int):
    now = time.monotonic()
    with _lock:
        q = _hits[(subject, bucket)]
        while q and now - q[0] > window:
            q.popleft()
        if len(q) >= limit:
            raise HTTPException(429, "Too many requests. Please slow down.",
                                headers={"Retry-After": str(window)})
        q.append(now)
