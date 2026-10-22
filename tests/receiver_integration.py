#!/usr/bin/env python3
"""Protocol smoke test for the Windows receiver logic (run on Linux/macOS too).
Uses a tiny local mock phone to exercise pairing, streaming, checksum verification, commit, and ACK.
"""
import hashlib
import json
import os
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PHOTO = (b"\xff\xd8\xff\xe0SnapToPrint-test-image-bytes\xff\xd9" * 100)
SHA = hashlib.sha256(PHOTO).hexdigest()
TOKEN = "a" * 64
SESSION_ID = "abcdef01-2345-6789-abcd-ef0123456789"
STARTED = 1792677600000
state = {"acked": False}

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _send(self, status, body, content_type="application/json", headers=None):
        if isinstance(body, str):
            body = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def do_GET(self):
        if self.path == "/api/v1/health":
            return self._send(200, json.dumps({"ok": True, "service": "snap-to-print", "version": 1}))
        if self.headers.get("X-Snap-Token") != TOKEN:
            return self._send(401, '{"error":"unauthorized"}')
        if self.path == "/api/v1/session/current":
            return self._send(200, json.dumps({"sessionId": SESSION_ID, "name": "Guest magnets", "startedAtMs": STARTED, "active": True, "status": "ACTIVE"}))
        if self.path == "/api/v1/queue/next":
            if state["acked"]:
                return self._send(200, '{"empty":true}')
            photo = {"photoId":"photo-001", "sessionId":SESSION_ID, "eventName":"Guest magnets", "sessionStartedAtMs":STARTED, "sequence":1, "fileName":"IMG_001.jpg", "mimeType":"image/jpeg", "captureTimeMs":STARTED+1200, "size":len(PHOTO), "sha256":SHA}
            return self._send(200, json.dumps({"empty":False, "photo":photo}))
        if self.path == "/api/v1/photos/photo-001/content":
            if self.headers.get("X-Snap-Token") != TOKEN:
                return self._send(401, '{"error":"unauthorized"}')
            return self._send(200, PHOTO, "application/octet-stream", {"X-Photo-Sha256":SHA, "X-Photo-Size":str(len(PHOTO))})
        return self._send(404, '{"error":"not_found"}')

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.path == "/api/v1/pair":
            payload = json.loads(body)
            if payload.get("code") != "123456":
                return self._send(403, '{"error":"invalid_pairing_code"}')
            return self._send(200, json.dumps({"token":TOKEN, "protocolVersion":1}))
        if self.headers.get("X-Snap-Token") != TOKEN:
            return self._send(401, '{"error":"unauthorized"}')
        if self.path == "/api/v1/photos/photo-001/ack":
            p = json.loads(body)
            if p.get("sha256") != SHA or p.get("size") != len(PHOTO):
                return self._send(409, '{"error":"verification_mismatch"}')
            state["acked"] = True
            return self._send(200, '{"accepted":true,"photoId":"photo-001"}')
        return self._send(404, '{"error":"not_found"}')


def main():
    # Reuse the compiled host binary when present; otherwise build it locally.
    with tempfile.TemporaryDirectory(prefix="snap-print-test-") as td:
        td = Path(td)
        binary = td / "receiver"
        build = subprocess.run(["go", "build", "-o", str(binary), "."], cwd=ROOT / "windows", capture_output=True, text=True)
        if build.returncode != 0:
            raise RuntimeError(build.stderr)
        server = ThreadingHTTPServer(("127.0.0.1", 8765), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        dest = td / "photos"
        env = os.environ.copy()
        env["XDG_CONFIG_HOME"] = str(td / "config")
        proc = subprocess.Popen([str(binary), "-phone", "http://127.0.0.1:8765", "-dest", str(dest)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env)
        try:
            proc.stdin.write("123456\n")
            proc.stdin.flush()
            deadline = time.time() + 12
            result = None
            while time.time() < deadline:
                files = list(dest.rglob("*.jpg")) if dest.exists() else []
                if files and state["acked"]:
                    result = files[0]
                    break
                if proc.poll() is not None:
                    raise RuntimeError("receiver exited unexpectedly\n" + proc.stdout.read())
                time.sleep(0.1)
            if result is None:
                raise RuntimeError("receiver did not save and acknowledge the test photo\n" + (proc.stdout.read(5000) if proc.poll() is not None else "process still running"))
            if result.read_bytes() != PHOTO:
                raise AssertionError("saved bytes differ from source")
            if list(dest.rglob("*.part")):
                raise AssertionError("temporary .part file remains after successful delivery")
            print("PASS: pair -> poll -> download -> SHA-256 check -> atomic final file -> ACK")
            print("PASS: saved", result.relative_to(dest), f"({len(PHOTO)} bytes)")
        finally:
            proc.terminate()
            try: proc.wait(timeout=3)
            except subprocess.TimeoutExpired: proc.kill()
            server.shutdown()
            server.server_close()

if __name__ == "__main__":
    main()
