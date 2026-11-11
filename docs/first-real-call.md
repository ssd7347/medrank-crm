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
4. **Indian English on an 8 kHz phone line (free; being checked).** The ear is Whisper `base.en` and the voice is
   Piper's US `en_US-lessac-medium`. The tool is built: `voice-engine/ear_check.py` (README: "Checking the ears").
   **Baseline (11 Nov, Piper's own voice reading the 24 test sentences):** ordinary words and numbers are fine, about
   2–3% of words wrong, and the phone line (8 kHz mu-law, and a mobile's AMR codec) barely changes that. **Indian
   names are the weak spot for both ears:** Suresh became "surish", "Sirish" or "syringe", Irfan "Erfan", Lakshmi
   Narayanan "Laxminarayanan", Priya Venkatesh "pre-eventkadish". The CRM's identity check needs the name word for
   word, so such callers would have failed it. **Fixed the same day:** names are now matched the way the ear spells
   them (`NameMatch`, see `docs/voice-agent.md`); names the ear garbles completely ("syringe" for Suresh) still fail
   and use a try. Speed on the laptop's CPU, per sentence: `base.en` about 1.0 s, `small.en` about 3.0 s (too slow on
   this CPU). Next: the developer and a few others record the sentences (real Indian voices; the baseline is a US
   voice).

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
the GPU machine too would cut the ear's part. The first call measures it.

## Settings (`backend/.env`, never committed)

| Key | Value for the first call |
|---|---|
| `VOICE_PLATFORM` | `plivo` |
| `VOICE_HMAC_SECRET` | 32+ random characters; the same value for the engine |
| `PLIVO_AUTH_ID`, `PLIVO_AUTH_TOKEN`, `PLIVO_FROM_NUMBER` | from the client's Plivo account |
| `PLIVO_PUBLIC_URL` | the gateway tunnel's `https://` address, exactly as Plivo calls it (signatures are checked against it) |
| `PLIVO_ENGINE_URL` | the same address as `wss://.../call` |
| `AI_BRAIN` | `LOCAL` |
| `LOCAL_LLM_MODEL` | `ourvoice8b-r4` (the default `qwen2.5:3b` is not ours) |
| `OTP_SHOW_ON_SCREEN`, `COOKIE_SECURE` | unchanged while only the gateway is public (item 3 above) |

Engine: `VOICE_HMAC_SECRET` (same) and `CRM_URL=http://127.0.0.1:8082`.
Gateway: `CRM_URL=http://127.0.0.1:8082`, `ENGINE_URL=http://127.0.0.1:8090`.

## Order on the day

1. Laptop: start the CRM, the engine and the gateway (`uvicorn gateway:app --host 127.0.0.1 --port 8099`); `/health`
   on the engine answers on `127.0.0.1:8090`, and through the gateway it must give 404.
2. GPU machine: model loaded and warmed; SSH tunnel up; the CRM's test console with "Our own model" answers in ~1 s.
3. The tunnel up (to :8099 only); settings filled in; CRM restarted. From mobile data, open
   `https://<tunnel>/api/auth/me`: it must say 404.
4. In the CRM: one contact (the developer's own number) with AI-call consent recorded, an approved script, a one-contact
   campaign. Start it during the calling window (09:00–20:00 IST).
5. On the call, try: answering normally, the identity check with the right details, "call me back at 4", a question
   from the FAQ, "stop calling me". Note any long silences.
6. Afterwards: read the transcript and tool trail on `/voice/calls`; write down what Whisper heard against what was
   said, and the reply times. Then take the tunnel down and stop the GPU machine.

Known limits that stay for now: no call recording (the scripts say "may be recorded": adjust before live use); a desk
transfer cuts "I am connecting you"; the model loads on the first call after the engine starts.
