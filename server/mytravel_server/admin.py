"""Owner's commands, run on the host. There is no admin endpoint over HTTP.

    python -m mytravel_server.admin create-user NAME
    python -m mytravel_server.admin set-password NAME     (also revokes all of NAME's sessions)
    python -m mytravel_server.admin list-sessions [NAME]
    python -m mytravel_server.admin revoke-session ID
    python -m mytravel_server.admin revoke-all NAME
    python -m mytravel_server.admin unlock NAME           (clears the daily failed-login lock)
    python -m mytravel_server.admin disable-user NAME | enable-user NAME
    python -m mytravel_server.admin usage [--days N]
"""

import argparse
import getpass
import sys
import time
from datetime import datetime
from pathlib import Path

from .app import hasher
from .config import load_config
from .store import Store

MIN_PASSWORD = 10


def ask_password() -> str:
    if not sys.stdin.isatty():
        # For scripted test accounts: the password arrives on stdin, never on the command line.
        p = sys.stdin.readline().rstrip("\n")
        if len(p) < MIN_PASSWORD:
            sys.exit("密码太短")
        return p
    p = getpass.getpass("新密码（至少 10 位，不要与其他平台相同）：")
    if len(p) < MIN_PASSWORD:
        sys.exit("密码太短")
    if getpass.getpass("再输入一次：") != p:
        sys.exit("两次输入不一致")
    return p


def user_id(store, name) -> int:
    row = store.one("SELECT id FROM users WHERE username = ?", (name,))
    if row is None:
        sys.exit(f"没有用户 {name}")
    return row["id"]


def fmt(ts) -> str:
    return datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M") if ts else "-"


def main(argv=None):
    ap = argparse.ArgumentParser(prog="mytravel_server.admin")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for c in ("create-user", "set-password", "revoke-all", "unlock", "disable-user", "enable-user"):
        sub.add_parser(c).add_argument("name")
    sub.add_parser("list-sessions").add_argument("name", nargs="?")
    sub.add_parser("revoke-session").add_argument("id", type=int)
    sub.add_parser("usage").add_argument("--days", type=int, default=30)
    a = ap.parse_args(argv)

    cfg = load_config()
    Path(cfg.db_path).parent.mkdir(parents=True, exist_ok=True)
    store = Store(cfg.db_path)
    now = time.time()

    if a.cmd == "create-user":
        if store.one("SELECT 1 FROM users WHERE username = ?", (a.name,)):
            sys.exit("用户已存在")
        store.run("INSERT INTO users (username, pw_hash, created) VALUES (?, ?, ?)", (a.name, hasher.hash(ask_password()), now))
        print(f"已创建 {a.name}")
    elif a.cmd == "set-password":
        uid = user_id(store, a.name)
        store.run("UPDATE users SET pw_hash = ? WHERE id = ?", (hasher.hash(ask_password()), uid))
        n = store.run("UPDATE sessions SET revoked = ? WHERE user_id = ? AND revoked IS NULL", (now, uid)).rowcount
        print(f"已修改密码，撤销 {n} 个会话")
    elif a.cmd == "revoke-all":
        n = store.run("UPDATE sessions SET revoked = ? WHERE user_id = ? AND revoked IS NULL", (now, user_id(store, a.name))).rowcount
        print(f"已撤销 {n} 个会话")
    elif a.cmd == "revoke-session":
        n = store.run("UPDATE sessions SET revoked = ? WHERE id = ? AND revoked IS NULL", (now, a.id)).rowcount
        print("已撤销" if n else "没有这个有效会话")
    elif a.cmd == "unlock":
        store.run("UPDATE users SET fail_reset = ? WHERE id = ?", (now, user_id(store, a.name)))
        print("已解除今日登录锁定（同一网络连续输错的 15 分钟锁定会自动解除）")
    elif a.cmd in ("disable-user", "enable-user"):
        store.run("UPDATE users SET disabled = ? WHERE id = ?", (int(a.cmd == "disable-user"), user_id(store, a.name)))
        print("已停用" if a.cmd == "disable-user" else "已启用")
    elif a.cmd == "list-sessions":
        sql = "SELECT s.*, u.username FROM sessions s JOIN users u ON u.id = s.user_id"
        rows = store.all(sql + (" WHERE u.username = ?" if a.name else "") + " ORDER BY s.id", (a.name,) if a.name else ())
        for r in rows:
            state = "已撤销" if r["revoked"] else ("已过期" if r["expires"] <= now else "有效")
            print(f"#{r['id']} {r['username']} {state} 创建 {fmt(r['created'])} 最近使用 {fmt(r['last_used'])} 到期 {fmt(r['expires'])} {r['note'] or ''}")
    elif a.cmd == "usage":
        rows = store.all(
            "SELECT u.username, g.kind, SUM(g.count) AS n, SUM(g.in_tok) AS i, SUM(g.out_tok) AS o, SUM(g.cost) AS c, "
            "SUM(g.status != 'ok') AS err FROM usage g JOIN users u ON u.id = g.user_id WHERE g.ts >= ? GROUP BY u.username, g.kind",
            (now - a.days * 86400,),
        )
        print(f"最近 {a.days} 天（费用为按单价估算，以服务商账单为准）")
        for r in rows:
            print(f"{r['username']} {r['kind']}: {r['n']} 次，失败 {r['err']}，输入 {r['i']} / 输出 {r['o']} Token，约 {r['c']:.3f} 元")


if __name__ == "__main__":
    main()
