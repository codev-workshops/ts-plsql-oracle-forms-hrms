#!/usr/bin/env python3
"""Checks the dev proxy routing: /api -> backend :8080, / -> frontend :5173.

Run: python3 proxy/test_proxy_config.py   (stdlib only; starts fake upstreams on free ports)
"""
import http.client
import os
import re
import socket
import subprocess
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import dev_proxy  # noqa: E402


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def fake_upstream(name: str):
    class Handler(BaseHTTPRequestHandler):
        def _reply(self):
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else b""
            payload = f"{name} {self.command} {self.path} {body.decode()}".encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        do_GET = do_POST = _reply

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", free_port()), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


class NginxConfigTest(unittest.TestCase):
    conf = (HERE / "nginx.conf").read_text()

    def test_listens_on_8000(self):
        self.assertRegex(self.conf, r"listen\s+8000;")

    def test_upstreams(self):
        self.assertRegex(self.conf, r"upstream hrms_backend\s*\{\s*server 127\.0\.0\.1:8080;")
        self.assertRegex(self.conf, r"upstream hrms_frontend\s*\{\s*server 127\.0\.0\.1:5173;")

    def test_locations(self):
        locations = dict(re.findall(r"location\s+(\S+)\s*\{[^}]*?proxy_pass\s+http://(\w+);", self.conf, re.S))
        self.assertEqual(locations, {"/api/": "hrms_backend", "/": "hrms_frontend"})


class DevProxyRoutingTest(unittest.TestCase):
    def test_defaults_match_nginx(self):
        self.assertEqual(dev_proxy.LISTEN_PORT, int(os.environ.get("HRMS_PROXY_PORT", "8000")))
        self.assertTrue(dev_proxy.BACKEND.endswith(":8080") or "HRMS_BACKEND_URL" in os.environ)
        self.assertTrue(dev_proxy.FRONTEND.endswith(":5173") or "HRMS_FRONTEND_URL" in os.environ)

    def test_upstream_for(self):
        self.assertEqual(dev_proxy.upstream_for("/api/v1/leave/types"), dev_proxy.BACKEND)
        self.assertEqual(dev_proxy.upstream_for("/api"), dev_proxy.BACKEND)
        self.assertEqual(dev_proxy.upstream_for("/"), dev_proxy.FRONTEND)
        self.assertEqual(dev_proxy.upstream_for("/leave"), dev_proxy.FRONTEND)
        self.assertEqual(dev_proxy.upstream_for("/apix"), dev_proxy.FRONTEND)


class DevProxyEndToEndTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.backend = fake_upstream("backend")
        cls.frontend = fake_upstream("frontend")
        cls.port = free_port()
        env = dict(os.environ, HRMS_PROXY_PORT=str(cls.port),
                   HRMS_BACKEND_URL=f"http://127.0.0.1:{cls.backend.server_port}",
                   HRMS_FRONTEND_URL=f"http://127.0.0.1:{cls.frontend.server_port}")
        cls.proc = subprocess.Popen([sys.executable, str(HERE / "dev_proxy.py")], env=env,
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(50):
            try:
                socket.create_connection(("127.0.0.1", cls.port), timeout=0.2).close()
                break
            except OSError:
                time.sleep(0.1)

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        cls.proc.wait(5)
        cls.backend.shutdown()
        cls.frontend.shutdown()

    def fetch(self, method, path, body=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        conn.request(method, path, body=body, headers={"Content-Type": "application/json"} if body else {})
        resp = conn.getresponse()
        return resp.status, resp.read().decode()

    def test_api_goes_to_backend(self):
        self.assertEqual(self.fetch("GET", "/api/v1/health?x=1"), (200, "backend GET /api/v1/health?x=1 "))

    def test_api_post_body_forwarded(self):
        self.assertEqual(self.fetch("POST", "/api/v1/auth/login", '{"a":1}'),
                         (200, 'backend POST /api/v1/auth/login {"a":1}'))

    def test_root_goes_to_frontend(self):
        self.assertEqual(self.fetch("GET", "/leave"), (200, "frontend GET /leave "))


if __name__ == "__main__":
    unittest.main(verbosity=2)
