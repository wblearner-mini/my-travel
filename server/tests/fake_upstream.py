"""A stand-in for Bailian's chat endpoint, for on-device checks without paid calls.

    python tests/fake_upstream.py 18444

First reply in a turn: one plan_route tool call (the app then closes the AI panel to show the
route). After the tool result: a short text. Every request's history is checked: each assistant
tool_call must be followed by its tool result. Prints one line per request (roles only)."""

import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

n = 0


class H(BaseHTTPRequestHandler):
    def do_POST(self):
        global n
        n += 1
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        msgs = body["messages"]
        ok, open_ids = True, set()
        for m in msgs:
            if open_ids and m["role"] != "tool":
                ok = False
            if m["role"] == "assistant":
                open_ids = {c["id"] for c in m.get("tool_calls", [])}
            elif m["role"] == "tool":
                open_ids.discard(m["tool_call_id"])
        ok = ok and not open_ids
        last = msgs[-1]["role"]
        if last == "user":
            msg = {"role": "assistant", "content": "", "tool_calls": [{"id": f"call{n}", "type": "function", "function": {
                "name": "plan_route", "arguments": json.dumps({"date": "2026-11-11", "mode": "drive", "start": "first"})}}]}
        else:
            msg = {"role": "assistant", "content": f"（模拟回复）路线已显示。请求 {n}，历史格式{'正确' if ok else '错误'}。"}
        print(f"request {n}: roles={[m['role'] for m in msgs][1:]} history_ok={ok}", flush=True)
        out = json.dumps({"choices": [{"message": msg}], "usage": {"prompt_tokens": 10, "completion_tokens": 5}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *a):
        pass


HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
