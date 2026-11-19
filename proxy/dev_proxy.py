#!/usr/bin/env python3
"""Stdlib-only dev reverse proxy with the same routing as proxy/nginx.conf.

    python3 proxy/dev_proxy.py            # listens on :8000
    /api/* -> http://127.0.0.1:8080, everything else -> http://127.0.0.1:5173
Override with HRMS_PROXY_PORT, HRMS_BACKEND_URL, HRMS_FRONTEND_URL.
"""
import http.client
import os
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LISTEN_PORT = int(os.environ.get("HRMS_PROXY_PORT", "8000"))
BACKEND = os.environ.get("HRMS_BACKEND_URL", "http://127.0.0.1:8080")
FRONTEND = os.environ.get("HRMS_FRONTEND_URL", "http://127.0.0.1:5173")
HOP_HEADERS = {"connection", "keep-alive", "transfer-encoding", "upgrade", "proxy-connection", "te", "trailer"}


def upstream_for(path: str) -> str:
    """Route /api and /api/... to the backend, everything else to the frontend."""
    return BACKEND if path == "/api" or path.startswith("/api/") else FRONTEND


class ProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _proxy(self):
        target = urllib.parse.urlsplit(upstream_for(self.path.split("?", 1)[0]))
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_HEADERS}
        headers["X-Forwarded-For"] = self.client_address[0]
        conn = http.client.HTTPConnection(target.hostname, target.port, timeout=60)
        try:
            conn.request(self.command, self.path, body=body, headers=headers)
            resp = conn.getresponse()
            data = resp.read()
        except OSError as exc:
            self.send_error(502, f"Upstream unavailable: {exc}")
            return
        finally:
            conn.close()
        self.send_response(resp.status, resp.reason)
        for key, value in resp.getheaders():
            if key.lower() not in HOP_HEADERS and key.lower() != "content-length":
                self.send_header(key, value)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = do_OPTIONS = _proxy

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    print(f"HRMS dev proxy on :{LISTEN_PORT} (/api -> {BACKEND}, / -> {FRONTEND})", flush=True)
    ThreadingHTTPServer(("0.0.0.0", LISTEN_PORT), ProxyHandler).serve_forever()
