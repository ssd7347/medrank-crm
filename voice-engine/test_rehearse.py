"""Tests for rehearse.py (the pretend Plivo), against a small fake CRM-and-engine served on this machine.

    .venv\\Scripts\\python -m unittest test_rehearse
"""

import asyncio
import base64
import io
import json
import socket
import threading
import time
import unittest
import wave
from urllib.parse import parse_qsl

import numpy as np
import requests
import uvicorn
from fastapi import FastAPI, Request, Response, WebSocket
from starlette.websockets import WebSocketDisconnect

import phone
import rehearse

TOKEN = "MATOKEN1234567890abcdef"       # the same values as the CRM's PlivoSignatureTest
NONCE = "12345678901234567890"


def tone(seconds, hz=300.0, level=8000.0, rate=8000):
    t = np.arange(int(seconds * rate)) / rate
    return (level * np.sin(2 * np.pi * hz * t)).astype(np.int16)


def wav_bytes(pcm, rate, channels=1):
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())
    return buf.getvalue()


class SignatureTest(unittest.TestCase):

    def test_signatures_match_the_crms(self):
        answer = "https://crm.example.in/api/voice/hooks/plivo/answer?call_id=6f1c2b8e-0000-4000-8000-000000000001"
        self.assertEqual(rehearse.signature(TOKEN, answer, {"CallUUID": "abc-123", "From": "+919812345678",
                                                            "To": "+918045671234", "Direction": "outbound",
                                                            "CallStatus": "in-progress"}, NONCE),
                         "3R2+wql2r8z/+hSzdJS/mGHd/t2+ZOw8mXC9l5nwjQo=")
        hangup = "https://crm.example.in/api/voice/hooks/plivo/hangup"
        self.assertEqual(rehearse.signature(TOKEN, hangup, {"CallUUID": "abc-123", "Duration": "42",
                                                            "HangupCause": "NORMAL_CLEARING"}, NONCE),
                         "4tXoTpOiKZElBwRVJrUZvTnthpAYT4z5/kQHvUzhgn4=")
        self.assertEqual(rehearse.signature(TOKEN, "https://crm.example.in/api/voice/hooks/plivo/desk", {}, NONCE),
                         "BldF3REnTi+LpUDm6JJubNcUKqRvtCo+7dU1wGTAdN8=")


class AudioTest(unittest.TestCase):

    def test_audio_goes_out_in_20_ms_messages(self):
        parts = rehearse.frames(tone(1.01))
        self.assertEqual((len(parts), {len(p) for p in parts}), (51, {160}))
        back = phone.ulaw_decode(b"".join(parts))[:8000]
        self.assertLess(np.abs(back.astype(int) - tone(1.0).astype(int)).max(), 300)     # mu-law's rounding

    def test_a_recording_at_any_rate_becomes_8_khz(self):
        self.assertEqual(len(rehearse.read_wav(wav_bytes(tone(2.0, rate=22050), 22050))), 16000)
        stereo = np.repeat(tone(1.0, rate=16000), 2)
        self.assertEqual(len(rehearse.read_wav(wav_bytes(stereo, 16000, channels=2))), 8000)

    def test_text_is_spoken_by_the_engines_voice(self):
        asked = []

        class Res:
            content = wav_bytes(tone(0.5, rate=22050), 22050)

            def raise_for_status(self):
                pass

        def post(url, json, timeout):
            asked.append((url, json))
            return Res()
        audio = rehearse.caller_line("Can I speak to a person", "http://127.0.0.1:8090/", post=post)
        self.assertEqual(asked, [("http://127.0.0.1:8090/tts", {"text": "Can I speak to a person"})])
        self.assertEqual(len(audio), 4000)


# ---------------------------------------------------------------------------------------------- a fake CRM + engine

SEEN = {"hooks": [], "bad_signatures": 0, "forms": []}
MODE = {"end": "transfer", "answer": "stream", "reply": "end"}
fake = FastAPI()


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@fake.post("/api/voice/hooks/plivo/{hook}")
async def hook(hook: str, request: Request):
    form = dict(parse_qsl((await request.body()).decode()))
    query = request.url.query
    url = f"{FakeCall.base}/api/voice/hooks/plivo/{hook}" + (f"?{query}" if query else "")
    nonce = request.headers.get("x-plivo-signature-v3-nonce", "")
    if request.headers.get("x-plivo-signature-v3") != rehearse.signature(TOKEN, url, form, nonce):
        SEEN["bad_signatures"] += 1
        return Response("bad signature", 401)
    SEEN["hooks"].append(hook + (f"?{query}" if query else ""))
    SEEN["forms"].append(form)
    if hook == "answer" and MODE["answer"] == "refuse":
        return Response("<Response><Hangup/></Response>", media_type="application/xml")
    if hook == "answer" and MODE["answer"] == "speak":
        return Response("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Speak>Sorry, we cannot take this call "
                        "right now. A member of our team will call you back.</Speak><Hangup/></Response>",
                        media_type="application/xml")
    if hook == "answer" and MODE["answer"] == "desk":
        return Response("<Response><Dial callerId=\"+918000000001\"><Number>+914400000000</Number></Dial></Response>",
                        media_type="application/xml")
    if hook == "answer":
        ws = FakeCall.base.replace("http", "ws") + "/call?call_id=c1&amp;token=t1"
        return Response(f"<Response><Stream bidirectional=\"true\">{ws}</Stream><Hangup/></Response>",
                        media_type="application/xml")
    if hook == "desk":
        return Response("<Response><Dial><Number>+914400000000</Number></Dial></Response>", media_type="application/xml")
    return Response("", media_type="application/xml")


@fake.websocket("/call")
async def call(ws: WebSocket, call_id: str = "", token: str = ""):
    """Says an opening line, waits for the caller to speak and stop, says goodbye, then does what MODE["end"] says."""
    await ws.accept()

    async def say(name):
        payload = base64.b64encode(phone.ulaw_encode(tone(0.2))).decode()
        await ws.send_text(json.dumps({"event": "playAudio", "media": {"payload": payload}}))
        await ws.send_text(json.dumps({"event": "checkpoint", "name": name}))
    uuid, spoke, quiet = "", False, 0
    try:
        while True:
            m = json.loads(await ws.receive_text())
            if m["event"] == "start":
                uuid = m["start"]["callId"]
                await say("say-1")
            elif m["event"] == "media":
                loud = np.abs(phone.ulaw_decode(base64.b64decode(m["media"]["payload"])).astype(int)).mean() > 500
                spoke, quiet = spoke or loud, 0 if loud else quiet + 1
                if spoke and quiet == 20:
                    await say("end-2" if MODE["reply"] == "end" else "say-2")
                    spoke = False
            elif m["event"] == "playedStream" and m["name"] == "end-2":
                api = f"http://127.0.0.1:{FakeCall.api.port}/v1/Account/A/Call/{uuid}/"
                if MODE["end"] == "hang up":
                    await asyncio.to_thread(requests.delete, api, timeout=5)
                else:
                    await asyncio.to_thread(requests.post, api, json={"legs": "aleg", "aleg_url": "http://x/desk"}, timeout=5)
                if MODE["end"] == "transfer, then close":
                    await ws.close()            # what the engine did before 15 Nov: it could cut the caller off
                    return
    except WebSocketDisconnect:
        return


class FakeCall(unittest.TestCase):
    base = ""
    api = None

    @classmethod
    def setUpClass(cls):
        port = free_port()
        cls.server = uvicorn.Server(uvicorn.Config(fake, host="127.0.0.1", port=port, log_level="warning"))
        threading.Thread(target=cls.server.run, daemon=True).start()
        for _ in range(100):
            if cls.server.started:
                break
            time.sleep(0.05)
        FakeCall.base = f"http://127.0.0.1:{port}"
        FakeCall.api = rehearse.PretendPlivoApi(0)

    @classmethod
    def tearDownClass(cls):
        cls.server.should_exit = True
        FakeCall.api.close()

    def setUp(self):
        SEEN.update(hooks=[], bad_signatures=0, forms=[])
        MODE.update(end="transfer", answer="stream", reply="end")
        self.said = []

    def run_call(self, speed=4.0, **kwargs):
        hooks = rehearse.Hooks(self.base, self.base, TOKEN)
        speech = np.concatenate([tone(0.6), np.zeros(800, dtype=np.int16)])
        caller = {} if kwargs.get("outbound") else {"caller": "+919876500001", "number": "+918000000001"}
        return asyncio.run(rehearse.rehearse(hooks, self.api, [speech], ["Can I speak to a real person"],
                                             speed=speed, wait_s=10, say=self.said.append, **caller, **kwargs))

    def dial_soon(self, call_id="c-out"):
        """What the CRM does when a campaign calls someone: asks Plivo to place the call, a moment from now."""
        def dial():
            time.sleep(0.3)
            requests.post(f"http://127.0.0.1:{self.api.port}/v1/Account/A/Call/", timeout=5, json={
                "from": "+918000000001", "to": "+919876500002",
                "answer_url": f"{self.base}/api/voice/hooks/plivo/answer?call_id={call_id}",
                "hangup_url": f"{self.base}/api/voice/hooks/plivo/hangup?call_id={call_id}"})
        threading.Thread(target=dial, daemon=True).start()

    def test_an_outbound_call_is_answered_on_the_crms_own_urls(self):
        self.dial_soon()
        r = self.run_call(outbound=True, dial_wait_s=10)
        self.assertEqual((r["ending"], r["dialled"]), ("transferred", "+919876500002"))
        self.assertTrue(r["request_uuid"].startswith("rehearsal-req-"))
        self.assertEqual(SEEN["hooks"], ["answer?call_id=c-out", "desk", "hangup?call_id=c-out"])
        self.assertEqual(SEEN["bad_signatures"], 0)
        answered = SEEN["forms"][0]
        self.assertEqual((answered["Direction"], answered["From"], answered["To"], answered["RequestUUID"]),
                         ("outbound", "+918000000001", "+919876500002", r["request_uuid"]))
        self.assertEqual(answered["CallUUID"], r["call_uuid"])     # Plivo's call id, which the CRM links to its call

    def test_an_outbound_call_nobody_answers(self):
        self.dial_soon()
        r = self.run_call(outbound=True, answer=False, dial_wait_s=10)
        self.assertEqual((r["ending"], r["turns"]), ("not answered", []))
        self.assertEqual(SEEN["hooks"], ["hangup?call_id=c-out"])
        self.assertEqual(SEEN["forms"][0]["CallStatus"], "no-answer")

    def test_a_crm_that_never_dials_is_reported(self):
        r = self.run_call(outbound=True, dial_wait_s=0.3)
        self.assertEqual(r["ending"], "not dialled")
        self.assertEqual(SEEN["hooks"], [])

    def test_a_caller_who_runs_out_of_lines_hangs_up_at_once(self):
        MODE["reply"] = "say"           # the agent carries on, no goodbye
        t = time.monotonic()
        r = self.run_call()
        self.assertEqual(r["ending"], "caller hung up")
        self.assertLess(time.monotonic() - t, 8)        # not the 15 s wait for a goodbye's hang-up
        self.assertEqual(SEEN["hooks"], ["answer", "hangup"])

    def test_a_url_off_the_crms_public_address_is_not_followed(self):
        hooks = rehearse.Hooks(self.base, self.base, TOKEN)
        with self.assertRaisesRegex(ValueError, "not on its PLIVO_PUBLIC_URL"):
            hooks("answer", {}, "https://elsewhere.example/api/voice/hooks/plivo/answer?call_id=x")
        self.assertEqual(SEEN["hooks"], [])

    def test_a_transferred_call_is_followed_to_the_desk_and_hung_up(self):
        r = self.run_call(speed=1.0)        # in real time, so the reply time means something
        self.assertEqual((r["ending"], r["stream_open_at_transfer"], r["desk"]), ("transferred", True, "+914400000000"))
        self.assertEqual((SEEN["hooks"], SEEN["bad_signatures"]), (["answer", "desk", "hangup"], 0))
        self.assertEqual([t["said"] for t in r["turns"]], ["Can I speak to a real person"])
        # The fake answers after 20 quiet messages, 15 of them after the caller's audio ended: about 0.3 s.
        self.assertGreater(r["turns"][0]["reply_s"], 0.2)
        self.assertTrue(any("still open" in s for s in self.said), self.said)

    def test_an_engine_that_closes_the_stream_at_the_transfer_is_caught(self):
        MODE["end"] = "transfer, then close"
        r = self.run_call()
        self.assertEqual((r["ending"], r["stream_open_at_transfer"]), ("transferred", False))
        self.assertTrue(any("ALREADY CLOSED" in s for s in self.said), self.said)

    def test_the_newer_websockets_client_is_judged_the_same(self):
        # websockets.connect is the older client until websockets 14; the newer one has no ``open``, only ``state``.
        import websockets.asyncio.client
        for end, still_open in (("transfer", True), ("transfer, then close", False)):
            MODE["end"] = end
            r = self.run_call(connect=websockets.asyncio.client.connect)
            self.assertEqual((r["ending"], r["stream_open_at_transfer"]), ("transferred", still_open), end)

    def test_a_call_the_crm_hangs_up(self):
        MODE["end"] = "hang up"
        r = self.run_call()
        self.assertEqual((r["ending"], r["stream_open_at_transfer"]), ("hung_up", None))
        self.assertEqual(SEEN["hooks"], ["answer", "hangup"])

    def test_a_call_the_crm_does_not_take(self):
        MODE["answer"] = "refuse"
        r = self.run_call()
        self.assertEqual((r["ending"], r["turns"]), ("refused", []))
        self.assertEqual(SEEN["hooks"], ["answer"])

    def test_a_call_answered_without_the_agent_says_what_the_caller_hears(self):
        MODE["answer"] = "speak"        # the CRM's reply while the speech engine is down
        r = self.run_call()
        self.assertEqual((r["ending"], r["spoken"], r["desk"], r["turns"]),
                         ("spoken", "Sorry, we cannot take this call right now. A member of our team will call you "
                                    "back.", None, []))
        self.assertTrue(any('Plivo says "Sorry, we cannot take this call' in s for s in self.said), self.said)
        MODE["answer"] = "desk"
        r = self.run_call()
        self.assertEqual((r["ending"], r["spoken"], r["desk"]), ("desk", None, "+914400000000"))
        self.assertEqual(SEEN["hooks"], ["answer", "hangup", "answer", "hangup"])    # Plivo reports each call's end

    def test_a_wrong_token_is_refused_by_the_crm(self):
        hooks = rehearse.Hooks(self.base, self.base, "some-other-token")
        with self.assertRaisesRegex(RuntimeError, "answer callback was refused: HTTP 401"):
            hooks("answer", {"CallUUID": "x"})
        self.assertEqual(SEEN["bad_signatures"], 1)

    def test_the_pretend_api_answers_like_plivo(self):
        base = f"http://127.0.0.1:{self.api.port}/v1/Account/A/Call/u-1/"
        before = len(self.api.requests)
        self.assertEqual(requests.post(base, json={"legs": "aleg"}, timeout=5).status_code, 202)
        self.assertEqual(requests.delete(base, timeout=5).status_code, 204)
        self.assertEqual(requests.post(base + "x", timeout=5).status_code, 404)
        self.assertEqual([(r["method"], r["call"]) for r in self.api.requests[before:]],
                         [("POST", "u-1"), ("DELETE", "u-1")])
        placed = requests.post(base[:-len("u-1/")], json={"to": "+919876500002"}, timeout=5)
        self.assertEqual(placed.status_code, 201)
        self.assertEqual(self.api.dialled(before)["call"], placed.json()["request_uuid"])
        self.assertEqual(requests.delete(base[:-len("u-1/")], timeout=5).status_code, 404)


if __name__ == "__main__":
    unittest.main()
