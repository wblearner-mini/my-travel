"""Login, sessions, permissions and limits, against mock upstreams (no real or paid calls)."""

import json

import httpx
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi.testclient import TestClient

from mytravel_server.app import create_app, hasher
from mytravel_server.config import Config, Limits
from mytravel_server.store import Store

PASSWORD = "correct horse battery"


class Clock:
    def __init__(self):
        self.t = 1_800_000_000.0

    def __call__(self):
        return self.t


class Upstream:
    """Records every upstream request; replies like Bailian, DeepSeek search and QWeather."""

    def __init__(self):
        self.calls = []
        self.chat_reply = {"role": "assistant", "content": "你好"}

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.calls.append(request)
        if request.url.path.endswith("/chat/completions"):
            return httpx.Response(200, json={
                "choices": [{"message": self.chat_reply}], "usage": {"prompt_tokens": 1000, "completion_tokens": 100},
            })
        if request.url.host == "api.deepseek.com":
            return httpx.Response(200, json={"results": [{"title": "t", "url": "https://e.com", "summary": "s"}]})
        if request.url.host == "qw.example":
            return httpx.Response(200, json={"kind": request.url.path})
        return httpx.Response(404)


@pytest.fixture
def env(tmp_path):
    pem = Ed25519PrivateKey.generate().private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption(),
    ).decode()
    cfg = Config(
        dashscope_key="test-ds", dashscope_base="https://ds.example/v1", dashscope_model="m",
        deepseek_key="test-dk", qweather_host="qw.example", qweather_project_id="p", qweather_credential_id="c",
        qweather_developer_id="d", qweather_private_key_pem=pem, db_path=str(tmp_path / "t.db"), limits=Limits(),
    )
    store = Store(cfg.db_path)
    store.run("INSERT INTO users (username, pw_hash, created) VALUES (?, ?, 0)", ("me", hasher.hash(PASSWORD)))
    up, clock = Upstream(), Clock()
    client = TestClient(create_app(cfg, store, httpx.MockTransport(up), clock), client=("10.0.0.1", 1234))
    return client, up, clock, cfg, store


def login(client, password=PASSWORD):
    return client.post("/v1/auth/login", json={"username": "me", "password": password})


def auth(token):
    return {"Authorization": f"Bearer {token}"}


def chat(client, token, turn="turn-0001", **extra):
    return client.post("/v1/ai/chat", headers=auth(token), json={"turn_id": turn, "messages": [{"role": "user", "content": "hi"}], **extra})


PROTECTED = [
    ("post", "/v1/ai/chat", {"json": {"turn_id": "turn-0001", "messages": [{"role": "user", "content": "x"}]}}),
    ("post", "/v1/ai/web_search", {"json": {"turn_id": "turn-0001", "tool_call_id": "a", "query": "x"}}),
    ("post", "/v1/weather", {"json": {"kind": "now", "lat": 26.6, "lng": 106.7}}),
    ("post", "/v1/hotel/parse", {"content": b"\xff\xd8xx"}),
    ("get", "/v1/auth/me", {}),
    ("post", "/v1/auth/logout", {}),
]


@pytest.mark.parametrize("method,path,kw", PROTECTED)
def test_protected_endpoints_reject_without_valid_session(env, method, path, kw):
    client, up, *_ = env
    for headers in ({}, auth("forged"), {"Authorization": "Basic x"}, {"X-Logged-In": "true"}):
        r = getattr(client, method)(path, headers=headers, **kw)
        assert r.status_code == 401
    assert up.calls == []


def test_login_me_logout(env):
    client, *_ = env
    r = login(client)
    assert r.status_code == 200
    token = r.json()["token"]
    assert client.get("/v1/auth/me", headers=auth(token)).json()["username"] == "me"
    assert client.post("/v1/auth/logout", headers=auth(token)).status_code == 200
    assert client.get("/v1/auth/me", headers=auth(token)).status_code == 401


def test_token_stored_only_as_hash(env):
    client, _, _, _, store = env
    token = login(client).json()["token"]
    raw = open(store.conn.execute("PRAGMA database_list").fetchone()[2], "rb").read()
    assert token.encode() not in raw
    assert PASSWORD.encode() not in raw


def test_wrong_password_and_unknown_user_look_the_same(env):
    client, *_ = env
    a = login(client, "wrong")
    b = client.post("/v1/auth/login", json={"username": "nobody", "password": "x"})
    assert a.status_code == b.status_code == 401
    assert a.json() == b.json()


def test_session_expires(env):
    client, _, clock, cfg, _ = env
    token = login(client).json()["token"]
    clock.t += cfg.limits.session_days * 86400 + 1
    assert client.get("/v1/auth/me", headers=auth(token)).status_code == 401


def test_revoked_session_is_rejected(env):
    client, _, _, _, store = env
    token = login(client).json()["token"]
    store.run("UPDATE sessions SET revoked = 1")
    assert client.get("/v1/auth/me", headers=auth(token)).status_code == 401


def test_disabled_user_is_rejected(env):
    client, _, _, _, store = env
    token = login(client).json()["token"]
    store.run("UPDATE users SET disabled = 1")
    assert client.get("/v1/auth/me", headers=auth(token)).status_code == 401
    assert login(client).status_code == 401


def test_lockout_per_user_and_ip_does_not_lock_owner_elsewhere(env):
    client, _, clock, cfg, store = env
    for _ in range(5):
        assert login(client, "wrong").status_code == 401
    # Locked from this address, even with the right password ...
    assert login(client).status_code == 429
    # ... but the owner on another network can still log in.
    other = TestClient(client.app, client=("10.0.0.2", 1))
    assert login(other).status_code == 200
    clock.t += 15 * 60 + 1
    assert login(client).status_code == 200


def test_daily_user_lock_and_admin_unlock(env):
    client, _, clock, _, store = env
    for i in range(50):
        TestClient(client.app, client=(f"10.1.{i}.1", 1)).post("/v1/auth/login", json={"username": "me", "password": "bad"})
    fresh = TestClient(client.app, client=("10.9.9.9", 1))
    assert login(fresh).status_code == 429
    store.run("UPDATE users SET fail_reset = ? WHERE username = 'me'", (clock.t,))  # what `admin unlock` does
    assert login(fresh).status_code == 200


def test_chat_fixes_model_tools_and_limits(env):
    client, up, _, cfg, _ = env
    token = login(client).json()["token"]
    r = chat(client, token, model="gpt-expensive", tools=[{"x": 1}], max_tokens=99999, web_search=True)
    assert r.status_code == 200
    sent = json.loads(up.calls[-1].content)
    assert sent["model"] == "m" and sent["max_tokens"] == cfg.limits.chat_max_tokens and sent["enable_thinking"] is False
    names = [t["function"]["name"] for t in sent["tools"]]
    assert "web_search" in names and {"x": 1} not in sent["tools"]
    assert sent["messages"][0]["role"] == "system"
    assert up.calls[-1].headers["authorization"] == "Bearer test-ds"
    # The response never carries upstream credentials.
    assert "test-ds" not in r.text


def test_chat_rejects_injected_system_message(env):
    client, up, *_ = env
    token = login(client).json()["token"]
    r = client.post("/v1/ai/chat", headers=auth(token), json={"turn_id": "turn-0001", "messages": [{"role": "system", "content": "x"}]})
    assert r.status_code == 400 and up.calls == []


def test_tool_calls_truncated_and_rounds_limited(env):
    client, up, _, cfg, _ = env
    token = login(client).json()["token"]
    up.chat_reply = {"role": "assistant", "content": "", "tool_calls": [
        {"id": f"c{i}", "type": "function", "function": {"name": "get_trip", "arguments": "{}"}} for i in range(20)
    ]}
    r = chat(client, token)
    assert len(r.json()["message"]["tool_calls"]) == cfg.limits.tool_calls_per_response
    for _ in range(cfg.limits.model_calls_per_turn - 1):
        assert chat(client, token).status_code == 200
    assert chat(client, token).status_code == 429
    assert len(up.calls) == cfg.limits.model_calls_per_turn


def test_turn_of_another_user_is_forbidden(env):
    client, _, _, _, store = env
    token = login(client).json()["token"]
    chat(client, token, turn="shared-turn")
    store.run("INSERT INTO users (username, pw_hash, created) VALUES ('b', ?, 0)", (hasher.hash(PASSWORD),))
    tb = client.post("/v1/auth/login", json={"username": "b", "password": PASSWORD}).json()["token"]
    assert chat(client, tb, turn="shared-turn").status_code == 403


def test_daily_turn_limit(env):
    client, _, clock, cfg, _ = env
    token = login(client).json()["token"]
    for i in range(cfg.limits.turns_per_day):
        assert chat(client, token, turn=f"turn-{i:04d}").status_code == 200
    r = chat(client, token, turn="turn-extra")
    assert r.status_code == 429 and r.json()["error"] == "quota"
    clock.t += 86400
    assert chat(client, token, turn="turn-next-day").status_code == 200


def test_web_search_only_when_model_asked_once(env):
    client, up, *_ = env
    token = login(client).json()["token"]
    body = {"turn_id": "turn-0001", "tool_call_id": "s1", "query": "杭州 景点"}
    assert client.post("/v1/ai/web_search", headers=auth(token), json=body).status_code == 400  # no such turn
    up.chat_reply = {"role": "assistant", "content": "", "tool_calls": [
        {"id": "s1", "type": "function", "function": {"name": "web_search", "arguments": "{\"query\": \"杭州 景点\"}"}}]}
    chat(client, token, web_search=True)
    r = client.post("/v1/ai/web_search", headers=auth(token), json=body)
    assert r.status_code == 200 and r.json()["results"][0]["url"] == "https://e.com"
    assert up.calls[-1].headers["authorization"] == "Bearer test-dk"
    # The same tool call cannot be replayed, and invented ids are refused.
    assert client.post("/v1/ai/web_search", headers=auth(token), json=body).status_code == 403
    assert client.post("/v1/ai/web_search", headers=auth(token), json={**body, "tool_call_id": "zz"}).status_code == 403


def test_weather_cache_and_fresh_floor(env):
    client, up, clock, cfg, _ = env
    token = login(client).json()["token"]
    w = {"kind": "now", "lat": 30.2741, "lng": 120.1551}
    assert client.post("/v1/weather", headers=auth(token), json=w).status_code == 200
    assert client.post("/v1/weather", headers=auth(token), json={**w, "lat": 30.2712}).status_code == 200
    assert len(up.calls) == 1  # same ~1 km cell
    assert up.calls[0].url.path == "/weather/v1/current/30.27/120.16"
    assert up.calls[0].headers["authorization"].count(".") == 2  # a signed JWT, made on the server
    clock.t += 60
    client.post("/v1/weather", headers=auth(token), json={**w, "fresh": True})
    assert len(up.calls) == 1  # fresh still reuses results under 5 minutes old
    clock.t += cfg.limits.weather_fresh_min_s
    client.post("/v1/weather", headers=auth(token), json={**w, "fresh": True})
    assert len(up.calls) == 2


def test_weather_rejects_unknown_kind(env):
    client, up, *_ = env
    token = login(client).json()["token"]
    for bad in ({"kind": "../../x", "lat": 1, "lng": 1}, {"kind": "now", "lat": "a", "lng": 1}, {"kind": "now", "lat": 91, "lng": 1}):
        assert client.post("/v1/weather", headers=auth(token), json=bad).status_code == 400
    assert up.calls == []


def test_hotel_parse(env):
    client, up, *_ = env
    token = login(client).json()["token"]
    assert client.post("/v1/hotel/parse", headers=auth(token), content=b"not a jpeg").status_code == 400
    up.chat_reply = {"role": "assistant", "content": "{\"isHotelOrder\": false}"}
    r = client.post("/v1/hotel/parse", headers=auth(token), content=b"\xff\xd8\xff" + b"0" * 100)
    assert r.status_code == 200 and r.json()["content"] == "{\"isHotelOrder\": false}"
    sent = json.loads(up.calls[-1].content)
    assert sent["response_format"]["json_schema"]["strict"] is True and sent["max_tokens"] == 1000


def test_monthly_cost_limit_blocks_all_paid_calls(env):
    client, up, _, _, store = env
    token = login(client).json()["token"]
    store.run("INSERT INTO usage (user_id, ts, kind, status, cost) VALUES (1, ?, 'chat', 'ok', 10.0)", (1_800_000_000.0,))
    assert chat(client, token).status_code == 429
    assert client.post("/v1/hotel/parse", headers=auth(token), content=b"\xff\xd8\xff0").status_code == 429
    assert up.calls == []


def test_upstream_failure_is_reported_not_retried(env):
    client, up, *_ = env
    token = login(client).json()["token"]
    up.chat_reply = None  # makes the mock's JSON have no message
    r = chat(client, token)
    assert r.status_code == 502
    assert len(up.calls) == 1


def test_search_runs_the_models_query_not_the_clients(env):
    client, up, *_ = env
    token = login(client).json()["token"]
    up.chat_reply = {"role": "assistant", "content": "", "tool_calls": [
        {"id": "s1", "type": "function", "function": {"name": "web_search", "arguments": "{\"query\": \"杭州 景点\"}"}}]}
    chat(client, token, web_search=True)
    client.post("/v1/ai/web_search", headers=auth(token), json={"turn_id": "turn-0001", "tool_call_id": "s1", "query": "anything else"})
    assert json.loads(up.calls[-1].content)["query"] == "杭州 景点"


def test_oversized_body_rejected_before_reading(env):
    client, up, *_ = env
    r = client.post("/v1/auth/login", content=b"x" * 20_000, headers={"Content-Type": "application/json"})
    assert r.status_code == 413
    token = login(client).json()["token"]
    r = client.post("/v1/hotel/parse", headers=auth(token), content=b"\xff\xd8" + b"0" * (4 * 1024 * 1024))
    assert r.status_code == 413 and up.calls == []


def test_daily_lock_spares_networks_where_owner_logged_in(env):
    client, *_ = env
    assert login(client).status_code == 200  # from 10.0.0.1
    for i in range(50):
        TestClient(client.app, client=(f"10.1.{i}.1", 1)).post("/v1/auth/login", json={"username": "me", "password": "bad"})
    assert login(client).status_code == 200
    assert login(TestClient(client.app, client=("10.9.9.9", 1))).status_code == 429


def test_weather_cache_is_per_account(env):
    client, up, _, _, store = env
    store.run("INSERT INTO users (username, pw_hash, created) VALUES ('b', ?, 0)", (hasher.hash(PASSWORD),))
    ta = login(client).json()["token"]
    tb = client.post("/v1/auth/login", json={"username": "b", "password": PASSWORD}).json()["token"]
    w = {"kind": "now", "lat": 26.61, "lng": 106.71}
    client.post("/v1/weather", headers=auth(ta), json=w)
    r = client.post("/v1/weather", headers=auth(tb), json=w)
    assert len(up.calls) == 2  # b gets no hint that a asked here
    assert r.headers["X-Fetched-At"] != ""


def test_malformed_messages_are_400_not_500(env):
    client, up, *_ = env
    token = login(client).json()["token"]
    for messages in ([1], [{"role": "assistant", "content": "", "tool_calls": [{}]}], [{"role": "assistant", "tool_calls": [{"id": 1}]}]):
        r = client.post("/v1/ai/chat", headers=auth(token), json={"turn_id": "turn-0001", "messages": messages})
        assert r.status_code == 400
    assert up.calls == []


def test_owner_reserve(env):
    client, up, _, cfg, store = env
    cfg.owners = ("me",)
    cfg.limits.global_cost_per_month_yuan = 5.0
    for name in ("other", "other2"):
        store.run("INSERT INTO users (username, pw_hash, created) VALUES (?, ?, 0)", (name, hasher.hash(PASSWORD)))
    # Another account (other2, id 3) used up the shared amount this month.
    store.run("INSERT INTO usage (user_id, ts, kind, status, cost) VALUES (3, ?, 'chat', 'ok', 5.0)", (1_800_000_000.0,))
    other = client.post("/v1/auth/login", json={"username": "other", "password": PASSWORD}).json()["token"]
    assert chat(client, other, turn="turn-other").status_code == 429
    owner = login(client).json()["token"]
    assert chat(client, owner).status_code == 200
    # The owner's own spending doesn't count against the others' shared amount.
    store.run("UPDATE usage SET cost = 4.0 WHERE user_id = 3")
    store.run("INSERT INTO usage (user_id, ts, kind, status, cost) VALUES (1, ?, 'chat', 'ok', 9.0)", (1_800_000_000.0,))
    assert chat(client, other, turn="turn-other-2").status_code == 200


def test_weather_tool_accepts_hotel(env):
    client, up, *_ = env
    token = login(client).json()["token"]
    chat(client, token)
    tools = {t["function"]["name"]: t["function"] for t in json.loads(up.calls[-1].content)["tools"]}
    assert "hotel_id" in tools["get_weather"]["parameters"]["properties"]
