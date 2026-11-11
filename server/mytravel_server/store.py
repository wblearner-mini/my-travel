"""SQLite storage: accounts, sessions, login attempts, AI turns, usage records and the weather cache.
No trip data, message content or images are stored. The weather cache keeps, per account, the ~1 km
cells asked for in the last hour; older rows are deleted."""

import sqlite3
import threading

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY, username TEXT UNIQUE NOT NULL, pw_hash TEXT NOT NULL,
    disabled INTEGER NOT NULL DEFAULT 0, fail_reset REAL NOT NULL DEFAULT 0, created REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id), token_hash TEXT UNIQUE NOT NULL,
    created REAL NOT NULL, last_used REAL NOT NULL, expires REAL NOT NULL, revoked REAL, note TEXT
);
CREATE TABLE IF NOT EXISTS login_attempts (ts REAL NOT NULL, username TEXT NOT NULL, ip TEXT NOT NULL, ok INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS login_attempts_ts ON login_attempts(ts);
CREATE TABLE IF NOT EXISTS turns (
    turn_id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, created REAL NOT NULL,
    model_calls INTEGER NOT NULL DEFAULT 0, pending_search TEXT NOT NULL DEFAULT '[]'
);
CREATE TABLE IF NOT EXISTS usage (
    id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, ts REAL NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL,
    ms INTEGER NOT NULL DEFAULT 0, count INTEGER NOT NULL DEFAULT 1,
    in_tok INTEGER NOT NULL DEFAULT 0, out_tok INTEGER NOT NULL DEFAULT 0, cost REAL NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS usage_user_ts ON usage(user_id, ts);
-- Per account: a shared cache would reveal where and when others asked.
CREATE TABLE IF NOT EXISTS weather_cache (
    user_id INTEGER NOT NULL, kind TEXT NOT NULL, cell TEXT NOT NULL, json TEXT NOT NULL, fetched REAL NOT NULL,
    PRIMARY KEY (user_id, kind, cell)
);
"""


class Store:
    def __init__(self, path: str):
        self.conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA foreign_keys = ON")
        self.conn.executescript(SCHEMA)
        self.lock = threading.Lock()

    def one(self, sql, args=()):
        with self.lock:
            return self.conn.execute(sql, args).fetchone()

    def all(self, sql, args=()):
        with self.lock:
            return self.conn.execute(sql, args).fetchall()

    def run(self, sql, args=()):
        with self.lock:
            return self.conn.execute(sql, args)
