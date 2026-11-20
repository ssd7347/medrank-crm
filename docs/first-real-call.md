# The first real phone call: what is ready, what is missing, and the order

Written 10 November 2026. Goal: one call from the CRM to the developer's own phone, with our own model
(`ourvoice8b-r4`), Plivo as the line and our speech engine, then read its transcript. Nothing goes to customers
until that call and a pilot (20–50 consenting contacts, every transcript read) are done.

## What is ready

| Part | State |
|---|---|
| Brain: `ourvoice8b-r4` | Fair 54/59 (92%) on callers nobody had read (seed 23), 150/177 (85%) over three sets; zero private details before the check. Model file in the college Google Drive, `ourvoice-training-8b-r4`. |
| Speed of the brain | On a T4 GPU: about 1.0 s per later reply, first reply ≈ 2.4 s once the model is loaded. On the laptop: 6.5–21 s, **too slow for a phone call**. |
| Phone bridge | `PlivoClient`, `PlivoHooksController`, `LiveCallService`, `voice-engine/phone.py`. Tested with a fake Plivo and real Whisper + Piper on one machine; **never on a real line**. |
| CRM safety nets | Identity check, private fields, reason guard, opt-out, call-back and repeat nets, hand-over to a person. |

## What is missing, and whose it is

1. **Plivo account: the client's.** India data region (cannot be changed later), KYC, an Indian number. Ask Plivo
   what outbound calls to Indian mobiles need for this client (DLT registration / 160-series number). The
   developer enters the Auth ID, Auth Token and number in `backend/.env` themselves; they are never committed.
   Plivo's sign-up page refused the laptop's home network ("not available in your region") but opened on mobile data.
2. **A GPU for the brain: paid, so the client's decision.** A T4-class GPU, in India if possible (every extra
   100 ms of network delay is heard). For the first call, rented by the hour; for live use, monthly. Free Colab's terms
   do not allow using it to offer a web service, which a phone line's brain is; Kaggle's notebooks are likewise meant
   for interactive work (check its terms before relying on it).
3. **Done (10 Nov): only Plivo's doors are public.** `OTP_SHOW_ON_SCREEN=true` locally (SMS/WhatsApp codes are not
   connected), so the CRM itself must never be on a public address: anyone who knows a staff mobile number could sign
   in. The tunnel points at `voice-engine/gateway.py` instead, which passes on only Plivo's three callbacks and the
   `/call` stream and answers 404 to everything else (sign-in, the whole API, the engine's `/stt` and `/tts`). Checked
   against the real CRM and engine: a correctly signed Plivo callback is accepted through it, a wrongly signed one is
   refused, a wrong stream token is refused. Staff keep using the CRM on `localhost`. Before the CRM itself goes on a
   public address (hosting), real OTP delivery and `OTP_SHOW_ON_SCREEN=false`, `COOKIE_SECURE=true` are required.
   (17 Nov: SMS codes through MSG91 are built; they need the client's MSG91 account, `docs/new-customer-setup.md`.)
4. **Indian English on an 8 kHz phone line (free; being checked).** The ear is Whisper `base.en` and the voice is
   Piper's US `en_US-lessac-medium`. The tool is built: `voice-engine/ear_check.py` (README: "Checking the ears").
   **Baseline (11 Nov, Piper's own voice reading the 24 test sentences):** ordinary words and numbers are fine, about
   2–3% of words wrong, and the phone line (8 kHz mu-law, and a mobile's AMR codec) barely changes that. **Indian
   names are the weak spot for both ears:** Suresh became "surish", "Sirish" or "syringe", Irfan "Erfan", Lakshmi
   Narayanan "Laxminarayanan", Priya Venkatesh "pre-eventkadish". The CRM's identity check needs the name word for
   word, so such callers would have failed it. **Fixed the same day:** names are now matched the way the ear spells
   them (`NameMatch`, see `docs/voice-agent.md`); names the ear garbles completely ("syringe" for Suresh) still fail
   and use a try. Speed on the laptop's CPU, per sentence: `base.en` about 1.0 s, `small.en` about 3.0 s (too slow on
   this CPU). **First Indian voice (the developer's, 11 Nov evening), key words heard out of 37 on the phone line:
   `base.en` 27 (25 after a mobile codec), `small.en` 34.** base.en on the line also missed clinic, Sunday, fee,
   appointment and a digit; small.en missed only names and the sentence about the check-up fee. So the ear should be `small.en`, which is too
   slow on the laptop's CPU: **the speech engine should run on the GPU machine with the brain** (part of the client's
   GPU decision). More voices still to record before deciding. **Name hints (11 Nov, night):** told the contact's name
   (Whisper `hotwords`), small.en got every name right for both voices (36 and 37 of 37 key words); told a similar
   other person's name, it never turned a caller's own name into it (base.en did: "Rajesh" for Ramesh). **Wired into
   live calls (11 Nov, late):** the CRM's `open` reply carries the contact's name as `hint` and the engine gives it to
   Whisper on every turn; engine setting `NAME_HINTS=auto` uses it with small.en and up, not with base.en (README,
   "Name hints"). Checked end to end (test CRM, real engine with small.en, the developer's recording on the phone
   path): with the hint "My full name is Lakshmi Narayanan", without it "Laxmina Rainan".

## The layout for the first call

```
Developer's phone ── Plivo ── HTTPS + WSS ──> gateway (laptop, :8099) ──> CRM (:8082)  ── SSH tunnel ──> Ollama + ourvoice8b-r4
                                                                   └──> engine (:8090)          (GPU machine)
```

* CRM, engine and gateway stay on the laptop (the engine measured 0.88 s on the laptop's CPU). **One** HTTPS tunnel,
  to the gateway only (ngrok is installed; check that its free plan carries WebSockets). Never point a tunnel at
  :8082 or :8090.
* The brain runs on the GPU machine. **Ollama is never put on the internet**: the laptop reaches it through
  `ssh -N -L 11434:localhost:11434 <gpu-machine>`, so `LOCAL_LLM_URL=http://localhost:11434` stays unchanged.
* On the GPU machine: install Ollama, copy the `.gguf` from Drive, `ollama pull qwen3:4b-instruct`, then
  `python make_modelfile.py <gguf> --base qwen3:4b-instruct --name ourvoice8b-r4 --create` (the training kit's own
  script, exactly as on Colab), and send one warm-up message so the model is loaded before the call.

Expected reply time: 0.7 s end-of-speech wait + ~1.0 s ear (`base.en` on the laptop's CPU, measured 11 Nov for a
2–4 s sentence) + the voice + ~1.0 s brain + the network. **About 3 s**, above the ~2 s target; running the engine on
the GPU machine too would cut the ear's part. The first call measures it. When what the caller said looks cut off by
a pause ("My date of birth is..."), the engine waits up to 1.5 s more for the rest before answering (15 Nov;
`voice-engine/README.md`, "Real phone calls"); finished sentences are not delayed.

## Settings (`backend/.env`, never committed)

| Key | Value for the first call |
|---|---|
| `VOICE_PLATFORM` | `plivo` |
| `VOICE_HMAC_SECRET` | 32+ random characters; the same value for the engine |
| `PLIVO_AUTH_ID`, `PLIVO_AUTH_TOKEN`, `PLIVO_FROM_NUMBER` | from the client's Plivo account |
| `PLIVO_PUBLIC_URL` | the gateway tunnel's `https://` address, exactly as Plivo calls it (signatures are checked against it) |
| `PLIVO_ENGINE_URL` | the same address as `wss://.../call` |
| `PLIVO_API_BASE` | **not set** (it is only for a rehearsal; left pointing at the rehearsal, no transfer or hang-up would reach Plivo) |
| `AI_BRAIN` | `LOCAL` |
| `LOCAL_LLM_MODEL` | `ourvoice8b-r4` (the default `qwen2.5:3b` is not ours) |
| `OTP_SHOW_ON_SCREEN`, `COOKIE_SECURE` | unchanged while only the gateway is public (item 3 above) |

Engine: `VOICE_HMAC_SECRET` (same) and `CRM_URL=http://127.0.0.1:8082`.
Gateway: `CRM_URL=http://127.0.0.1:8082`, `ENGINE_URL=http://127.0.0.1:8090`.

## Order on the day

0. The day before, on the laptop: rehearse the whole call with `voice-engine/rehearse.py` (README, "Rehearsing a
   whole call"), with the in-memory test CRM and a made-up token, an outbound campaign call as in step 4. Done on
   16 Nov 2026: an incoming call transferred to the desk only after the goodbye was played; and campaign calls dialled,
   answered, identity checked (full name and customer ref), details given only after it, "call me back at 4 pm" booked
   and hung up; "stop calling me" opted out and the campaign skipped the number; a call nobody answered recorded as
   NO_ANSWER.

1. Laptop: start the CRM, the engine and the gateway (`uvicorn gateway:app --host 127.0.0.1 --port 8099`); `/health`
   on the engine answers on `127.0.0.1:8090` and says `"warmed": true` a few seconds after the start (both speech
   models loaded and run once; `warm_up_error` says why if not), and through the gateway it must give 404.
2. GPU machine: model loaded and warmed; SSH tunnel up; the CRM's test console with "Our own model" answers in ~1 s.
3. The tunnel up (to :8099 only); settings filled in; CRM restarted. From mobile data, open
   `https://<tunnel>/api/auth/me`: it must say 404.
   Then, as the admin, open **AI voice agent → Provider setup → Ready for a real call?** (`GET /api/voice/readiness`).
   It repeats steps 1–3 and the settings table itself, and says what to fix for each red line:
   - the Plivo settings, and that `PLIVO_API_BASE` is not left pointing at a rehearsal;
   - that Plivo accepts the keys (one read of the account; nothing changes);
   - that the public address is https, with `wss://.../call` on the same host;
   - what the internet reaches: 404 at `/api/auth/me` (only the gateway). If the CRM itself answers (401), that is
     refused unless codes go by SMS and are not shown on screen;
   - that an unsigned test callback reaches this CRM and is refused (401), so `CRM_URL` on the gateway is right;
   - `VOICE_HMAC_SECRET`; the engine `"warmed": true`; `AI_BRAIN` not `SIMULATED`, with the model answering;
   - calling hours, `VOICE_ENABLED` and "Pause all", the desk number, and an approved script whose opening says the
     call may be recorded.

   It places no call. Fix every "Fix first" line and press **Check again**. "Check" lines (calling hours closed, no
   desk number, no recording line) do not block a call, but read them. Steps 1–3 by hand remain the fallback.
4. In the CRM: one contact (the developer's own number) with AI-call consent recorded, an approved script, a one-contact
   campaign. Start it during the calling window (09:00–18:00 IST).
5. On the call, try: answering normally, the identity check with the right details, "call me back at 4", a question
   from the FAQ, "stop calling me". Note any long silences. During desk hours (not Sundays), also ask for a person
   once: you should hear the whole "I am connecting you to a member of our team now. Please stay on the line." and
   then the desk phone rings. If the transfer fails, an urgent call-back appears on `/voice/callbacks`.
   Then ring the Plivo number once with your number hidden (`#31#` before the number, or the phone's "Show my caller ID"
   setting turned off; not every network allows it; the incoming script must be approved). On `/voice/calls` its number must show as "withheld". If it shows digits
   instead, note them: Plivo's placeholder for a hidden number is not documented, and the CRM treats only a word,
   nothing, fewer than 8 digits or only zeros as withheld (`docs/voice-agent.md`, "A withheld number").
6. Afterwards: read the transcript and tool trail on `/voice/calls`; write down what Whisper heard against what was
   said, and the reply times. Then take the tunnel down and stop the GPU machine, but only once the engine's
   `/health` says `"undelivered": 0` (a call-back or a "stop calling me" the CRM has not received yet waits in the
   engine's outbox; README, "Real phone calls").

7. The recording: on the call's page, **Recording: Listen** plays both voices (the script's opening must say "This
   call may be recorded"; otherwise it says why it was not recorded). The file is in the CRM's recordings folder.
