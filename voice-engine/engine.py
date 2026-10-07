"""
Our own speech engine for the AI Call CRM: speech-to-text and text-to-speech with open models, on our own
machine, with no per-minute fees.

  * Ears:  faster-whisper (OpenAI's open Whisper model, int8 on the CPU). Default model: base.en.
  * Voice: Piper (fast open text-to-speech that runs on a CPU). Default voice: en_US-lessac-medium.

The CRM backend calls it; browsers never do. Endpoints:
  GET  /health -> {"ok": true, "stt_model": ..., "voice": ...}
  POST /stt    body: audio bytes (WebM/Opus, WAV, MP3...)        -> {"text": "...", "ms": 412}
  POST /tts    body: {"text": "..."}                               -> audio/wav

Settings (environment variables):
  STT_MODEL      Whisper model name or folder (base.en, small.en, ...). Default base.en.
  PIPER_VOICE    Path to a Piper .onnx voice. Default voices/en_US-lessac-medium.onnx.
  SPEECH_TOKEN   If set, every request must carry it in the X-Speech-Token header.
  CPU_THREADS    Threads for Whisper. Default: all cores.

Run:  uvicorn engine:app --host 127.0.0.1 --port 8090
"""

import io
import os
import tempfile
import time
import wave
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import Response
from pydantic import BaseModel, Field

HERE = Path(__file__).resolve().parent
STT_MODEL = os.environ.get("STT_MODEL", "base.en")
PIPER_VOICE = os.environ.get("PIPER_VOICE", str(HERE / "voices" / "en_US-lessac-medium.onnx"))
SPEECH_TOKEN = os.environ.get("SPEECH_TOKEN", "")
CPU_THREADS = int(os.environ.get("CPU_THREADS", "0"))
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


@app.post("/tts", dependencies=[Depends(check_token)])
def tts(body: SpeakBody):
    v = voice()
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wav:
        # piper-tts 1.3 has synthesize_wav; 1.2 had synthesize(text, wav_file).
        if hasattr(v, "synthesize_wav"):
            v.synthesize_wav(body.text, wav)
        else:
            v.synthesize(body.text, wav)
    return Response(content=buf.getvalue(), media_type="audio/wav")
