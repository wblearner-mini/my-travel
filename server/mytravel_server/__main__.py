"""Starts the server: python -m mytravel_server [--host H ...] [--port P] [--cert C --key K].

--host can be given more than once, e.g. 127.0.0.1 (USB via adb reverse) and the Tailscale address.
Listening only on chosen addresses keeps the port off the LAN and the internet."""

import argparse
import logging
import socket
from pathlib import Path

import uvicorn

from .app import create_app
from .config import load_config
from .store import Store


def main():
    ap = argparse.ArgumentParser(prog="mytravel_server")
    ap.add_argument("--host", action="append", help="address to listen on; repeatable (default 127.0.0.1)")
    ap.add_argument("--port", type=int, default=18443)
    ap.add_argument("--cert", help="TLS certificate (PEM)")
    ap.add_argument("--key", help="TLS private key (PEM)")
    a = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    cfg = load_config()
    Path(cfg.db_path).parent.mkdir(parents=True, exist_ok=True)
    app = create_app(cfg, Store(cfg.db_path))
    # No access log: it would hold client addresses and paths; usage is recorded by the app itself.
    # proxy_headers off: no proxy sits in front, so X-Forwarded-For from a client must not change its IP.
    sockets = []
    for host in a.host or ["127.0.0.1"]:
        s = socket.socket(socket.AF_INET6 if ":" in host else socket.AF_INET)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((host, a.port))
        sockets.append(s)
    config = uvicorn.Config(app, ssl_certfile=a.cert, ssl_keyfile=a.key, access_log=False, proxy_headers=False)
    uvicorn.Server(config).run(sockets=sockets)


if __name__ == "__main__":
    main()
