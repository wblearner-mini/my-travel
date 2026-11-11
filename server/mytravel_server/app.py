"""The app's server: login with a managed account, then a few fixed business endpoints that call
Bailian, DeepSeek search and QWeather with credentials that never leave the server.

Every protected endpoint checks the session token against the database on each request, then
checks the account's budget before calling upstream. There is no generic proxy: model, tools,
output limit and upstream URLs are fixed here."""

import base64
import hashlib
import json
import logging
import secrets
import threading
import time
from datetime import datetime
from zoneinfo import ZoneInfo

import httpx
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError
from cryptography.hazmat.primitives.serialization import load_pem_private_key
from fastapi import FastAPI, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse, Response

from . import spec
from .config import Config
from .store import Store

log = logging.getLogger("mytravel")
TZ = ZoneInfo("Asia/Shanghai")
hasher = PasswordHasher()
# Verified against when the username is unknown, so both cases take the same time.
_DUMMY_HASH = hasher.hash("not-a-real-password")

WEATHER_PATHS = {
    # QWeather accepts at most 2 decimals (~1 km). Same paths as the app used (Weather.kt).
    "now": "/weather/v1/current/{lat}/{lng}?lang=zh",
    "hourly": "/weather/v1/hourly/{lat}/{lng}?hours=24&localTime=true&lang=zh",
    "daily": "/weather/v1/daily/{lat}/{lng}?days=10&localTime=true&lang=zh",
    "minutely": "/v7/minutely/5m?location={lng},{lat}&lang=zh",
    "alerts": "/weatheralert/v1/current/{lat}/{lng}?localTime=true&lang=zh",
}


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        self.status, self.code, self.message = status, code, message


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def create_app(cfg: Config, store: Store, transport: httpx.BaseTransport | None = None, clock=time.time) -> FastAPI:
    lim, price = cfg.limits, cfg.prices
    http = httpx.Client(transport=transport, timeout=httpx.Timeout(90.0, connect=15.0))
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)

    # Upstream calls and password hashing block, so handlers run them in the thread pool; the event
    # loop stays free for other requests. Per account, paid calls run one at a time, so two requests
    # can't both pass a budget check before either is recorded.
    locks: dict = {}
    locks_guard = threading.Lock()

    def run_locked(uid, group, fn, *args):
        with locks_guard:
            lock = locks.setdefault((uid, group), threading.Lock())
        with lock:
            return fn(*args)

    @app.exception_handler(ApiError)
    def _api_error(_, e: ApiError):
        return JSONResponse({"error": e.code, "message": e.message}, status_code=e.status)

    # ---------------------------------------------------------------- time windows
    def day_start(now: float) -> float:
        d = datetime.fromtimestamp(now, TZ)
        return d.replace(hour=0, minute=0, second=0, microsecond=0).timestamp()

    def month_start(now: float) -> float:
        d = datetime.fromtimestamp(now, TZ)
        return d.replace(day=1, hour=0, minute=0, second=0, microsecond=0).timestamp()

    # ---------------------------------------------------------------- sessions
    def current_user(request: Request):
        auth = request.headers.get("authorization", "")
        if not auth.startswith("Bearer "):
            raise ApiError(401, "unauthorized", "请先登录")
        now = clock()
        row = store.one(
            "SELECT s.id AS sid, s.expires, u.id AS uid, u.username FROM sessions s JOIN users u ON u.id = s.user_id "
            "WHERE s.token_hash = ? AND s.revoked IS NULL AND s.expires > ? AND u.disabled = 0",
            (token_hash(auth[7:].strip()), now),
        )
        if row is None:
            raise ApiError(401, "unauthorized", "登录已失效，请重新登录")
        store.run("UPDATE sessions SET last_used = ? WHERE id = ?", (now, row["sid"]))
        return row

    def client_ip(request: Request) -> str:
        # Behind a reverse proxy, run uvicorn with --proxy-headers so this is the real client.
        return request.client.host if request.client else "unknown"

    def login_blocked(username: str, ip: str, now: float) -> bool:
        last = store.all(
            "SELECT ts, ok FROM login_attempts WHERE username = ? AND ip = ? ORDER BY ts DESC LIMIT 5", (username, ip),
        )
        if len(last) == 5 and not any(r["ok"] for r in last) and last[0]["ts"] > now - 15 * 60:
            return True
        ip_fails = store.one("SELECT COUNT(*) AS n FROM login_attempts WHERE ip = ? AND ok = 0 AND ts > ?", (ip, now - 3600))["n"]
        # Successful logins count too, so a valid account can't loop logins to keep hashing busy.
        ip_all = store.one("SELECT COUNT(*) AS n FROM login_attempts WHERE ip = ? AND ts > ?", (ip, now - 3600))["n"]
        if ip_fails >= 20 or ip_all >= 60:
            return True
        # The per-account daily lock doesn't apply where this account logged in recently, so someone
        # guessing from elsewhere can't keep the owner out on their own networks.
        if store.one(
            "SELECT 1 FROM login_attempts WHERE username = ? AND ip = ? AND ok = 1 AND ts > ?", (username, ip, now - 30 * 86400),
        ):
            return False
        reset = store.one("SELECT fail_reset FROM users WHERE username = ?", (username,))
        since = max(now - 86400, reset["fail_reset"] if reset else 0)
        user_fails = store.one(
            "SELECT COUNT(*) AS n FROM login_attempts WHERE username = ? AND ok = 0 AND ts > ?", (username, since),
        )["n"]
        return user_fails >= 50

    @app.post("/v1/auth/login")
    async def login(request: Request):
        return await run_in_threadpool(login_sync, request, await json_body(request))

    def login_sync(request: Request, body: dict):
        username, password = str(body.get("username", ""))[:64], str(body.get("password", ""))[:256]
        ip, now = client_ip(request), clock()
        if login_blocked(username, ip, now):
            raise ApiError(429, "too_many_attempts", "尝试次数过多，请稍后再试")
        user = store.one("SELECT id, pw_hash, disabled FROM users WHERE username = ?", (username,))
        try:
            ok = hasher.verify(user["pw_hash"] if user else _DUMMY_HASH, password) and user is not None and not user["disabled"]
        except (VerificationError, InvalidHashError):
            ok = False
        store.run("INSERT INTO login_attempts VALUES (?, ?, ?, ?)", (now, username, ip, int(ok)))
        if not ok:
            raise ApiError(401, "bad_credentials", "用户名或密码错误")
        token = secrets.token_urlsafe(32)
        expires = now + lim.session_days * 86400
        note = str(body.get("device_note", ""))[:40] or None
        store.run(
            "INSERT INTO sessions (user_id, token_hash, created, last_used, expires, note) VALUES (?, ?, ?, ?, ?, ?)",
            (user["id"], token_hash(token), now, now, expires, note),
        )
        return {"token": token, "expires_at": int(expires)}

    @app.post("/v1/auth/logout")
    def logout(request: Request):
        u = current_user(request)
        store.run("UPDATE sessions SET revoked = ? WHERE id = ?", (clock(), u["sid"]))
        return {"ok": True}

    @app.get("/v1/auth/me")
    def me(request: Request):
        u = current_user(request)
        return {"username": u["username"], "expires_at": int(u["expires"]), "usage_today": usage_today(u["uid"])}

    # ---------------------------------------------------------------- usage and budget
    def record(uid, kind, status, ms=0, count=1, in_tok=0, out_tok=0, cost=0.0):
        store.run(
            "INSERT INTO usage (user_id, ts, kind, status, ms, count, in_tok, out_tok, cost) VALUES (?,?,?,?,?,?,?,?,?)",
            (uid, clock(), kind, status, ms, count, in_tok, out_tok, cost),
        )
        log.info("usage user=%s kind=%s status=%s ms=%s count=%s in=%s out=%s cost=%.4f", uid, kind, status, ms, count, in_tok, out_tok, cost)

    def used(uid, kind, since) -> int:
        return store.one(
            "SELECT COALESCE(SUM(count), 0) AS n FROM usage WHERE user_id = ? AND kind = ? AND status = 'ok' AND ts >= ?",
            (uid, kind, since),
        )["n"]

    def usage_today(uid) -> dict:
        since = day_start(clock())
        rows = store.all(
            "SELECT kind, SUM(count) AS n, SUM(in_tok) AS i, SUM(cost) AS c FROM usage WHERE user_id = ? AND status = 'ok' AND ts >= ? GROUP BY kind",
            (uid, since),
        )
        return {r["kind"]: {"count": r["n"], "input_tokens": r["i"], "cost_yuan": round(r["c"], 4)} for r in rows}

    def check_money(uid):
        """Monthly cost for this account and, unless it is an owner, for all non-owner accounts
        together; plus today's input tokens."""
        now = clock()
        m = month_start(now)
        mine = store.one("SELECT COALESCE(SUM(cost), 0) AS c FROM usage WHERE user_id = ? AND ts >= ?", (uid, m))["c"]
        if mine >= lim.cost_per_month_yuan:
            raise ApiError(429, "quota", "本月费用额度已用完")
        owners = list(cfg.owners)
        marks = ",".join("?" * len(owners))
        is_owner = bool(owners) and store.one(f"SELECT 1 FROM users WHERE id = ? AND username IN ({marks})", (uid, *owners)) is not None
        if not is_owner:
            others = f" AND user_id NOT IN (SELECT id FROM users WHERE username IN ({marks}))" if owners else ""
            total = store.one(f"SELECT COALESCE(SUM(cost), 0) AS c FROM usage WHERE ts >= ?{others}", (m, *owners))["c"]
            if total >= lim.global_cost_per_month_yuan:
                raise ApiError(429, "quota", "本月服务总额度已用完")
        tokens = store.one("SELECT COALESCE(SUM(in_tok), 0) AS n FROM usage WHERE user_id = ? AND ts >= ?", (uid, day_start(now)))["n"]
        if tokens >= lim.input_tokens_per_day:
            raise ApiError(429, "quota", "今日 AI 额度已用完")

    def check_count(uid, kind, limit, message):
        if used(uid, kind, day_start(clock())) >= limit:
            raise ApiError(429, "quota", message)

    def chat_cost(usage: dict) -> tuple[int, int, float]:
        i, o = int(usage.get("prompt_tokens") or 0), int(usage.get("completion_tokens") or 0)
        return i, o, i / 1e6 * price.chat_in_per_m + o / 1e6 * price.chat_out_per_m

    def bailian(body: dict) -> dict:
        if not (cfg.dashscope_key and cfg.dashscope_base and cfg.dashscope_model):
            raise ApiError(503, "not_configured", "服务端尚未配置模型")
        body = {**body, "model": cfg.dashscope_model, "enable_thinking": False}
        r = http.post(
            cfg.dashscope_base.rstrip("/") + "/chat/completions", json=body,
            headers={"Authorization": f"Bearer {cfg.dashscope_key}"},
        )
        if r.status_code != 200:
            raise ApiError(502, "upstream", f"AI 服务返回 HTTP {r.status_code}")
        return r.json()

    # ---------------------------------------------------------------- AI assistant
    def clean_messages(raw) -> list:
        """Keeps only the roles and fields a chat history needs; anything else is rejected."""
        if not isinstance(raw, list) or not 1 <= len(raw) <= 300:
            raise ApiError(400, "bad_request", "messages 不合法")
        out = []
        for m in raw:
            if not isinstance(m, dict):
                raise ApiError(400, "bad_request", "messages 中有不支持的内容")
            role, content = m.get("role"), m.get("content")
            if role == "user" and isinstance(content, str):
                out.append({"role": "user", "content": content})
            elif role == "assistant" and (content is None or isinstance(content, str)):
                msg = {"role": "assistant", "content": content or ""}
                calls = m.get("tool_calls")
                if calls:
                    if not isinstance(calls, list) or not all(
                        isinstance(c, dict) and isinstance(c.get("id"), str) and isinstance(c.get("function"), dict)
                        and isinstance(c["function"].get("name"), str) for c in calls
                    ):
                        raise ApiError(400, "bad_request", "tool_calls 不合法")
                    msg["tool_calls"] = [
                        {"id": str(c["id"]), "type": "function",
                         "function": {"name": str(c["function"]["name"]), "arguments": str(c["function"].get("arguments", ""))}}
                        for c in calls
                    ]
                out.append(msg)
            elif role == "tool" and isinstance(content, str) and isinstance(m.get("tool_call_id"), str):
                out.append({"role": "tool", "tool_call_id": m["tool_call_id"], "content": content})
            else:
                raise ApiError(400, "bad_request", "messages 中有不支持的内容")
        return out

    def turn_for(uid, turn_id: str, create: bool):
        if not isinstance(turn_id, str) or not 8 <= len(turn_id) <= 64:
            raise ApiError(400, "bad_request", "turn_id 不合法")
        t = store.one("SELECT * FROM turns WHERE turn_id = ?", (turn_id,))
        if t is not None and t["user_id"] != uid:
            raise ApiError(403, "forbidden", "无权访问")
        if t is None:
            if not create:
                raise ApiError(400, "bad_request", "没有这一轮对话")
            check_count(uid, "turn", lim.turns_per_day, "今日 AI 对话次数已用完")
            store.run("INSERT INTO turns (turn_id, user_id, created) VALUES (?, ?, ?)", (turn_id, uid, clock()))
            record(uid, "turn", "ok")
            t = store.one("SELECT * FROM turns WHERE turn_id = ?", (turn_id,))
        return t

    @app.post("/v1/ai/chat")
    async def ai_chat(request: Request):
        u = current_user(request)
        return await run_in_threadpool(run_locked, u["uid"], "paid", ai_chat_sync, u, await json_body(request, 400_000))

    def ai_chat_sync(u, body: dict):
        uid = u["uid"]
        messages = clean_messages(body.get("messages"))
        context = str(body.get("context", ""))[:4000]
        with_search = bool(body.get("web_search")) and bool(cfg.deepseek_key)
        check_money(uid)
        check_count(uid, "amap_tool", lim.amap_tools_per_day, "今日 AI 地图查询额度已用完")
        t = turn_for(uid, body.get("turn_id"), create=True)
        if t["model_calls"] >= lim.model_calls_per_turn:
            raise ApiError(429, "turn_limit", "这个问题需要的步骤太多，可以拆小一点再问")
        store.run("UPDATE turns SET model_calls = model_calls + 1 WHERE turn_id = ?", (t["turn_id"],))
        prompt = spec.SYSTEM_PROMPT + spec.DISTANCE_PROMPT + (spec.WEB_SEARCH_PROMPT if with_search else "")
        tools = spec.BASE_TOOLS + ([spec.WEB_SEARCH_TOOL] if with_search else [])
        started = time.monotonic()
        try:
            resp = bailian({
                "messages": [{"role": "system", "content": prompt + "\n\n" + context}, *messages],
                "tools": tools, "max_tokens": lim.chat_max_tokens,
            })
            msg = resp["choices"][0]["message"]
            if not isinstance(msg, dict):
                raise ValueError("no message")
        except (httpx.HTTPError, KeyError, IndexError, TypeError, ValueError):
            record(uid, "chat", "error", int((time.monotonic() - started) * 1000))
            raise ApiError(502, "upstream", "AI 服务暂时不可用")
        except ApiError:
            record(uid, "chat", "error", int((time.monotonic() - started) * 1000))
            raise
        i, o, cost = chat_cost(resp.get("usage") or {})
        record(uid, "chat", "ok", int((time.monotonic() - started) * 1000), in_tok=i, out_tok=o, cost=cost)
        calls = (msg.get("tool_calls") or [])[: lim.tool_calls_per_response]
        def query_of(c):
            try:
                return str(json.loads(c["function"].get("arguments") or "{}").get("query", "")).strip()[:128]
            except (ValueError, TypeError, AttributeError):
                return ""

        # The query is kept with the call id, so the search runs exactly what the model asked for.
        searches = [[c["id"], query_of(c)] for c in calls if c.get("function", {}).get("name") == "web_search" and with_search]
        if searches:
            pending = json.loads(t["pending_search"]) + searches
            store.run("UPDATE turns SET pending_search = ? WHERE turn_id = ?", (json.dumps(pending), t["turn_id"]))
        amap = 0
        for c in calls:
            name = c.get("function", {}).get("name")
            if name == "locate_places":
                try:
                    a = json.loads(c["function"].get("arguments") or "{}")
                    amap += min(8, len(a.get("poi_ids") or []) + len(a.get("names") or [])) or 1
                except (ValueError, TypeError, AttributeError):
                    amap += 1
            elif name in spec.AMAP_TOOLS:
                amap += 1
        if amap:
            record(uid, "amap_tool", "ok", count=amap)
        out = {"role": "assistant", "content": msg.get("content") or ""}
        if calls:
            out["tool_calls"] = calls
        return {"message": out}

    @app.post("/v1/ai/web_search")
    async def ai_web_search(request: Request):
        """Runs only a search the model asked for in this turn (by its tool_call id), once, with the
        model's own query; the client's query is ignored."""
        u = current_user(request)
        return await run_in_threadpool(run_locked, u["uid"], "paid", ai_web_search_sync, u, await json_body(request))

    def ai_web_search_sync(u, body: dict):
        uid = u["uid"]
        t = turn_for(uid, body.get("turn_id"), create=False)
        pending = json.loads(t["pending_search"])
        match = next((p for p in pending if p[0] == body.get("tool_call_id")), None)
        if match is None:
            raise ApiError(403, "forbidden", "这次搜索不是 AI 在本轮提出的")
        pending.remove(match)
        store.run("UPDATE turns SET pending_search = ? WHERE turn_id = ?", (json.dumps(pending), t["turn_id"]))
        query = match[1]
        if not query:
            raise ApiError(400, "bad_request", "query 不能为空")
        check_money(uid)
        check_count(uid, "web_search", lim.web_search_per_day, "今日联网搜索次数已用完")
        started = time.monotonic()
        try:
            r = http.post(
                "https://api.deepseek.com/v1/tools/search", json={"query": query, "count": 6},
                headers={"Authorization": f"Bearer {cfg.deepseek_key}"}, timeout=httpx.Timeout(30.0, connect=15.0),
            )
            if r.status_code != 200:
                raise ApiError(502, "upstream", f"联网搜索返回 HTTP {r.status_code}")
            results = r.json()["results"]
        except (httpx.HTTPError, KeyError, ValueError):
            record(uid, "web_search", "error", int((time.monotonic() - started) * 1000))
            raise ApiError(502, "upstream", "联网搜索暂时不可用")
        except ApiError:
            record(uid, "web_search", "error", int((time.monotonic() - started) * 1000))
            raise
        # Empty results are not billed (【官方文档】2026-11-10).
        record(uid, "web_search", "ok", int((time.monotonic() - started) * 1000), cost=price.web_search_each if results else 0.0)

        def opt(v):
            return v if v not in (None, "", "null") else None

        return {
            "source": "DeepSeek 联网搜索，网页内容可能过时或不准确",
            "results": [
                {
                    "title": x.get("title", ""), "url": x.get("url", ""), "site": opt(x.get("site_name")),
                    "date": (opt(x.get("date")) or "")[:10] or None,
                    "summary": x.get("summary") or x.get("snippet") or "",
                }
                for x in results
            ],
        }

    # ---------------------------------------------------------------- weather
    jwt_cache = {"token": "", "exp": 0.0}

    def qweather_jwt() -> str:
        now = clock()
        if jwt_cache["exp"] - now > 60:
            return jwt_cache["token"]
        key = load_pem_private_key(cfg.qweather_private_key_pem.encode(), password=None)

        def b64(b: bytes) -> str:
            return base64.urlsafe_b64encode(b).rstrip(b"=").decode()

        iat = int(now) - 30
        header = b64(json.dumps({"alg": "EdDSA", "kid": cfg.qweather_credential_id}, separators=(",", ":")).encode())
        payload = b64(json.dumps(
            {"iss": cfg.qweather_developer_id, "sub": cfg.qweather_project_id, "iat": iat, "exp": iat + 900},
            separators=(",", ":"),
        ).encode())
        sig = b64(key.sign(f"{header}.{payload}".encode()))
        jwt_cache.update(token=f"{header}.{payload}.{sig}", exp=iat + 900)
        return jwt_cache["token"]

    @app.post("/v1/weather")
    async def weather(request: Request):
        """Coordinates come in the body, not the URL, so access logs never hold them."""
        u = current_user(request)
        return await run_in_threadpool(run_locked, u["uid"], "weather", weather_sync, u, await json_body(request))

    def weather_sync(u, body: dict):
        uid = u["uid"]
        kind = body.get("kind")
        try:
            lat, lng = float(body.get("lat")), float(body.get("lng"))
        except (TypeError, ValueError):
            raise ApiError(400, "bad_request", "坐标不合法")
        if kind not in WEATHER_PATHS or not (-90 <= lat <= 90 and -180 <= lng <= 180):
            raise ApiError(400, "bad_request", "参数不合法")
        la, ln = f"{lat:.2f}", f"{lng:.2f}"
        cell, now = f"{la},{ln}", clock()
        # Per account: a shared cache would tell one user where (and when) another asked for weather.
        cached = store.one("SELECT json, fetched FROM weather_cache WHERE user_id = ? AND kind = ? AND cell = ?", (uid, kind, cell))
        max_age = lim.weather_fresh_min_s if body.get("fresh") else lim.weather_cache_s
        if cached and now - cached["fetched"] < max_age:
            return Response(cached["json"], media_type="application/json", headers={"X-Fetched-At": str(int(cached["fetched"] * 1000))})
        check_count(uid, "weather", lim.weather_upstream_per_day, "今日天气查询额度已用完")
        if not (cfg.qweather_host and cfg.qweather_private_key_pem):
            raise ApiError(503, "not_configured", "服务端尚未配置天气")
        started = time.monotonic()
        try:
            r = http.get(
                f"https://{cfg.qweather_host}" + WEATHER_PATHS[kind].format(lat=la, lng=ln),
                headers={"Authorization": f"Bearer {qweather_jwt()}"}, timeout=httpx.Timeout(10.0),
            )
            if r.status_code != 200:
                raise ApiError(502, "upstream", f"和风天气返回 HTTP {r.status_code}")
            text = r.text
            json.loads(text)
        except (httpx.HTTPError, ValueError):
            record(uid, "weather", "error", int((time.monotonic() - started) * 1000))
            raise ApiError(502, "upstream", "天气服务暂时不可用")
        except ApiError:
            record(uid, "weather", "error", int((time.monotonic() - started) * 1000))
            raise
        record(uid, "weather", "ok", int((time.monotonic() - started) * 1000))
        store.run("INSERT OR REPLACE INTO weather_cache VALUES (?, ?, ?, ?, ?)", (uid, kind, cell, text, now))
        # Old cells are of no use once stale, and they record where someone was.
        store.run("DELETE FROM weather_cache WHERE fetched < ?", (now - 3600,))
        return Response(text, media_type="application/json", headers={"X-Fetched-At": str(int(now * 1000))})

    # ---------------------------------------------------------------- hotel order
    @app.post("/v1/hotel/parse")
    async def hotel_parse(request: Request):
        """One JPEG in the body. The image is forwarded from memory only: never stored or logged."""
        u = current_user(request)
        data = await read_limited(request, lim.max_image_bytes, "图片需为 4 MB 以内的 JPEG")
        return await run_in_threadpool(run_locked, u["uid"], "paid", hotel_parse_sync, u, data)

    def hotel_parse_sync(u, data: bytes):
        uid = u["uid"]
        if not data or not data.startswith(b"\xff\xd8"):
            raise ApiError(400, "bad_request", "图片需为 4 MB 以内的 JPEG")
        check_money(uid)
        check_count(uid, "hotel_parse", lim.hotel_parse_per_day, "今日订单识别次数已用完")
        started = time.monotonic()
        try:
            resp = bailian({
                "response_format": {"type": "json_schema", "json_schema": {"name": "hotel_order", "strict": True, "schema": spec.HOTEL_SCHEMA}},
                "max_tokens": lim.hotel_max_tokens,
                "messages": [
                    {"role": "system", "content": spec.HOTEL_PROMPT},
                    {"role": "user", "content": [
                        {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(data).decode()}},
                        {"type": "text", "text": "提取这张酒店订单截图中的信息。"},
                    ]},
                ],
            })
            content = resp["choices"][0]["message"]["content"]
            if not isinstance(content, str):
                raise ValueError("no content")
        except (httpx.HTTPError, KeyError, IndexError, TypeError, ValueError):
            record(uid, "hotel_parse", "error", int((time.monotonic() - started) * 1000))
            raise ApiError(502, "upstream", "订单识别暂时不可用")
        except ApiError:
            record(uid, "hotel_parse", "error", int((time.monotonic() - started) * 1000))
            raise
        i, o, cost = chat_cost(resp.get("usage") or {})
        record(uid, "hotel_parse", "ok", int((time.monotonic() - started) * 1000), in_tok=i, out_tok=o, cost=cost)
        return {"content": content}

    # ---------------------------------------------------------------- helpers
    async def read_limited(request: Request, max_bytes: int, message: str = "请求内容过大") -> bytes:
        """Stops reading as soon as the body passes [max_bytes], so a huge body can't fill memory."""
        declared = request.headers.get("content-length")
        if declared is not None and (not declared.isdigit() or int(declared) > max_bytes):
            raise ApiError(413, "too_large", message)
        buf = bytearray()
        async for chunk in request.stream():
            buf += chunk
            if len(buf) > max_bytes:
                raise ApiError(413, "too_large", message)
        return bytes(buf)

    async def json_body(request: Request, max_bytes: int = 16_000) -> dict:
        raw = await read_limited(request, max_bytes)
        try:
            body = json.loads(raw or b"{}")
        except ValueError:
            raise ApiError(400, "bad_request", "请求格式错误")
        if not isinstance(body, dict):
            raise ApiError(400, "bad_request", "请求格式错误")
        return body

    return app
