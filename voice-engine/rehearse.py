"""A whole phone call on this machine, with no phone line: this script plays Plivo.

It rings the CRM through the gateway exactly as Plivo would (a signed "answer" callback), opens the call stream the
CRM hands back, and plays the caller's lines into it as 8 kHz phone audio in real time, while it plays the agent's
replies back (it waits as long as they last, then says "played", as Plivo does). It also stands in for Plivo's API,
which the CRM calls to transfer or hang up the call. At the end it says what happened and how long each reply took.

    CRM ──(transfer / hang up)──> this script's pretend Plivo API (:8098)
    this script ──(signed callbacks, call stream)──> gateway (:8099) ──> CRM and speech engine

The CRM must be set up for it: VOICE_PLATFORM=plivo, PLIVO_API_BASE=http://127.0.0.1:8098/v1,
PLIVO_PUBLIC_URL=http://127.0.0.1:8099, PLIVO_ENGINE_URL=ws://127.0.0.1:8099/call, any PLIVO_AUTH_ID and
PLIVO_FROM_NUMBER, and a made-up PLIVO_AUTH_TOKEN that this script is given too. Never the real account's token,
and never on a CRM that real calls go through.

Each caller line is a WAV file (a recording) or text, spoken by the engine's own voice (its /tts, on this machine):

    python rehearse.py --auth-token <made-up token> --from +919876500001 "Hello" "Can I speak to a real person"
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import hmac
import html
import io
import json
import os
import re
import threading
import time
import uuid
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qsl, urlsplit

import numpy as np

import phone

FRAME = 160                 # 20 ms of 8 kHz mu-law, what Plivo sends per message
QUIET = 30.0                # the line's background hiss (a level, in 16-bit samples)


# ---------------------------------------------------------------------------------------------- signing


def signature(auth_token: str, url: str, form: dict[str, str], nonce: str) -> str:
    """Plivo's V3 signature of a POST callback (the CRM's PlivoSignature; the same test values)."""
    parts = urlsplit(url)
    bare = f"{parts.scheme}://{parts.netloc}{parts.path}"
    query = "&".join(f"{k}={v}" for k, v in sorted(parse_qsl(parts.query, keep_blank_values=True)))
    base = bare
    if query or form:
        base += "?" + query
    if query and form:
        base += "."
    base += "".join(f"{k}{v}" for k, v in sorted(form.items()))
    mac = hmac.new(auth_token.encode(), f"{base}.{nonce}".encode(), hashlib.sha256).digest()
    return base64.b64encode(mac).decode()


# ---------------------------------------------------------------------------------------------- audio


def frames(pcm: np.ndarray) -> list[bytes]:
    """8 kHz 16-bit samples as mu-law messages of 20 ms each; the last one padded with quiet."""
    data = phone.ulaw_encode(pcm)
    if len(data) % FRAME:
        data += phone.ulaw_encode(hiss(FRAME - len(data) % FRAME))
    return [data[i:i + FRAME] for i in range(0, len(data), FRAME)]


def hiss(samples: int, seed: int = 7) -> np.ndarray:
    return np.random.default_rng(seed).normal(0, QUIET, samples).astype(np.int16)


def read_wav(data: bytes) -> np.ndarray:
    """Any 16-bit WAV (mono or stereo, any rate) as 8 kHz 16-bit samples."""
    with wave.open(io.BytesIO(data), "rb") as w:
        if w.getsampwidth() != 2:
            raise ValueError("only 16-bit WAV files")
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32)
        if w.getnchannels() > 1:
            x = x.reshape(-1, w.getnchannels()).mean(axis=1)
        rate = w.getframerate()
    return np.clip(phone.resample(x, rate, phone.RATE), -32768, 32767).astype(np.int16)


def caller_line(line: str, engine_url: str, post=None) -> np.ndarray:
    """A WAV file's audio, or the text spoken by the engine's voice."""
    if line.lower().endswith(".wav") and os.path.isfile(line):
        with open(line, "rb") as f:
            return read_wav(f.read())
    import requests

    r = (post or requests.post)(engine_url.rstrip("/") + "/tts", json={"text": line}, timeout=60)
    r.raise_for_status()
    return read_wav(r.content)


# ---------------------------------------------------------------------------------------------- Plivo's API


class PretendPlivoApi:
    """What the CRM calls on Plivo: transfer (POST .../Call/<uuid>/) and hang up (DELETE .../Call/<uuid>/)."""

    PATH = re.compile(r"^/v1/Account/[^/]+/Call/([^/]+)/$")

    def __init__(self, port: int) -> None:
        self.requests: list[dict] = []
        api = self

        class Handler(BaseHTTPRequestHandler):
            def _answer(self, method: str) -> None:
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                m = api.PATH.match(self.path)
                if not m:
                    self.send_response(404)
                    self.end_headers()
                    return
                api.requests.append({"method": method, "call": m.group(1), "at": time.monotonic(),
                                     "body": json.loads(body) if body else {}})
                if method == "DELETE":
                    self.send_response(204)
                    self.end_headers()
                    return
                reply = b'{"api_id":"rehearsal","message":"call transferred"}'
                self.send_response(202)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(reply)))
                self.end_headers()
                self.wfile.write(reply)

            def do_POST(self):
                self._answer("POST")

            def do_DELETE(self):
                self._answer("DELETE")

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def first(self, call: str, since: int = 0) -> dict | None:
        return next((r for r in self.requests[since:] if r["call"] == call), None)

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


# ---------------------------------------------------------------------------------------------- the call


class Hooks:
    """Plivo's signed callbacks, through the gateway. ``public_url`` is the CRM's PLIVO_PUBLIC_URL (what it checks
    the signature against); ``gateway`` is where they are sent."""

    def __init__(self, gateway: str, public_url: str, auth_token: str, post=None) -> None:
        import requests

        self.gateway, self.public = gateway.rstrip("/"), public_url.rstrip("/")
        self.token, self.post = auth_token, post or requests.post
        self.sent: list[str] = []

    def __call__(self, hook: str, form: dict[str, str]) -> str:
        path = f"/api/voice/hooks/plivo/{hook}"
        nonce = uuid.uuid4().hex
        r = self.post(self.gateway + path, data=form, timeout=20, headers={
            "X-Plivo-Signature-V3": signature(self.token, self.public + path, form, nonce),
            "X-Plivo-Signature-V3-Nonce": nonce})
        self.sent.append(f"{hook} {r.status_code}")
        if r.status_code != 200:
            raise RuntimeError(f"the {hook} callback was refused: HTTP {r.status_code} {r.text[:200]}")
        return r.text


async def rehearse(hooks: Hooks, api: PretendPlivoApi, lines: list[np.ndarray], said: list[str], caller: str,
                   number: str, speed: float = 1.0, wait_s: float = 60.0, connect=None, say=print) -> dict:
    """One incoming call from ``caller`` to ``number``; returns what happened. ``speed`` above 1 plays audio faster
    than real time (tests)."""
    import websockets

    call_uuid = "rehearsal-" + uuid.uuid4().hex[:12]
    result: dict = {"call_uuid": call_uuid, "turns": [], "ending": None, "stream_open_at_transfer": None}
    t0 = time.monotonic()
    xml = await asyncio.to_thread(hooks, "answer", {"CallUUID": call_uuid, "From": caller, "To": number,
                                                     "Direction": "inbound", "CallStatus": "ringing"})
    m = re.search(r"<Stream[^>]*>([^<]+)</Stream>", xml)
    if not m:
        result["ending"] = "refused"
        say(f"The CRM did not take the call (no approved incoming script, or Plivo is not set up): {xml.strip()}")
        return result
    url = html.unescape(m.group(1).strip())
    say(f"answered: the CRM streams the call to {url.split('?')[0]}")

    ws = await (connect or websockets.connect)(url)
    replies: asyncio.Queue = asyncio.Queue()
    closed = asyncio.Event()
    agent = {"audio": bytearray(), "first": None}
    outgoing: asyncio.Queue = asyncio.Queue()

    async def listen() -> None:
        try:
            async for raw in ws:
                msg = json.loads(raw)
                if msg.get("event") == "playAudio":
                    if agent["first"] is None:
                        agent["first"] = time.monotonic()
                    agent["audio"] += base64.b64decode(msg["media"]["payload"])
                elif msg.get("event") == "checkpoint":
                    await replies.put((msg["name"], bytes(agent["audio"]), agent["first"]))
                    agent["audio"], agent["first"] = bytearray(), None
        except Exception:
            pass
        finally:
            closed.set()

    async def line() -> None:
        """The caller's side of the line: their speech when they talk, the line's hiss otherwise, every 20 ms."""
        quiet = frames(hiss(FRAME * 50))
        n = 0
        while not closed.is_set():
            try:
                f = outgoing.get_nowait()
            except asyncio.QueueEmpty:
                f, n = quiet[n % len(quiet)], n + 1
            if callable(f):
                f()             # a marker: the speech before it has been sent
                continue
            try:
                await ws.send(json.dumps({"event": "media", "media": {"track": "inbound", "payload":
                                                                      base64.b64encode(f).decode()}}))
            except Exception:
                return
            await asyncio.sleep(0.02 / speed)

    async def heard(what: str) -> tuple[str, float, float] | None:
        """Waits for the agent's next reply, plays it (as long as it lasts), says "played"; returns its checkpoint."""
        try:
            name, audio, first = await asyncio.wait_for(replies.get(), wait_s)
        except asyncio.TimeoutError:
            say(f"no reply from the agent within {wait_s:.0f} s ({what})")
            return None
        seconds = len(audio) / phone.RATE
        await asyncio.sleep(seconds / speed)
        await ws.send(json.dumps({"event": "playedStream", "streamId": "rehearsal", "name": name}))
        return name, seconds, first

    listener = asyncio.create_task(listen())
    await ws.send(json.dumps({"event": "start", "start": {"streamId": "rehearsal", "callId": call_uuid}}))
    talker = asyncio.create_task(line())
    try:
        got = await heard("opening")
        if got:
            name, seconds, first = got
            say(f"agent: opening line, {seconds:.1f} s of speech, began {first - t0:.1f} s after the call was answered")
        for audio, text in zip(lines, said):
            if not got or got[0].startswith("end"):
                break
            spoke = {}
            for f in frames(audio):
                await outgoing.put(f)
            await outgoing.put(lambda: spoke.setdefault("at", time.monotonic()))
            say(f"caller: {text} ({len(audio) / phone.RATE:.1f} s)")
            got = await heard(text)
            if not got:
                break
            name, seconds, first = got
            reply_s = first - spoke.get("at", first)
            result["turns"].append({"said": text, "reply_s": round(reply_s, 2), "agent_s": round(seconds, 1)})
            say(f"agent: {seconds:.1f} s of speech, began {reply_s:.1f} s after the caller stopped talking"
                f" (the engine first waits 0.7 s of quiet to know they have finished)")
        # The goodbye has been played: the CRM now hangs up or transfers through Plivo's API.
        deadline = time.monotonic() + 15
        request = None
        while time.monotonic() < deadline and not request:
            request = api.first(call_uuid)
            if not request:
                await asyncio.sleep(0.05)
        if request and request["method"] == "POST":
            result["ending"] = "transferred"
            # Plivo needs a moment to move the call; an engine that closes the stream at once would cut the caller off.
            await asyncio.sleep(1.0)
            result["stream_open_at_transfer"] = not closed.is_set()
            leg = request["body"].get("aleg_url", "")
            say(f"the CRM asked Plivo to transfer the call (to {leg}); the stream was "
                + ("still open, as it must be" if result["stream_open_at_transfer"] else "ALREADY CLOSED by the engine"))
            desk = await asyncio.to_thread(hooks, "desk", {"CallUUID": call_uuid, "From": caller, "To": number})
            dial = re.search(r"<Number>([^<]+)</Number>", desk)
            result["desk"] = dial.group(1) if dial else None
            say(f"desk: Plivo would now ring {result['desk']}" if dial else f"desk: no number to ring: {desk.strip()}")
        elif request:
            result["ending"] = "hung_up"
            say("the CRM asked Plivo to hang up")
        else:
            result["ending"] = "nothing"
            say("the CRM asked Plivo for nothing after the goodbye: is PLIVO_API_BASE this script's pretend API?")
    finally:
        await ws.close()        # Plivo closes the stream once it has moved or ended the call
        talker.cancel()
        listener.cancel()
    duration = int(time.monotonic() - t0)
    await asyncio.to_thread(hooks, "hangup", {"CallUUID": call_uuid, "CallStatus": "completed",
                                              "Duration": str(duration), "HangupCause": "NORMAL_CLEARING"})
    say(f"hung up after {duration} s; the call is on the CRM's /voice/calls page")
    return result


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("lines", nargs="+", help="what the caller says, in order: text, or a 16-bit WAV file")
    ap.add_argument("--auth-token", default=os.environ.get("PLIVO_AUTH_TOKEN", ""),
                    help="the made-up PLIVO_AUTH_TOKEN the CRM was started with")
    ap.add_argument("--from", dest="caller", required=True, help="the caller's number, +91...")
    ap.add_argument("--to", default="+918000000001", help="the CRM's PLIVO_FROM_NUMBER")
    ap.add_argument("--gateway", default="http://127.0.0.1:8099")
    ap.add_argument("--public-url", default=None, help="the CRM's PLIVO_PUBLIC_URL (default: the gateway)")
    ap.add_argument("--engine", default="http://127.0.0.1:8090", help="the speech engine, for its voice")
    ap.add_argument("--api-port", type=int, default=8098, help="where the pretend Plivo API listens")
    a = ap.parse_args()
    if not a.auth_token:
        ap.error("--auth-token (or PLIVO_AUTH_TOKEN) is needed: the same made-up token the CRM was started with")
    audio = [caller_line(x, a.engine) for x in a.lines]
    api = PretendPlivoApi(a.api_port)
    try:
        result = asyncio.run(rehearse(Hooks(a.gateway, a.public_url or a.gateway, a.auth_token), api, audio, a.lines,
                                      a.caller, a.to))
    finally:
        api.close()
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
