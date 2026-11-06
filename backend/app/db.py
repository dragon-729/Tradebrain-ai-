from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from .config import settings

pool: ConnectionPool | None = None


def init():
    global pool
    pool = ConnectionPool(settings.database_url, min_size=1, max_size=10, open=False,
                          kwargs={"row_factory": dict_row, "prepare_threshold": None})
    pool.open(wait=True, timeout=20)


def close():
    if pool:
        pool.close()


def fetch_all(sql, params=()):
    with pool.connection() as c:
        return c.execute(sql, params).fetchall()


def fetch_one(sql, params=()):
    with pool.connection() as c:
        return c.execute(sql, params).fetchone()


def execute(sql, params=()):
    with pool.connection() as c:
        c.execute(sql, params)
