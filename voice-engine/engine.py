"""
Our own speech engine for the AI Call CRM: speech-to-text and text-to-speech with open models, on our own
machine, with no per-minute fees.

  * Ears:  faster-whisper (OpenAI's open Whisper model, int8 on the CPU). Default model: base.en.
  * Voice: Piper (fast open text-to-speech that runs on a CPU). Default voice: en_US-lessac-medium.

The CRM backend calls it; browsers never do. Endpoints:
  GET  /health -> {"ok": true, "stt_model": ..., "voice": ..., "warmed": true|false, "warm_up_error": null|"...",
                   "undelivered": 0}     (requests waiting in the outbox for the CRM; see OUTBOX_DIR)
  POST /stt    body: audio bytes (WebM/Opus, WAV, MP3...)        -> {"text": "...", "ms": 412}
  POST /tts    body: {"text": "..."}                               -> audio/wav
  WS   /call?call_id=...&token=...                                  a real phone call streamed by Plivo (phone.py)

Settings (environment variables):
  STT_MODEL      Whisper model name or folder (base.en, small.en, ...). Default base.en.
  PIPER_VOICE    Path to a Piper .onnx voice. Default voices/en_US-lessac-medium.onnx.
  SPEECH_TOKEN   If set, every request must carry it in the X-Speech-Token header.
  CPU_THREADS    Threads for Whisper. Default: all cores.
  CRM_URL           The CRM backend, for real calls. Default http://127.0.0.1:8082.
  VOICE_HMAC_SECRET The CRM's VOICE_HMAC_SECRET: checks each call's stream token and signs the CRM requests.
                    Blank: /call refuses every connection.
  WARM_UP        on (default) / off. On: both models are loaded and run once in the background as the engine starts,
                 so the first caller does not wait several seconds in silence. /health says when that is done.
  NAME_HINTS     on / off / auto (default). On a call to a known contact, tell Whisper the contact's name so it spells
                 it as registered. auto: on for small.en and larger, off for tiny and base, which in the ear check
                 (11 Nov 2026) sometimes wrote the expected name over the one said (told Rajesh, heard Ramesh: "Rajesh").
  OUTBOX_DIR     Folder for the call-backs callers were promised and their last words while the CRM cannot be reached
                 (phone.Outbox). Sent again every 30 s and at start-up, and deleted once the CRM has them. Default
                 outbox/ next to this file. Before switching this machine off for good, /health must say
                 "undelivered": 0.

Run:  uvicorn engine:app --host 127.0.0.1 --port 8090
"""

import asyncio
import hmac
import io
import json
import logging
import os
import tempfile
import threading
import time
import wave
from contextlib import asynccontextmanager
from pathlib import Path

import numpy as np
from fastapi import Depends, FastAPI, Header, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import Response
from pydantic import BaseModel, Field

import phone
import redact

redact.install()        # call stream tokens never reach the log (redact.py)

HERE = Path(__file__).resolve().parent
STT_MODEL = os.environ.get("STT_MODEL", "base.en")
PIPER_VOICE = os.environ.get("PIPER_VOICE", str(HERE / "voices" / "en_US-lessac-medium.onnx"))
SPEECH_TOKEN = os.environ.get("SPEECH_TOKEN", "")
CPU_THREADS = int(os.environ.get("CPU_THREADS", "0"))
CRM_URL = os.environ.get("CRM_URL", "http://127.0.0.1:8082")
VOICE_HMAC_SECRET = os.environ.get("VOICE_HMAC_SECRET", "")
NAME_HINTS = os.environ.get("NAME_HINTS", "auto")
WARM_UP = os.environ.get("WARM_UP", "on")
OUTBOX = phone.Outbox(os.environ.get("OUTBOX_DIR", str(HERE / "outbox")))
MAX_AUDIO_BYTES = 5 * 1024 * 1024

log = logging.getLogger("uvicorn.error")     # uvicorn's own log, so start-up notes show beside its "Started server"


def crm() -> phone.Crm:
    return phone.Crm(CRM_URL, VOICE_HMAC_SECRET, outbox=OUTBOX)


async def resend_outbox(every: float = phone.Outbox.RETRY_S) -> None:
    """Sends what waits in the outbox now (it may be left from before a restart), then every ``every`` seconds."""
    while True:
        try:
            if OUTBOX.waiting():
                await asyncio.to_thread(OUTBOX.deliver_all, crm())
            if OUTBOX.waiting():
                log.warning("%d request(s) for the CRM still waiting in the outbox (%s)", OUTBOX.waiting(), OUTBOX.folder)
        except Exception as e:      # never let the retries stop for good
            log.warning("Outbox retry failed: %s", e)
        await asyncio.sleep(every)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    if warm_up_on(WARM_UP):
        # In the background: the engine answers /health at once, and a call that comes in meanwhile simply waits for
        # the model being loaded (the locks below), as it would have without the warm-up.
        threading.Thread(target=warm_up, name="warm-up", daemon=True).start()
    retries = asyncio.create_task(resend_outbox())
    yield
    retries.cancel()


app = FastAPI(title="AI Call CRM speech engine", docs_url=None, redoc_url=None, lifespan=lifespan)

_whisper = None
_voice = None
_whisper_lock = threading.Lock()    # one load, even when the warm-up and a first call ask at the same moment
_voice_lock = threading.Lock()
_warm = {"warmed": False, "error": None}


def whisper():
    """Loaded by the warm-up at start-up, or else on first use (that request then takes a few seconds longer)."""
    global _whisper
    if _whisper is None:
        with _whisper_lock:
            if _whisper is None:
                from faster_whisper import WhisperModel

                _whisper = WhisperModel(STT_MODEL, device="cpu", compute_type="int8", cpu_threads=CPU_THREADS)
    return _whisper


def voice():
    global _voice
    if _voice is None:
        with _voice_lock:
            if _voice is None:
                from piper import PiperVoice

                if not Path(PIPER_VOICE).exists():
                    raise HTTPException(503, f"Piper voice not found at {PIPER_VOICE}. Run download_models.py.")
                _voice = PiperVoice.load(PIPER_VOICE)
    return _voice


def warm_up_on(setting: str) -> bool:
    """WARM_UP: only off / 0 / false / no turn it off."""
    return setting.strip().lower() not in ("off", "0", "false", "no")


def warm_up() -> None:
    """Loads both models and runs each once (the first run of a loaded model is slow too), so the first call after
    start-up answers as quickly as the rest. A failure is logged and shown on /health, never fatal: the models then
    load on first use as before."""
    t0 = time.perf_counter()
    try:
        stt_pcm(np.zeros(16000, dtype=np.float32))      # one second of silence
        speak_wav("Hello.")
    except Exception as e:      # a missing model or voice file: say so, keep serving
        _warm["error"] = f"{type(e).__name__}: {getattr(e, 'detail', None) or e}"
        log.warning("Warm-up failed, the models will load on first use: %s", _warm["error"])
        return
    _warm["warmed"] = True
    log.info("Speech models warmed up in %.1f s", time.perf_counter() - t0)


def check_token(x_speech_token: str | None = Header(default=None)):
    if SPEECH_TOKEN and x_speech_token != SPEECH_TOKEN:
        raise HTTPException(401, "Missing or wrong speech token")


@app.get("/health")
def health():
    return {"ok": True, "stt_model": STT_MODEL, "voice": Path(PIPER_VOICE).stem, "warmed": _warm["warmed"],
            "warm_up_error": _warm["error"], "undelivered": OUTBOX.waiting()}


@app.post("/stt", dependencies=[Depends(check_token)])
async def stt(request: Request):
    audio = await request.body()
    if not audio:
        raise HTTPException(400, "No audio")
    if len(audio) > MAX_AUDIO_BYTES:
        raise HTTPException(413, "Audio too long")
    t0 = time.perf_counter()
    # Whisper decodes any format FFmpeg knows (via PyAV); a temporary file keeps that simple.
    suffix = ".webm" if b"webm" in (request.headers.get("content-type") or "").encode() else ".audio"
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as f:
        f.write(audio)
        path = f.name
    try:
        segments, _info = whisper().transcribe(
            path,
            language="en",
            beam_size=1,
            vad_filter=True,  # drop silence and noise before transcribing
            condition_on_previous_text=False,
        )
        text = " ".join(s.text.strip() for s in segments).strip()
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass
    return {"text": text, "ms": int((time.perf_counter() - t0) * 1000)}


class SpeakBody(BaseModel):
    text: str = Field(min_length=1, max_length=800)


def speak_wav(text: str) -> bytes:
    v = voice()
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wav:
        # piper-tts 1.3 has synthesize_wav; 1.2 had synthesize(text, wav_file).
        if hasattr(v, "synthesize_wav"):
            v.synthesize_wav(text, wav)
        else:
            v.synthesize(text, wav)
    return buf.getvalue()


@app.post("/tts", dependencies=[Depends(check_token)])
def tts(body: SpeakBody):
    return Response(content=speak_wav(body.text), media_type="audio/wav")


# ---------------------------------------------------------------------------------------------- real phone calls


def stt_pcm(audio: np.ndarray, model=None, hint: str = "") -> str:
    """16 kHz mono float32 -> text (the phone path; the console's /stt takes encoded files). ``model``: another
    loaded Whisper model, for ear_check.py's comparisons; a real call uses STT_MODEL. ``hint``: words to expect, such
    as the name of the person called (Whisper's hotwords), so it spells that name as registered."""
    segments, _info = (model or whisper()).transcribe(audio, language="en", beam_size=1, vad_filter=False,
                                                      condition_on_previous_text=False, hotwords=hint or None)
    return " ".join(s.text.strip() for s in segments).strip()


def hints_on(model: str, setting: str) -> bool:
    """Whether a call's ear is told the contact's name (NAME_HINTS). A setting other than on/off counts as auto."""
    setting = setting.strip().lower()
    if setting in ("on", "1", "true", "yes"):
        return True
    if setting in ("off", "0", "false", "no"):
        return False
    name = Path(model.rstrip("/\\")).name.lower().removeprefix("faster-whisper-")   # a model name or its folder
    return not name.startswith(("tiny", "base"))


def tts_pcm(text: str) -> tuple[np.ndarray, int]:
    with wave.open(io.BytesIO(speak_wav(text[:800])), "rb") as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16), w.getframerate()


@app.websocket("/call")
async def call(ws: WebSocket, call_id: str = "", token: str = ""):
    # The CRM gave Plivo this URL with a token made from the call id and the shared secret; nobody else has it.
    expected = phone.stream_token(VOICE_HMAC_SECRET, call_id).encode() if VOICE_HMAC_SECRET and call_id else b""
    if not expected or not hmac.compare_digest(token.encode(), expected):
        await ws.close(code=1008)
        return
    await ws.accept()

    async def send(msg: dict) -> None:
        await ws.send_text(json.dumps(msg))

    async def close() -> None:
        await ws.close()

    def hear(audio: np.ndarray, hint: str = "") -> str:
        return stt_pcm(audio, hint=hint) if hint and hints_on(STT_MODEL, NAME_HINTS) else stt_pcm(audio)

    session = phone.Call(call_id, send, crm(), hear, tts_pcm, close)
    try:
        while not session.done:
            await session.handle(json.loads(await ws.receive_text()))
        if session.transferred:
            await phone.await_transfer(ws.receive_text, close)     # Plivo closes the stream once the call is at the desk
    except (WebSocketDisconnect, RuntimeError):
        pass                    # the caller hung up, or the call was transferred to the desk
    finally:
        last = session.stop()   # hears and sends whatever the caller said that the CRM has not had yet
        recording = session.send_recording()    # and the call's recording, if the CRM said to record it
        for task in (last, recording):
            if task is not None:
                await asyncio.shield(task)  # each goes on even if this handler is cancelled
