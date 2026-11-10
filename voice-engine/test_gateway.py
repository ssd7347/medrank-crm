"""Tests for the gateway, with a fake CRM and a small real WebSocket server in place of the engine.

    .venv\\Scripts\\python -m unittest test_gateway
"""

import socket
import threading
import time
import unittest

import httpx
import uvicorn
from fastapi import FastAPI, WebSocket
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

import gateway

FORM = b"CallUUID=abc-123&From=%2B919800000000&CallStatus=in-progress"
SIGNED = {"Content-Type": "application/x-www-form-urlencoded", "X-Plivo-Signature-V3": "sig==",
          "X-Plivo-Signature-V3-Nonce": "12345", "Cookie": "session=staff", "Authorization": "Bearer x"}


class FakeCrm:
    def __init__(self, status=200, down=False):
        self.seen, self.status, self.down = [], status, down

    def client(self):
        def handle(request: httpx.Request) -> httpx.Response:
            if self.down:
                raise httpx.ConnectError("refused", request=request)
            self.seen.append(request)
            return httpx.Response(self.status, content=b"<Response><Hangup/></Response>",
                                  headers={"content-type": "application/xml"})
        return httpx.AsyncClient(transport=httpx.MockTransport(handle))


class HookTest(unittest.TestCase):

    def setUp(self):
        self.crm = FakeCrm()
        self.real = gateway.crm_client
        gateway.crm_client = self.crm.client
        self.web = TestClient(gateway.app)

    def tearDown(self):
        gateway.crm_client = self.real

    def test_a_plivo_hook_reaches_the_crm_unchanged(self):
        r = self.web.post("/api/voice/hooks/plivo/answer?call_id=11111111-2222-3333-4444-555555555555",
                          content=FORM, headers=SIGNED)
        self.assertEqual(200, r.status_code)
        self.assertEqual(b"<Response><Hangup/></Response>", r.content)
        self.assertIn("application/xml", r.headers["content-type"])
        sent = self.crm.seen[0]
        self.assertEqual("POST", sent.method)
        self.assertEqual(gateway.CRM_URL + "/api/voice/hooks/plivo/answer?call_id=11111111-2222-3333-4444-555555555555",
                         str(sent.url))
        self.assertEqual(FORM, sent.content)                     # byte for byte, or the signature would not match
        self.assertEqual("sig==", sent.headers["x-plivo-signature-v3"])
        self.assertEqual("12345", sent.headers["x-plivo-signature-v3-nonce"])
        self.assertNotIn("cookie", sent.headers)
        self.assertNotIn("authorization", sent.headers)

    def test_hangup_and_desk_pass_and_the_crms_refusal_is_passed_back(self):
        for hook in ("hangup", "desk"):
            self.assertEqual(200, self.web.post(f"/api/voice/hooks/plivo/{hook}", content=FORM, headers=SIGNED).status_code)
        self.crm.status = 401
        self.assertEqual(401, self.web.post("/api/voice/hooks/plivo/answer", content=FORM, headers=SIGNED).status_code)

    def test_nothing_else_is_reachable(self):
        for method, path in (("post", "/api/voice/hooks/plivo/other"), ("post", "/api/auth/otp/request"),
                             ("post", "/api/auth/otp/verify"), ("get", "/api/contacts"),
                             ("post", "/api/voice/hooks/live/turn"), ("post", "/api/voice/hooks/tools/opt_out"),
                             ("post", "/stt"), ("post", "/tts"), ("get", "/health"), ("get", "/docs"),
                             ("get", "/openapi.json"), ("get", "/"), ("get", "/api/voice/hooks/plivo/answer")):
            r = self.web.request(method.upper(), path, content=b"x" if method == "post" else None)
            self.assertIn(r.status_code, (404, 405), f"{method} {path}")
        self.assertEqual([], self.crm.seen)

    def test_a_large_body_is_refused(self):
        r = self.web.post("/api/voice/hooks/plivo/answer", content=b"x" * (gateway.MAX_BODY_BYTES + 1), headers=SIGNED)
        self.assertEqual(413, r.status_code)
        self.assertEqual([], self.crm.seen)

    def test_a_crm_that_is_down_gives_502(self):
        self.crm.down = True
        self.assertEqual(502, self.web.post("/api/voice/hooks/plivo/answer", content=FORM, headers=SIGNED).status_code)


# A stand-in engine: refuses a wrong token before accepting (as engine.py does), echoes, and ends after "bye".
engine = FastAPI()


@engine.websocket("/call")
async def fake_call(ws: WebSocket, call_id: str = "", token: str = ""):
    if token != "good":
        await ws.close(code=1008)
        return
    await ws.accept()
    while True:
        msg = await ws.receive()
        if msg["type"] == "websocket.disconnect":
            return
        if msg.get("text") == "bye":
            await ws.send_text("goodbye " + call_id)
            await ws.close()
            return
        if msg.get("text") is not None:
            await ws.send_text("heard " + msg["text"])
        else:
            await ws.send_bytes(msg["bytes"][::-1])


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class StreamTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        port = free_port()
        cls.server = uvicorn.Server(uvicorn.Config(engine, host="127.0.0.1", port=port, log_level="warning"))
        threading.Thread(target=cls.server.run, daemon=True).start()
        for _ in range(100):
            if cls.server.started:
                break
            time.sleep(0.05)
        cls.real = gateway.ENGINE_URL
        gateway.ENGINE_URL = f"http://127.0.0.1:{port}"

    @classmethod
    def tearDownClass(cls):
        gateway.ENGINE_URL = cls.real
        cls.server.should_exit = True

    def test_the_call_stream_flows_both_ways_and_ends_with_the_engine(self):
        with TestClient(gateway.app).websocket_connect("/call?call_id=c1&token=good") as ws:
            ws.send_text('{"event":"start"}')
            self.assertEqual('heard {"event":"start"}', ws.receive_text())
            ws.send_bytes(b"\x01\x02\x03")
            self.assertEqual(b"\x03\x02\x01", ws.receive_bytes())
            ws.send_text("bye")
            self.assertEqual("goodbye c1", ws.receive_text())   # the call id reached the engine
            with self.assertRaises(WebSocketDisconnect):
                ws.receive_text()                                # the engine closed, so the gateway closed

    def test_a_wrong_token_is_refused(self):
        for query in ("call_id=c1&token=bad", "call_id=c1", ""):
            with self.assertRaises(WebSocketDisconnect) as e:
                with TestClient(gateway.app).websocket_connect("/call" + (f"?{query}" if query else "")) as ws:
                    ws.receive_text()
            self.assertEqual(1008, e.exception.code, query)

    def test_an_engine_that_is_down_refuses_the_stream(self):
        real, gateway.ENGINE_URL = gateway.ENGINE_URL, f"http://127.0.0.1:{free_port()}"
        try:
            with self.assertRaises(WebSocketDisconnect) as e:
                with TestClient(gateway.app).websocket_connect("/call?call_id=c1&token=good") as ws:
                    ws.receive_text()
            self.assertEqual(1008, e.exception.code)
        finally:
            gateway.ENGINE_URL = real


if __name__ == "__main__":
    unittest.main()
