"""
Our own speech engine for the AI Call CRM: speech-to-text and text-to-speech with open models, on our own
machine, with no per-minute fees.

  * Ears:  faster-whisper (OpenAI's open Whisper model, int8 on the CPU). Default model: base.en.
  * Voice: Piper (fast open text-to-speech that runs on a CPU). Default voice: en_US-lessac-medium.

The CRM backend calls it; browsers never do. Endpoints:
  GET  /health -> {"ok": true, "stt_model": ..., "voice": ...}
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

Run:  uvicorn engine:app --host 127.0.0.1 --port 8090
"""

import hmac
import io
import json
import os
import tempfile
import time
import wave
from pathlib import Path

import numpy as np
from fastapi import Depends, FastAPI, Header, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import Response
from pydantic import BaseModel, Field

import phone

HERE = Path(__file__).resolve().parent
STT_MODEL = os.environ.get("STT_MODEL", "base.en")
PIPER_VOICE = os.environ.get("PIPER_VOICE", str(HERE / "voices" / "en_US-lessac-medium.onnx"))
SPEECH_TOKEN = os.environ.get("SPEECH_TOKEN", "")
CPU_THREADS = int(os.environ.get("CPU_THREADS", "0"))
CRM_URL = os.environ.get("CRM_URL", "http://127.0.0.1:8082")
VOICE_HMAC_SECRET = os.environ.get("VOICE_HMAC_SECRET", "")
MAX_AUDIO_BYTES = 5 * 1024 * 1024

app = FastAPI(title="AI Call CRM speech engine", docs_url=None, redoc_url=None)

_whisper = None
_voice = None


def whisper():
    """Loaded on first use: the first request after start-up takes a few seconds longer."""
    global _whisper
    if _whisper is None:
        from faster_whisper import WhisperModel

        _whisper = WhisperModel(STT_MODEL, device="cpu", compute_type="int8", cpu_threads=CPU_THREADS)
    return _whisper


def voice():
    global _voice
    if _voice is None:
        from piper import PiperVoice

        if not Path(PIPER_VOICE).exists():
            raise HTTPException(503, f"Piper voice not found at {PIPER_VOICE}. Run download_models.py.")
        _voice = PiperVoice.load(PIPER_VOICE)
    return _voice


def check_token(x_speech_token: str | None = Header(default=None)):
    if SPEECH_TOKEN and x_speech_token != SPEECH_TOKEN:
        raise HTTPException(401, "Missing or wrong speech token")


@app.get("/health")
def health():
    return {"ok": True, "stt_model": STT_MODEL, "voice": Path(PIPER_VOICE).stem}


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


def stt_pcm(audio: np.ndarray, model=None) -> str:
    """16 kHz mono float32 -> text (the phone path; the console's /stt takes encoded files). ``model``: another
    loaded Whisper model, for ear_check.py's comparisons; a real call uses STT_MODEL."""
    segments, _info = (model or whisper()).transcribe(audio, language="en", beam_size=1, vad_filter=False,
                                                      condition_on_previous_text=False)
    return " ".join(s.text.strip() for s in segments).strip()


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

    session = phone.Call(call_id, send, phone.Crm(CRM_URL, VOICE_HMAC_SECRET), stt_pcm, tts_pcm, close)
    try:
        while not session.done:
            await session.handle(json.loads(await ws.receive_text()))
    except (WebSocketDisconnect, RuntimeError):
        pass                    # the caller hung up, or the call was transferred to the desk
    finally:
        session.stop()
