"""
A real phone call on our own speech engine. Plivo streams the call's audio both ways over a WebSocket (engine.py,
``/call``): 8 kHz mu-law, about 20 ms per message. Here the caller's speech is cut into utterances, Whisper turns
each into text, the CRM decides what the agent says (signed POST /api/voice/hooks/live/{open,turn,end}, the same
brains, tools and rules as the test console), and Piper speaks it back into the call.

  * The caller can talk over the agent: the agent's queued audio is cleared and it listens.
  * After the agent's goodbye has been heard (Plivo's "playedStream" for our checkpoint), the CRM hangs up.
  * When the stream closes, what the caller said that the CRM has not had yet is sent as their last words (live/last).
  * A call the CRM says to record (its opening told the person it may be recorded) is recorded, both voices as the
    caller heard them, and sent to the CRM as Ogg Opus once the call is over (live/recording; Recorder).
  * The call-back a caller was promised (live/trouble) and their last words (live/last, maybe "stop calling me") are
    kept in an outbox folder when the CRM cannot be reached, and sent again until it takes them (Outbox).

Pure numpy (no audioop: it is gone from Python 3.13), so it is tested without a phone line: test_phone.py.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import itertools
import json
import logging
import os
import re
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
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


# ---------------------------------------------------------------------------------------------- recording

class Recorder:
    """Both sides of one call, as the caller heard them. The caller's audio arrives in real time and is the call's
    clock; the agent's audio is sent ahead of time and Plivo plays it in order, so each piece is placed where it starts
    playing: when it was sent, or when the piece before it ends. When the caller talks over the agent, the queued
    audio is cleared (``cut``): what had not been played yet is dropped. At most MAX_S seconds are kept."""

    MAX_S = 3 * 3600
    BIT_RATE = 16000            # Opus: clear speech at about 120 kB a minute

    def __init__(self) -> None:
        self.caller: list[np.ndarray] = []
        self.heard = 0                                  # caller samples so far: now, on the call's clock
        self.agent: list[tuple[int, np.ndarray]] = []   # (where it starts playing, samples)
        self.agent_end = 0                              # where the agent's queued audio ends

    def hear(self, pcm: np.ndarray) -> None:
        if self.heard + len(pcm) <= self.MAX_S * RATE:
            self.caller.append(pcm.astype(np.int16))
            self.heard += len(pcm)

    def say(self, pcm: np.ndarray) -> None:
        start = max(self.agent_end, self.heard)
        if start + len(pcm) <= self.MAX_S * RATE:
            self.agent.append((start, pcm.astype(np.int16)))
            self.agent_end = start + len(pcm)

    def cut(self) -> None:
        kept = []
        for start, pcm in self.agent:
            if start < self.heard:
                kept.append((start, pcm[:self.heard - start]))
        self.agent = kept
        self.agent_end = min(self.agent_end, self.heard)

    def mix(self) -> np.ndarray:
        """One 8 kHz track with both voices, int16."""
        caller = np.concatenate(self.caller) if self.caller else np.zeros(0, np.int16)
        out = np.zeros(max(len(caller), self.agent_end), np.int32)
        out[:len(caller)] += caller
        for start, pcm in self.agent:
            out[start:start + len(pcm)] += pcm
        return np.clip(out, -32768, 32767).astype(np.int16)

    def ogg(self) -> bytes:
        """The mix as Ogg Opus (what the CRM keeps), or b"" when nothing was recorded."""
        pcm = self.mix()
        if not len(pcm):
            return b""
        import io

        import av

        buf = io.BytesIO()
        with av.open(buf, "w", format="ogg") as box:
            stream = box.add_stream("libopus", rate=RATE, layout="mono")
            stream.bit_rate = self.BIT_RATE
            frame = av.AudioFrame.from_ndarray(pcm.reshape(1, -1), format="s16", layout="mono")
            frame.sample_rate = RATE
            for packet in stream.encode(frame):
                box.mux(packet)
            for packet in stream.encode(None):
                box.mux(packet)
        return buf.getvalue()


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

    def flush(self) -> np.ndarray | None:
        """The call ended: the utterance still going (its 700 ms of quiet never came), if it holds enough speech."""
        frames, speech, active = self.frames, self.speech, self.active
        self.active, self.frames, self.pre, self.loud, self.quiet, self.speech = False, [], [], 0, 0, 0
        return np.concatenate(frames) if active and speech >= self.MIN_SPEECH else None

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


# ---------------------------------------------------------------------------------------------- unfinished turns

# A caller who stops to remember something ("My date of birth is ... fifteenth August") pauses longer than the 700 ms
# that ends a turn. Whisper's text for the first part shows it: "My birth of death is..." (ear check, 15 Nov 2026).
# Words nobody ends a sentence on, even with a full stop; "um" and "uh" are a caller thinking:
NEVER_LAST = frozenset({"my", "your", "our", "the", "an", "and", "or", "but", "of", "to", "for", "from", "with",
                        "um", "uh", "er", "erm", "because"})
# Words a sentence seldom ends on, though an answer can ("Yes, it is."): unfinished only without a full stop.
SELDOM_LAST = NEVER_LAST | {"a", "is", "are", "was", "were", "at", "in", "on", "so", "like", "about", "number", "id",
                            "its", "it's"}


def unfinished(text: str) -> bool:
    """True when what the caller said looks cut off by a pause: it trails off ("...", a comma, a dash) or stops on a
    word nobody ends on. Then the call waits a moment longer for the rest before answering."""
    t = text.strip()
    words = re.findall(r"[a-z0-9']+", t.lower())             # digits count: "my number is 98400" is finished
    if not words:
        return False
    if t.endswith(("...", "…", ",", "-", "–", "—")):
        return True
    return words[-1] in (NEVER_LAST if t[-1] in ".?!" else SELDOM_LAST)


# ---------------------------------------------------------------------------------------------- the CRM


@dataclass
class Reply:
    say: str
    over: bool
    hint: str = ""              # on opening: the called contact's name, given to Whisper on every turn (never said)
    record: bool = False        # on opening: record this call (its opening told the person it may be recorded)
    call_back: bool = True      # the team can call this person back (False: number withheld, or asked not to be called)


def stream_token(secret: str, call_id: str) -> str:
    """The token the CRM puts in Plivo's stream URL (PlivoHooksController.streamToken)."""
    return hmac.new(secret.encode(), f"stream:{call_id}".encode(), hashlib.sha256).hexdigest()


def sign(secret: str, timestamp: str, body: str) -> str:
    """The CRM's request signature (VoiceSignatures.sign)."""
    return "sha256=" + hmac.new(secret.encode(), f"{timestamp}.{body}".encode(), hashlib.sha256).hexdigest()


def refused(e: Exception) -> bool:
    """The CRM answered that it will never take this request (400: malformed, 404: no such call, for example a test
    call or one deleted since). Anything else (not reachable, timed out, 401 while the secrets differ, 5xx) may work
    later."""
    return getattr(getattr(e, "response", None), "status_code", None) in (400, 404)


class Outbox:
    """Requests the CRM must get even when it is down at the moment they are made: the call-back a caller was promised
    ("trouble") and the caller's last words ("last", which may be "stop calling me"). Each waits as one JSON file in
    ``folder`` until the CRM takes it; ``deliver_all`` sends them again, oldest first (the engine runs it every RETRY_S
    seconds and at start-up, so they also survive a restart). The files hold what callers said: each is deleted as
    soon as the CRM has it, and none is kept longer than MAX_AGE_S. Recordings are not kept here (a call that was not
    recorded breaks no promise), so the outbox stays small."""

    STEPS = ("trouble", "last")
    RETRY_S = 30.0
    MAX_AGE_S = 7 * 24 * 3600.0
    _seq = itertools.count()

    def __init__(self, folder: str | Path, clock: Callable[[], float] = time.time) -> None:
        self.folder, self.clock = Path(folder), clock

    def put(self, step: str, payload: dict) -> None:
        self.folder.mkdir(parents=True, exist_ok=True)
        # Oldest first by name: the clock (coarse on Windows), then a counter for the same tick, then a random part.
        name = f"{time.time_ns():020d}-{next(self._seq):08d}-{uuid.uuid4().hex[:8]}-{step}.json"
        tmp = self.folder / (name + ".tmp")         # written whole, then renamed: never read half-written
        tmp.write_text(json.dumps({"step": step, "payload": payload, "since": self.clock()}), encoding="utf-8")
        os.replace(tmp, self.folder / name)

    def waiting(self) -> int:
        return len(list(self.folder.glob("*.json"))) if self.folder.is_dir() else 0

    def deliver_all(self, crm: "Crm") -> int:
        """Sends what is waiting, oldest first; stops at the first one the CRM does not take yet (it is still down),
        to try again later. Returns how many the CRM took."""
        taken = 0
        for path in sorted(self.folder.glob("*.json")) if self.folder.is_dir() else []:
            try:
                item = json.loads(path.read_text(encoding="utf-8"))
                step, payload, since = item["step"], item["payload"], float(item["since"])
            except (OSError, ValueError, KeyError, TypeError) as e:
                log.error("outbox: %s cannot be read (%s): deleted", path.name, e)
                path.unlink(missing_ok=True)
                continue
            call_id = payload.get("call_id") if isinstance(payload, dict) else None
            if step not in self.STEPS or not isinstance(payload, dict):
                log.error("outbox: %s is not a request this engine sends: deleted", path.name)
                path.unlink(missing_ok=True)
                continue
            if self.clock() - since > self.MAX_AGE_S:
                log.error("outbox: call %s: %s NEVER REACHED THE CRM in %d days and is deleted. Read the call's "
                          "transcript and act on it by hand.", call_id, step, self.MAX_AGE_S // 86400)
                path.unlink(missing_ok=True)
                continue
            try:
                crm._call(step, payload)
            except Exception as e:
                if not refused(e):
                    return taken            # still not reachable: the rest wait their turn
                log.warning("outbox: call %s: the CRM refused %s (%s): deleted", call_id, step, e)
                path.unlink(missing_ok=True)
                continue
            path.unlink(missing_ok=True)
            taken += 1
            log.info("outbox: call %s: %s delivered", call_id, step)
        return taken


class Crm:
    """Signed calls to the CRM's live-call hooks."""

    def __init__(self, base_url: str, secret: str, post=None, outbox: Outbox | None = None) -> None:
        import requests

        self.base, self.secret = base_url.rstrip("/"), secret
        self.post = post or requests.post
        self.outbox = outbox

    def _call(self, step: str, payload: dict, timeout: float = 20) -> dict:
        body = json.dumps(payload, separators=(",", ":"))
        ts = str(int(time.time()))
        r = self.post(f"{self.base}/api/voice/hooks/live/{step}", data=body.encode(), timeout=timeout,
                      headers={"Content-Type": "application/json", "X-Voice-Timestamp": ts,
                               "X-Voice-Signature": sign(self.secret, ts, body)})
        r.raise_for_status()
        return r.json()

    # The opening only reads the CRM's database (the brain warms up in the background), so it answers within a
    # second; a CRM that takes longer is broken, and the caller should not wait 20 s in silence for the goodbye.
    OPEN_TIMEOUT_S = 8

    def open(self, call_id: str) -> Reply:
        d = self._call("open", {"call_id": call_id}, timeout=self.OPEN_TIMEOUT_S)
        return Reply(d.get("say") or "", bool(d.get("over")), str(d.get("hint") or "").strip()[:100],
                     d.get("record") is True, d.get("call_back") is not False)

    def turn(self, call_id: str, said: str) -> Reply:
        d = self._call("turn", {"call_id": call_id, "said": said})
        return Reply(d.get("say") or "", bool(d.get("over")), call_back=d.get("call_back") is not False)

    def end(self, call_id: str) -> bool:
        """The goodbye was heard: the CRM hangs up. True if instead it transferred the call to the desk (the caller was
        told they are being connected)."""
        return bool(self._call("end", {"call_id": call_id}).get("transferred"))

    def _must_arrive(self, step: str, payload: dict) -> dict | None:
        """``_call`` for a request that must not be lost: if the CRM cannot take it now, it goes in the outbox (when
        there is one) and None is returned. A request the CRM refuses for good is not kept."""
        try:
            return self._call(step, payload)
        except Exception as e:
            if self.outbox is None or refused(e):
                raise
            self.outbox.put(step, payload)
            log.warning("call %s: the CRM did not take %s (%s): kept in the outbox, sent again until it does",
                        payload.get("call_id"), step, type(e).__name__)
            return None

    def last_words(self, call_id: str, said: str) -> bool | None:
        """What the caller said that had not been passed on when the stream closed. Nothing is said back; the CRM keeps
        it, and opts the caller out if they asked to stop. True if it did; None if it is in the outbox for later."""
        d = self._must_arrive("last", {"call_id": call_id, "said": said[:500]})
        return None if d is None else bool(d.get("opted_out"))

    def trouble(self, call_id: str, what: str) -> bool | None:
        """The call broke on our side and the caller was told someone will call back (unless the CRM had said nobody can,
        TROUBLE_NO_CALL_BACK): the CRM books that call-back.
        True if it booked one (none is needed when one is already arranged or the caller asked not to be called);
        None if the request is in the outbox for later."""
        d = self._must_arrive("trouble", {"call_id": call_id, "what": what[:200]})
        return None if d is None else bool(d.get("callback_booked"))

    def recording(self, call_id: str, ogg: bytes) -> bool:
        """The call's recording, once it is over. True if the CRM kept it (it keeps only calls it said to record)."""
        return bool(self._call("recording", {"call_id": call_id, "audio": base64.b64encode(ogg).decode()},
                               timeout=120).get("kept"))


# ---------------------------------------------------------------------------------------------- one call

TROUBLE = "Sorry, I am having trouble on this line. A member of our team will call you back. Goodbye."
# For someone the team cannot call back (the CRM's last reply said so): no promise that would not be kept.
TROUBLE_NO_CALL_BACK = "Sorry, I am having trouble on this line. Please call us again later. Goodbye."
ONE_MOMENT = "One moment, please."      # said once when the CRM's reply is slow (Call.reply_to)
_LAST_WORDS: set[asyncio.Task] = set()     # callers' last words still being heard or sent (see Call.stop)
TRANSFER_WAIT_S = 10.0      # how long Plivo gets to move a transferred call before we close its stream ourselves


async def await_transfer(receive: Callable[[], Awaitable[object]], close: Callable[[], Awaitable[None]],
                         wait: float = TRANSFER_WAIT_S) -> None:
    """After the CRM transferred the call (Call.transferred): Plivo moves it to the desk and then closes this stream
    itself. Closing the stream first could make Plivo carry on with what follows it in the call's instructions, a
    hang-up, and cut the caller off. So the stream is read (and ignored) until Plivo closes it, which ``receive`` raises
    on, and closed here only if Plivo has not done so after ``wait`` seconds."""
    async def drain() -> None:
        while True:
            await receive()
    try:
        await asyncio.wait_for(drain(), wait)
    except TimeoutError:
        await close()


class Call:
    """One Plivo stream. ``send`` sends a JSON message to Plivo; ``stt`` takes 16 kHz float32 audio and returns
    text, and once the CRM has given the called contact's name, takes that name as a second argument (the hint that
    makes Whisper spell it as registered); ``tts`` takes text and returns (int16 samples, sample rate). All three
    are passed in, so the call logic is tested with fakes.

    When a turn looks cut off by a pause (``unfinished``), the call waits up to HOLD_S seconds more: if the caller
    carries on, both parts are heard again as one, so the CRM gets the whole sentence once. Finished sentences are
    not delayed."""

    HOLD_S = 1.5                # extra wait after an unfinished-looking turn
    ONE_MOMENT_AFTER_S = 2.5    # a reply slower than this is preceded by ONE_MOMENT (0: never); engine ONE_MOMENT_AFTER_S
    MOST_JOINS = 2              # a turn takes in at most this many later parts

    def __init__(self, call_id: str, send: Callable[[dict], Awaitable[None]], crm: Crm,
                 stt: Callable[[np.ndarray], str], tts: Callable[[str], tuple[np.ndarray, int]],
                 close: Callable[[], Awaitable[None]], call_back: bool = True) -> None:
        self.call_id, self.send, self.crm, self.stt, self.tts, self.close = call_id, send, crm, stt, tts, close
        self.stream_id = ""
        self.ears = Utterances()
        self.jobs: asyncio.Queue = asyncio.Queue()
        self.worker: asyncio.Task | None = None
        self.speaking = ""          # the checkpoint of the audio being played, "" when quiet
        self.marks = 0
        self.ending = ""            # the goodbye's checkpoint once the agent has finished
        self.hint = ""              # the called contact's name, from the CRM's opening reply
        # Whether the team can call this person back, from the CRM's latest reply. Before that, from the stream URL
        # (the CRM adds call_back=no for a withheld number). If the CRM is down as the call opens, a call-back is
        # promised unless the URL said no: the outbox delivers the report later, and the CRM books it if it can.
        self.call_back = call_back
        self.reported = False       # a fault has been reported to the CRM (once per call)
        self.reports: set[asyncio.Task] = set()
        self.unsent: np.ndarray | None = None   # the turn being heard, until it is on its way to the CRM
        self.late: list[np.ndarray] = []        # what the caller said while the goodbye played
        self.done = False
        self.transferred = False    # the CRM sent the call to the desk: Plivo closes the stream (await_transfer)
        # Records from the start; kept and sent only if the CRM's opening reply says to record (send_recording).
        self.recorder: Recorder | None = Recorder()
        self.record = False

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
            pcm = ulaw_decode(base64.b64decode(media.get("payload") or ""))
            if self.recorder:
                self.recorder.hear(pcm)
            for utterance in self.ears.feed(pcm):
                if not self.ending:
                    await self.jobs.put(("turn", utterance))
                else:
                    self.late.append(utterance)     # said over the goodbye: no reply, but sent as last words
            if self.speaking and not self.ending and self.ears.talking():
                # The caller talks over the agent: stop speaking and listen.
                self.speaking = ""
                await self.send({"event": "clearAudio", "streamId": self.stream_id})
                if self.recorder:
                    self.recorder.cut()
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
                    self.call_back = reply.call_back
                    self.hint = reply.hint
                    self.record = reply.record
                    if not reply.record:
                        self.recorder = None    # not to be recorded: nothing kept from here on
                else:
                    self.unsent = audio
                    text = await self.hear(audio)
                    joins = 0
                    while text and unfinished(text) and joins < self.MOST_JOINS:
                        more = await self._more()
                        if more is None:
                            break
                        audio, joins = np.concatenate([audio, more]), joins + 1
                        self.unsent = audio
                        text = await self.hear(audio)
                    # From here the words are the CRM's: the request starts before anything can cancel this task.
                    self.unsent = None
                    if not text:
                        continue
                    reply = await self.reply_to(text)
                    self.call_back = reply.call_back
            except Exception as e:     # the CRM or a model failed: say so and end, rather than go silent
                log.warning("call %s: %s failed: %s", self.call_id, kind, e)
                self.report(f"{'opening' if kind == 'open' else 'turn'} failed: {type(e).__name__}")
                reply = Reply(TROUBLE if self.call_back else TROUBLE_NO_CALL_BACK, True)
                if kind == "open":
                    self.recorder = None        # the CRM never said to record it
            try:
                await self.speak(reply)
            except Exception as e:     # the voice failed: nothing can be said, so hang up rather than leave silence
                log.warning("call %s: speaking failed: %s", self.call_id, e)
                self.report(f"speaking failed: {type(e).__name__}")
                await self.hang_up()

    def report(self, what: str) -> None:
        """Tells the CRM the call broke on our side, so it books the call-back the caller is promised. It runs
        alongside the goodbye: a CRM that is down (each request waits up to 20 s) must not keep the caller waiting."""
        if self.reported:
            return
        self.reported = True

        async def send() -> None:
            try:
                booked = await asyncio.to_thread(self.crm.trouble, self.call_id, what)
                if booked is not None:
                    log.info("call %s: fault reported (%s), call-back booked: %s", self.call_id, what, booked)
            except Exception as e:
                log.warning("call %s: could not report the fault (%s): %s", self.call_id, what, e)
        task = asyncio.create_task(send())
        self.reports.add(task)
        task.add_done_callback(self.reports.discard)

    async def hear(self, audio: np.ndarray) -> str:
        pcm = resample(audio, RATE, STT_RATE) / 32768.0
        hint = (self.hint,) if self.hint else ()
        return (await asyncio.to_thread(self.stt, pcm, *hint)).strip()

    async def _more(self) -> np.ndarray | None:
        """After an unfinished-looking turn: the caller's next utterance if it starts within HOLD_S seconds (it is
        then waited for until it ends), else None."""
        loop = asyncio.get_running_loop()
        end = loop.time() + self.HOLD_S
        while not self.done:
            left = end - loop.time()
            if left <= 0:
                if not self.ears.active:
                    return None
                left = 0.1                          # the caller is speaking again: wait for the end of it
            try:
                kind, audio = await asyncio.wait_for(self.jobs.get(), left)
            except asyncio.TimeoutError:
                continue
            if kind == "turn":
                return audio
        return None

    async def _play(self, text: str, mark: str) -> int:
        """Sends ``text`` in the voice, then a checkpoint named ``mark``; returns how many samples were sent."""
        pcm, rate = await asyncio.to_thread(self.tts, text)
        audio = ulaw_encode(np.clip(resample(pcm.astype(np.float32), rate, RATE), -32768, 32767))
        for i in range(0, len(audio), 4000):           # half a second per message
            await self.send({"event": "playAudio", "media": {"contentType": "audio/x-mulaw", "sampleRate": RATE,
                                                             "payload": base64.b64encode(audio[i:i + 4000]).decode()}})
        if self.recorder:
            self.recorder.say(ulaw_decode(audio))       # exactly what the caller is sent
        self.speaking = mark
        await self.send({"event": "checkpoint", "streamId": self.stream_id, "name": mark})
        return len(audio)

    async def reply_to(self, text: str) -> Reply:
        """The CRM's reply to what the caller said. One slower than ONE_MOMENT_AFTER_S (the brain using a tool, a busy
        CRM) is preceded by ONE_MOMENT, once, rather than silence. The request is on its way before anything is
        awaited, so cancelling this never stops the CRM getting the caller's words."""
        pending = asyncio.get_running_loop().run_in_executor(None, self.crm.turn, self.call_id, text)
        if self.ONE_MOMENT_AFTER_S > 0:
            done, _ = await asyncio.wait({pending}, timeout=self.ONE_MOMENT_AFTER_S)
            if not done and not self.done and not self.ears.talking():     # never over the caller
                self.marks += 1
                try:
                    await self._play(ONE_MOMENT, f"wait-{self.marks}")
                except Exception as e:      # the voice failed: the reply, or the fault, still follows
                    log.warning("call %s: could not say one moment: %s", self.call_id, e)
        return await pending

    async def speak(self, reply: Reply) -> None:
        self.marks += 1
        mark = f"{'end' if reply.over else 'say'}-{self.marks}"
        sent = 0
        if reply.say:
            sent = await self._play(reply.say, mark)
        if reply.over:
            self.ending = mark
            if not reply.say:
                await self.hang_up()
            else:
                # In case Plivo never confirms the goodbye was played: hang up after it surely has.
                asyncio.get_running_loop().call_later(sent / RATE + 5,
                                                      lambda: asyncio.ensure_future(self.hang_up()))

    async def hang_up(self) -> None:
        if self.done:
            return
        self.done = True
        try:
            self.transferred = bool(await asyncio.to_thread(self.crm.end, self.call_id))
        except Exception as e:
            log.warning("call %s: hang-up failed: %s", self.call_id, e)
        if not self.transferred:
            await self.close()

    def stop(self) -> asyncio.Task | None:
        """The stream closed (the caller hung up, or the call was transferred). What the caller said that the CRM has
        not had (the turn being heard, turns waiting their turn, what they said over the goodbye, and speech that the
        700 ms of quiet never ended) is heard and sent as their last words: "Stop calling me" said just before hanging
        up must reach the CRM. That runs as its own task, kept until it is done, so it goes on even if whoever closed
        the stream is cancelled; returned so it can be awaited (None when there is nothing to send)."""
        self.done = True
        if self.worker:
            self.worker.cancel()
        parts = [] if self.unsent is None else [self.unsent]
        self.unsent = None
        while not self.jobs.empty():
            kind, audio = self.jobs.get_nowait()
            if kind == "turn":
                parts.append(audio)
        parts += self.late
        self.late = []
        tail = self.ears.flush()
        if tail is not None:
            parts.append(tail)
        if not parts:
            return None
        task = asyncio.get_running_loop().create_task(self._send_last_words(np.concatenate(parts)))
        _LAST_WORDS.add(task)
        task.add_done_callback(_LAST_WORDS.discard)
        return task

    def send_recording(self) -> asyncio.Task | None:
        """Once the call is over (after stop): sends the recording to the CRM if it said to record this call. Runs as
        its own task, kept until it is done (like the last words); returned so it can be awaited (None when there is
        nothing to send)."""
        recorder, self.recorder = self.recorder, None
        if not self.record or recorder is None or not recorder.heard:
            return None
        task = asyncio.get_running_loop().create_task(self._send_recording(recorder))
        _LAST_WORDS.add(task)
        task.add_done_callback(_LAST_WORDS.discard)
        return task

    async def _send_recording(self, recorder: Recorder) -> None:
        try:
            ogg = await asyncio.to_thread(recorder.ogg)
            if ogg:
                kept = await asyncio.to_thread(self.crm.recording, self.call_id, ogg)
                log.info("call %s: recording sent (%d kB), kept: %s", self.call_id, len(ogg) // 1024, kept)
        except Exception as e:
            log.warning("call %s: could not send the recording: %s", self.call_id, e)

    async def _send_last_words(self, audio: np.ndarray) -> None:
        try:
            text = await self.hear(audio)
            if text:
                opted_out = await asyncio.to_thread(self.crm.last_words, self.call_id, text)
                if opted_out is not None:
                    log.info("call %s: the caller's last words sent, opted out: %s", self.call_id, opted_out)
        except Exception as e:
            log.warning("call %s: could not send the caller's last words: %s", self.call_id, e)
