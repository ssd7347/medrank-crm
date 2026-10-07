# Our own AI voice

The AI agent is built from three parts we run ourselves, plus a phone line we rent:

| Part | What it does | What we use | Where it runs |
|---|---|---|---|
| Ears | Caller's speech → text | **Whisper** (open model, via faster-whisper) | `voice-engine/` (this folder) |
| Brain | Decides what to say, uses the CRM tools | **Our own open model** (Qwen / Llama via Ollama) *or* **Claude** (API) | The CRM backend talks to it |
| Voice | Text → speech | **Piper** (open text-to-speech voices) | `voice-engine/` |
| Phone line | Rings real phones | A licensed provider (Exotel, Plivo, Tata) | Later |

The brain is switchable per test call on the **AI voice agent → Test console** page:

* **Simulated**: rules only, free, instant (always available).
* **Our own model**: an open model on this machine through [Ollama](https://ollama.com). Free. On a laptop
  without an NVIDIA graphics card a reply takes a few seconds; on a GPU server it is fast.
* **Claude**: Anthropic's API. Fast and the most capable; paid per use (a few paise per call).

## 1. The speech engine (ears and voice)

Needs Python 3.11 or newer.

```bash
cd voice-engine
python -m venv .venv
.venv\Scripts\activate            # Windows  (macOS/Linux: source .venv/bin/activate)
pip install -r requirements.txt
python download_models.py         # Piper voice (~60 MB) + Whisper base.en (~140 MB), once
uvicorn engine:app --host 127.0.0.1 --port 8090
```

Check it: <http://127.0.0.1:8090/health>. Other voices: `python download_models.py en_GB-alba-medium`, then set
`PIPER_VOICE=voices/en_GB-alba-medium.onnx`. A clearer but slower ear: `STT_MODEL=small.en`.

## 2. Our own brain (optional)

1. Install Ollama from <https://ollama.com/download>.
2. `ollama pull qwen2.5:3b` (about 2 GB; a small model that can use tools). On a GPU server, larger models
   such as `qwen2.5:14b` or `llama3.1:8b` answer better.
3. In `backend/.env`: `LOCAL_LLM_MODEL=qwen2.5:3b` (and `AI_BRAIN=LOCAL` to make it the default).

## 3. Claude as the brain (optional)

Set `ANTHROPIC_API_KEY=...` in `backend/.env` and restart the backend.

## How a turn works

1. The test console listens through the microphone and notices when you stop speaking.
2. The recording goes to the CRM backend (`/api/voice/speech/stt`, signed-in admins only), which passes it to
   this engine; Whisper returns the text.
3. The brain answers, calling CRM tools (verification, details, FAQ, call-backs, opt-out...) through the same
   rules, audit trail and hand-overs as any platform.
4. The answer goes to `/api/voice/speech/tts`; Piper returns the audio, which the browser plays. Then it listens again.

## Our own brand voice (later)

Piper voices can be trained on our own recordings: about 1–2 hours of a voice artist reading prepared sentences
in a quiet room, then a few hours of training on a GPU (cloud GPUs can be rented by the hour). The result is a
`.onnx` file used exactly like the downloaded voices.

## Real phone calls (later)

A phone line provider streams the call audio to an engine like this one over a WebSocket. The engine will gain a
`/call` stream endpoint doing the same loop (Whisper → brain → Piper) on the phone audio.
