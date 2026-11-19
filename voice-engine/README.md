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

Check it: <http://127.0.0.1:8090/health>. As it starts, the engine loads both models and runs each once in the
background (about 4 s on this laptop), so the first caller does not wait for them: `/health` then says
`"warmed": true`, or gives the reason in `warm_up_error` (the models then load on first use, as with `WARM_UP=off`).
Other voices: `python download_models.py en_GB-alba-medium`, then set
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
8 kHz mu-law. The caller can talk over the agent; after the goodbye has been played the CRM hangs up, or, when the
caller was told they are being connected, transfers the call to the desk (the engine then waits up to 10 seconds
for Plivo to close the stream, rather than closing it and cutting the caller off).

If hearing, the voice or a request to the CRM fails, the engine says "Sorry, I am having trouble on this line. A
member of our team will call you back. Goodbye." and ends the call, and tells the CRM (`POST
/api/voice/hooks/live/trouble`), which books that call-back (never for a caller who opted out or one who already has
a call-back) and notes `call_cut_off` in the transcript. Someone the team cannot call back (a withheld number, or
someone who opted out: the CRM's last `open` or `turn` reply said `"call_back": false`) hears "Please call us again
later" instead of the promise; the fault is still reported, and nothing is booked. The report runs alongside the goodbye, so a CRM that is down
does not keep the caller waiting. If the voice itself fails, nothing can be said, so the engine reports the fault and
hangs up instead of leaving the caller in silence.

When the stream closes (the caller hung up, or the call went to the desk), whatever the caller said that the CRM has
not had yet (the turn being heard, turns waiting, what they said over the goodbye, and speech still going because the
0.7 s of quiet never came) is heard then and sent as their **last words** (`POST /api/voice/hooks/live/last`,
`Call.stop`). Nothing is said back; the CRM adds them to the transcript and, if they asked to stop, opts them out at
once: "Stop calling me" followed by a click is never rung again. This runs as its own task, so it finishes even if the
handler is cancelled.

**The outbox: when the CRM cannot be reached.** These two requests carry a promise (the call-back) and maybe a
"stop". If the CRM does not take one (not reachable, timed out, 5xx, or 401 while the two `VOICE_HMAC_SECRET`s
differ), the engine writes it to a folder (`OUTBOX_DIR`, default `outbox/` here, not committed; `phone.Outbox`)
and sends it again at start-up and every 30 seconds, oldest first and freshly signed, until the CRM takes it. Then the
file is deleted, since it holds what the caller said. The CRM writes a repeat into the transcript only once.
- A request the CRM refuses for good (400, or 404: no such call, for example a test call or one erased since) is
  dropped.
- Nothing is kept longer than 7 days; one that old is deleted with an error in the log saying to read that call's
  transcript and act on it by hand.
- `/health` says how many are waiting (`"undelivered"`). **Do not switch the engine's machine off for good while that
  is above 0.**
- Checked live on 18 Nov 2026: a call while `CRM_URL` pointed at nothing, an engine restart pointed at the in-memory
  test CRM, and the call-back was booked at start-up.

Recordings are not kept in the outbox. A recording the CRM could not take is lost, which breaks no promise; it also
keeps copies of callers' voices off the engine's machine.

A turn ends after 0.7 s of quiet. A caller who stops to remember something ("My date of birth is ... fifteenth
August") pauses longer, so when what was heard looks cut off (it trails off with "...", a comma or a dash, or stops
on a word nobody ends on, such as "my", "the", "of", "um"; `phone.unfinished`), the call waits up to 1.5 s more
(`Call.HOLD_S`). If the caller carries on, both parts are heard again as one and the CRM gets the whole sentence
once; if not, the turn goes on as it was. Finished sentences are not delayed. This relies on the ear's punctuation:
`small.en` writes "is..." where the caller paused, `base.en` often writes a full stop, so with base.en most such
pauses still make two turns.

The engine needs two more settings: `VOICE_HMAC_SECRET` (the CRM's, it checks each call's stream token and signs the
CRM requests; blank refuses every call) and `CRM_URL`. `OUTBOX_DIR` is optional (above).

**Name hints.** When the call is to (or from) a contact the CRM knows, its `open` reply carries `"hint"`, the contact's
registered name (unknown callers get none). The engine gives that name to Whisper on every turn of the call
(faster-whisper `hotwords`), so it writes "Lakshmi Narayanan" instead of "Laxmina Rainan". `NAME_HINTS` decides
when: `auto` (default) uses it with `small.en` and larger and **not** with `tiny`/`base`, because in the ear check
`base.en` sometimes wrote the expected name over the one actually said (told to expect Rajesh, it wrote "Rajesh Kumar"
for a speaker who said Ramesh Kumar: 2 of 5 names); `on` or `off` force it. The name is only a spelling help: the CRM's identity
check still needs the identity answer.

**Never put the CRM or the engine on the internet directly.** Point the one public tunnel at the gateway instead
(`uvicorn gateway:app --host 127.0.0.1 --port 8099`, settings `CRM_URL` and `ENGINE_URL`): it passes on only Plivo's
three callbacks (`/api/voice/hooks/plivo/answer|hangup|desk`, unchanged, so Plivo's signature is still checked by the
CRM) and the `/call` stream, and answers 404 to everything else, including the CRM's sign-in and the engine's
`/stt` and `/tts`. Then `PLIVO_PUBLIC_URL=https://<tunnel>` and `PLIVO_ENGINE_URL=wss://<tunnel>/call`.
Setting up the CRM and Plivo: `docs/voice-agent.md`, "Plivo as the phone line".

Tests, with no phone line or models: `.venv\Scripts\python -m unittest test_phone test_gateway test_ear_check test_rehearse test_redact`.

The engine and the gateway write each call's stream address into their logs with `token=[hidden]`: the token lets
a stream into a call, so it never sits in a log file (`redact.py`). The call id stays, to find a call in the log.

## Rehearsing a whole call with no phone line (`rehearse.py`)

`rehearse.py` plays Plivo: it rings the CRM through the gateway with correctly signed callbacks (or, with
`--outbound`, waits for the CRM to dial and answers on that call's own URLs as the person called), opens the call
stream, speaks the caller's lines into it as 8 kHz phone audio in real time (text in the engine's voice, or WAV
recordings), plays the agent's replies for as long as they last, and stands in for Plivo's API, which the CRM calls
to transfer or hang up. It prints how long each reply took and how the call ended, and the call appears on the CRM's
`/voice/calls` page with what the engine heard.

Use it with a CRM that real calls never go through (the in-memory test CRM is best), started with a **made-up**
token, never the real account's:

| CRM setting | Value for a rehearsal |
|---|---|
| `VOICE_PLATFORM` | `plivo` |
| `PLIVO_AUTH_ID`, `PLIVO_FROM_NUMBER` | anything, e.g. `MAREHEARSAL01`, `+918000000001` |
| `PLIVO_AUTH_TOKEN` | a made-up value, also given to the script |
| `PLIVO_PUBLIC_URL` / `PLIVO_ENGINE_URL` | `http://127.0.0.1:8099` / `ws://127.0.0.1:8099/call` (the gateway) |
| `PLIVO_API_BASE` | `http://127.0.0.1:8098/v1` (the script's pretend Plivo API) |
| `VOICE_DESK_NUMBER` | any number, to see a transfer |
| `--app.alerts.scheduling-enabled=true` | on the test CRM's command line, for outbound calls: its test settings switch off the scheduled jobs, and the campaign dispatcher is one of them |

Start the engine and the gateway as for a real call, approve the "Incoming calls" script, add the caller as a
contact, then:

```
python rehearse.py --auth-token <the made-up token> --from +919876500001 "Hello, I have a question." "Can I speak to a real person please?"
```

An outbound call, as the first real call will be: start `rehearse.py --outbound` first (it waits up to 5 minutes),
then a one-contact campaign in the CRM (the contact with AI-call consent, the script approved). The dispatcher picks
it up within about 30 seconds. `--no-answer` rehearses a call nobody picks up.

When the CRM answers without the agent, the script says what the caller would hear and reports the hang-up, as Plivo
would. That happens when the speech engine is down as the call connects: a spoken line, or a transfer to the desk
while it is open. To rehearse it, stop the engine and give a WAV file as the caller's line, since text needs the
engine's voice:

```
python rehearse.py --auth-token <the made-up token> --from +919876500001 caller.wav
```

`--from anonymous` rehearses a caller who withheld their number.

```
python rehearse.py --auth-token <the made-up token> --outbound "Yes, speaking." "Priya Shankar, and my customer ref is 4471." "Please call me back at 4 pm."
```

## Checking the ears on Indian English (`ear_check.py`)

Before paying for a phone line: how well does Whisper hear our callers once their voice has been through the phone?
`ear_check.py` hears each recording three ways: **plain** (straight to Whisper), **line** (Plivo's 8 kHz mu-law
line and the call's own end-of-speech cutting, exactly as `phone.py` hears a call) and **mobile** (the same after a
mobile phone's AMR-NB codec). It reports words wrong, the key words the CRM must hear exactly (names, IDs, times) and
Whisper's time per sentence, for `base.en` and `small.en`.

Recording, for each person (about 5 minutes):

1. Read the 24 sentences in `ear_sentences.txt` aloud as written, in your normal phone voice, holding the phone as
   on a call, in a normal room (not a studio, not a noisy road).
2. Either one file per sentence named `01.m4a`, `02.m4a` ... `24.m4a`, or **one file `all.m4a`** reading them all
   in order with a pause of about 2 seconds between sentences (easier; the tool cuts it at the pauses; if a pause
   inside a sentence makes extra pieces, it joins neighbouring pieces to match the sentences and says which; too few
   pieces are refused). Any format a phone recorder makes is fine.
3. Put the files in `voice-engine/recordings/<name>/` (for example `recordings/siva/all.m4a`). Recordings are
   people's voices: the folder is in `.gitignore` and must never be committed or shared.

Then:

```
.venv\Scripts\python ear_check.py
```

The report is `recordings/ear-report.md` (every misheard sentence, said against heard) and `ear-report.json`.
`python ear_check.py --piper recordings/piper-lessac` makes a baseline "speaker" from Piper's own voice.
`--hints none right decoy` also hears each sentence with Whisper told the name the CRM expects (right) or a similar other
person's name (decoy), and counts how often the decoy ends up in the transcript.
What it cannot copy: the caller's own phone, VoLTE's wider-band audio and whatever the networks and Plivo do
between them; the first real call is still the real test.
