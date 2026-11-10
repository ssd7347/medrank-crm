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

Set `ANTHROPIC_API_KEY=...` in `backend/.env` and restart the backend. The default model is `claude-sonnet-5-5`
(`ANTHROPIC_MODEL=claude-haiku-4-5` for the fastest replies on the simplest calls). If Claude declines a
conversation, Anthropic retries it on a recommended model where it can; otherwise the call goes to a person.

The CRM uses Anthropic's official Java SDK with **prompt caching**: the tools and script, which every turn re-sends,
are cached, and cache reads cost a tenth of normal input. Each model has a minimum size before anything is cached:
Claude Haiku 4.5 needs 4,096 tokens, so the first turns of a short call are not cached and later ones are; Claude
Sonnet 5.5 caches from 512 tokens, so every turn after the first is. With `logging.level.com.aicallcrm.crm.voice=DEBUG`
the backend logs each turn's `cache_read` and `cache_write` token counts.

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

## Real phone calls

Plivo streams the call's audio both ways over a WebSocket to this engine's `/call` (`phone.py`): 8 kHz mu-law in,
Whisper to text, the CRM's brain decides the reply (`POST /api/voice/hooks/live/{open,turn,end}`), Piper back to
8 kHz mu-law. The caller can talk over the agent; after the goodbye has been played the CRM hangs up.

The engine needs two more settings: `VOICE_HMAC_SECRET` (the CRM's, it checks each call's stream token and signs the
CRM requests; blank refuses every call) and `CRM_URL`.

**Never put the CRM or the engine on the internet directly.** Point the one public tunnel at the gateway instead
(`uvicorn gateway:app --host 127.0.0.1 --port 8099`, settings `CRM_URL` and `ENGINE_URL`): it passes on only Plivo's
three callbacks (`/api/voice/hooks/plivo/answer|hangup|desk`, unchanged, so Plivo's signature is still checked by the
CRM) and the `/call` stream, and answers 404 to everything else, including the CRM's sign-in and the engine's
`/stt` and `/tts`. Then `PLIVO_PUBLIC_URL=https://<tunnel>` and `PLIVO_ENGINE_URL=wss://<tunnel>/call`.
Setting up the CRM and Plivo: `docs/voice-agent.md`, "Plivo as the phone line".

Tests, with no phone line or models: `.venv\Scripts\python -m unittest test_phone test_gateway`.
