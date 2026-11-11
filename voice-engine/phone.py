"""
A real phone call on our own speech engine. Plivo streams the call's audio both ways over a WebSocket (engine.py,
``/call``): 8 kHz mu-law, about 20 ms per message. Here the caller's speech is cut into utterances, Whisper turns
each into text, the CRM decides what the agent says (signed POST /api/voice/hooks/live/{open,turn,end}, the same
brains, tools and rules as the test console), and Piper speaks it back into the call.

  * The caller can talk over the agent: the agent's queued audio is cleared and it listens.
  * After the agent's goodbye has been heard (Plivo's "playedStream" for our checkpoint), the CRM hangs up.

Pure numpy (no audioop: it is gone from Python 3.13), so it is tested without a phone line: test_phone.py.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import logging
import time
from dataclasses import dataclass
from typing import Awaitable, Callable

import numpy as np

log = logging.getLogger("phone")

RATE = 8000                 # Plivo's stream: audio/x-mulaw;rate=8000
FRAME = 160                 # 20 ms at 8 kHz
STT_RATE = 16000            # what Whisper expects

# ---------------------------------------------------------------------------------------------- mu-law (G.711)

_BIAS, _CLIP = 0x84, 32635


def ulaw_decode(data: bytes) -> np.ndarray:
    """mu-law bytes to 16-bit samples."""
    u = ~np.frombuffer(data, dtype=np.uint8) & 0xFF
    sign = u & 0x80
    exponent = (u >> 4) & 0x07
    mantissa = u & 0x0F
    sample = (((mantissa.astype(np.int32) << 3) + _BIAS) << exponent) - _BIAS
    return np.where(sign != 0, -sample, sample).astype(np.int16)


def ulaw_encode(pcm: np.ndarray) -> bytes:
    """16-bit samples to mu-law bytes."""
    s = pcm.astype(np.int32)
    sign = np.where(s < 0, 0x80, 0)
    s = np.minimum(np.abs(s), _CLIP) + _BIAS
    top = (s >> 7) & 0xFF
    exponent = np.where(top > 0, np.floor(np.log2(np.maximum(top, 1))), 0).astype(np.int32)
    mantissa = (s >> (exponent + 3)) & 0x0F
    return (~(sign | (exponent << 4) | mantissa) & 0xFF).astype(np.uint8).tobytes()


# ---------------------------------------------------------------------------------------------- resampling


def _lowpass(x: np.ndarray, cutoff_hz: float, rate: int, taps: int = 101) -> np.ndarray:
    n = np.arange(taps) - (taps - 1) / 2
    h = np.sinc(2 * cutoff_hz / rate * n) * np.hamming(taps)
    return np.convolve(x, h / h.sum(), mode="same")


def resample(x: np.ndarray, src: int, dst: int) -> np.ndarray:
    """float32 samples from ``src`` Hz to ``dst`` Hz. Anything above the narrower band is filtered out first (or,
    going up, afterwards), so Piper's 22 kHz voice does not alias into the 8 kHz phone line."""
    x = x.astype(np.float32)
    if src == dst or len(x) == 0:
        return x
    if dst < src:
        x = _lowpass(x, 0.45 * dst, src)
    t = np.arange(int(len(x) * dst / src)) * (src / dst)
    y = np.interp(t, np.arange(len(x)), x).astype(np.float32)
    return _lowpass(y, 0.45 * src, dst).astype(np.float32) if dst > src else y


# ---------------------------------------------------------------------------------------------- end of speech


class Utterances:
    """Cuts the caller's audio into utterances by loudness: speech starts after 60 ms above the background, ends
    after 700 ms below it. The background level is learnt from the quiet parts of the call."""

    START_FRAMES = 3            # 60 ms of speech starts an utterance
    END_FRAMES = 35             # 700 ms of quiet ends it
    BARGE_FRAMES = 15           # 300 ms of speech counts as talking over the agent
    PRE_ROLL = 10               # 200 ms kept from before the start
    MIN_SPEECH = 12             # shorter than 240 ms of speech: a cough or a click
    MAX_FRAMES = 1000           # 20 s at most

    def __init__(self) -> None:
        self.floor = 200.0
        self.buf = np.zeros(0, dtype=np.int16)
        self.frames: list[np.ndarray] = []
        self.pre: list[np.ndarray] = []
        self.loud = 0               # consecutive loud frames
        self.quiet = 0              # consecutive quiet frames inside an utterance
        self.speech = 0             # loud frames in the current utterance
        self.active = False

    def talking(self) -> bool:
        """True once the caller has spoken long enough to count as talking over the agent."""
        return self.active and self.loud >= self.BARGE_FRAMES

    def feed(self, pcm: np.ndarray) -> list[np.ndarray]:
        """Adds audio; returns the utterances it completed."""
        self.buf = np.concatenate([self.buf, pcm])
        done = []
        while len(self.buf) >= FRAME:
            frame, self.buf = self.buf[:FRAME], self.buf[FRAME:]
            u = self._frame(frame)
            if u is not None:
                done.append(u)
        return done

    def _frame(self, frame: np.ndarray) -> np.ndarray | None:
        rms = float(np.sqrt(np.mean(frame.astype(np.float32) ** 2)))
        loud = rms > max(3.0 * self.floor, 300.0)
        self.loud = self.loud + 1 if loud else 0
        if not self.active:
            if not loud:
                self.floor = 0.95 * self.floor + 0.05 * rms
            self.pre = (self.pre + [frame])[-self.PRE_ROLL:]
            if self.loud >= self.START_FRAMES:
                self.active, self.frames, self.quiet, self.speech = True, list(self.pre), 0, self.loud
            return None
        self.frames.append(frame)
        if loud:
            self.quiet, self.speech = 0, self.speech + 1
        else:
            self.quiet += 1
        if self.quiet >= self.END_FRAMES or len(self.frames) >= self.MAX_FRAMES:
            frames, speech = self.frames, self.speech
            self.active, self.frames, self.pre, self.loud, self.quiet = False, [], [], 0, 0
            if speech >= self.MIN_SPEECH:
                return np.concatenate(frames)
        return None


# ---------------------------------------------------------------------------------------------- the CRM


@dataclass
class Reply:
    say: str
    over: bool
    hint: str = ""              # on opening: the called contact's name, given to Whisper on every turn (never said)


def stream_token(secret: str, call_id: str) -> str:
    """The token the CRM puts in Plivo's stream URL (PlivoHooksController.streamToken)."""
    return hmac.new(secret.encode(), f"stream:{call_id}".encode(), hashlib.sha256).hexdigest()


def sign(secret: str, timestamp: str, body: str) -> str:
    """The CRM's request signature (VoiceSignatures.sign)."""
    return "sha256=" + hmac.new(secret.encode(), f"{timestamp}.{body}".encode(), hashlib.sha256).hexdigest()


class Crm:
    """Signed calls to the CRM's live-call hooks."""

    def __init__(self, base_url: str, secret: str, post=None) -> None:
        import requests

        self.base, self.secret = base_url.rstrip("/"), secret
        self.post = post or requests.post

    def _call(self, step: str, payload: dict) -> dict:
        body = json.dumps(payload, separators=(",", ":"))
        ts = str(int(time.time()))
        r = self.post(f"{self.base}/api/voice/hooks/live/{step}", data=body.encode(), timeout=20,
                      headers={"Content-Type": "application/json", "X-Voice-Timestamp": ts,
                               "X-Voice-Signature": sign(self.secret, ts, body)})
        r.raise_for_status()
        return r.json()

    def open(self, call_id: str) -> Reply:
        d = self._call("open", {"call_id": call_id})
        return Reply(d.get("say") or "", bool(d.get("over")), str(d.get("hint") or "").strip()[:100])

    def turn(self, call_id: str, said: str) -> Reply:
        d = self._call("turn", {"call_id": call_id, "said": said})
        return Reply(d.get("say") or "", bool(d.get("over")))

    def end(self, call_id: str) -> None:
        self._call("end", {"call_id": call_id})


# ---------------------------------------------------------------------------------------------- one call

TROUBLE = "Sorry, I am having trouble on this line. A member of our team will call you back. Goodbye."


class Call:
    """One Plivo stream. ``send`` sends a JSON message to Plivo; ``stt`` takes 16 kHz float32 audio and returns
    text, and once the CRM has given the called contact's name, takes that name as a second argument (the hint that
    makes Whisper spell it as registered); ``tts`` takes text and returns (int16 samples, sample rate). All three
    are passed in, so the call logic is tested with fakes."""

    def __init__(self, call_id: str, send: Callable[[dict], Awaitable[None]], crm: Crm,
                 stt: Callable[[np.ndarray], str], tts: Callable[[str], tuple[np.ndarray, int]],
                 close: Callable[[], Awaitable[None]]) -> None:
        self.call_id, self.send, self.crm, self.stt, self.tts, self.close = call_id, send, crm, stt, tts, close
        self.stream_id = ""
        self.ears = Utterances()
        self.jobs: asyncio.Queue = asyncio.Queue()
        self.worker: asyncio.Task | None = None
        self.speaking = ""          # the checkpoint of the audio being played, "" when quiet
        self.marks = 0
        self.ending = ""            # the goodbye's checkpoint once the agent has finished
        self.hint = ""              # the called contact's name, from the CRM's opening reply
        self.done = False

    async def handle(self, msg: dict) -> None:
        event = msg.get("event")
        if event == "start":
            self.stream_id = (msg.get("start") or {}).get("streamId", "")
            self.worker = asyncio.create_task(self._work())
            await self.jobs.put(("open", None))
        elif event == "media" and not self.done:
            media = msg.get("media") or {}
            if media.get("track", "inbound") != "inbound":
                return
            for utterance in self.ears.feed(ulaw_decode(base64.b64decode(media.get("payload") or ""))):
                if not self.ending:
                    await self.jobs.put(("turn", utterance))
            if self.speaking and not self.ending and self.ears.talking():
                # The caller talks over the agent: stop speaking and listen.
                self.speaking = ""
                await self.send({"event": "clearAudio", "streamId": self.stream_id})
        elif event == "playedStream":
            name = msg.get("name", "")
            if name == self.speaking:
                self.speaking = ""
            if self.ending and name == self.ending:
                await self.hang_up()

    async def _work(self) -> None:
        while not self.done:
            kind, audio = await self.jobs.get()
            try:
                if kind == "open":
                    reply = await asyncio.to_thread(self.crm.open, self.call_id)
                    self.hint = reply.hint
                else:
                    pcm = resample(audio, RATE, STT_RATE) / 32768.0
                    hint = (self.hint,) if self.hint else ()
                    text = (await asyncio.to_thread(self.stt, pcm, *hint)).strip()
                    if not text:
                        continue
                    reply = await asyncio.to_thread(self.crm.turn, self.call_id, text)
            except Exception as e:     # the CRM or a model failed: say so and end, rather than go silent
                log.warning("call %s: %s failed: %s", self.call_id, kind, e)
                reply = Reply(TROUBLE, True)
            await self.speak(reply)

    async def speak(self, reply: Reply) -> None:
        self.marks += 1
        mark = f"{'end' if reply.over else 'say'}-{self.marks}"
        if reply.say:
            pcm, rate = await asyncio.to_thread(self.tts, reply.say)
            audio = ulaw_encode(np.clip(resample(pcm.astype(np.float32), rate, RATE), -32768, 32767))
            for i in range(0, len(audio), 4000):           # half a second per message
                await self.send({"event": "playAudio", "media": {"contentType": "audio/x-mulaw", "sampleRate": RATE,
                                                                 "payload": base64.b64encode(audio[i:i + 4000]).decode()}})
            self.speaking = mark
            await self.send({"event": "checkpoint", "streamId": self.stream_id, "name": mark})
        if reply.over:
            self.ending = mark
            if not reply.say:
                await self.hang_up()
            else:
                # In case Plivo never confirms the goodbye was played: hang up after it surely has.
                asyncio.get_running_loop().call_later(len(audio) / RATE + 5,
                                                      lambda: asyncio.ensure_future(self.hang_up()))

    async def hang_up(self) -> None:
        if self.done:
            return
        self.done = True
        try:
            await asyncio.to_thread(self.crm.end, self.call_id)
        except Exception as e:
            log.warning("call %s: hang-up failed: %s", self.call_id, e)
        await self.close()

    def stop(self) -> None:
        """The stream closed (the caller hung up, or the call was transferred)."""
        self.done = True
        if self.worker:
            self.worker.cancel()
