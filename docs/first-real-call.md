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
3. **The CRM must be safe to put online (developer, before any tunnel).** Today `OTP_SHOW_ON_SCREEN=true`, because
   SMS/WhatsApp codes are not connected: on a public address anyone who knows a staff mobile number could sign in.
   Either connect real OTP delivery and set it to `false`, or expose **only** Plivo's paths
   (`/api/voice/hooks/plivo/*`) and keep the rest of the CRM on `localhost`. Also `COOKIE_SECURE=true` behind HTTPS.
4. **Indian English on an 8 kHz phone line (free; can be checked now).** The ear is Whisper `base.en` and the voice is
   Piper's US `en_US-lessac-medium`; neither has heard an Indian caller on a phone line yet. This is the biggest open
   risk. Check before paying for anything: record the developer and a few others reading test sentences, pass the
   audio through the same 8 kHz mu-law path as `phone.py`, and compare `base.en` with `small.en`.

## The layout for the first call

```
Developer's phone ── Plivo ──HTTPS──> CRM (laptop, :8082)      ── SSH tunnel ──> Ollama + ourvoice8b-r4 (GPU machine)
                       └──── WSS ───> speech engine (laptop, :8090)
```

* CRM and engine stay on the laptop (the engine measured 0.88 s on the laptop's CPU), reached through one HTTPS
  tunnel each (ngrok is installed; check the free plan's limits on tunnels and WebSockets first).
* The brain runs on the GPU machine. **Ollama is never put on the internet**: the laptop reaches it through
  `ssh -N -L 11434:localhost:11434 <gpu-machine>`, so `LOCAL_LLM_URL=http://localhost:11434` stays unchanged.
* On the GPU machine: install Ollama, copy the `.gguf` from Drive, `ollama pull qwen3:4b-instruct`, then
  `python make_modelfile.py <gguf> --base qwen3:4b-instruct --name ourvoice8b-r4 --create` (the training kit's own
  script, exactly as on Colab), and send one warm-up message so the model is loaded before the call.

Expected reply time: 0.7 s end-of-speech wait + ~0.9 s ear and voice + ~1.0 s brain + the network. **About 2.6–3 s**,
above the ~2 s target; the first call measures it.

## Settings (`backend/.env`, never committed)

| Key | Value for the first call |
|---|---|
| `VOICE_PLATFORM` | `plivo` |
| `VOICE_HMAC_SECRET` | 32+ random characters; the same value for the engine |
| `PLIVO_AUTH_ID`, `PLIVO_AUTH_TOKEN`, `PLIVO_FROM_NUMBER` | from the client's Plivo account |
| `PLIVO_PUBLIC_URL` | the CRM tunnel's `https://` address, exactly as Plivo calls it (signatures are checked against it) |
| `PLIVO_ENGINE_URL` | the engine tunnel's address as `wss://.../call` |
| `AI_BRAIN` | `LOCAL` |
| `LOCAL_LLM_MODEL` | `ourvoice8b-r4` (the default `qwen2.5:3b` is not ours) |
| `OTP_SHOW_ON_SCREEN`, `COOKIE_SECURE` | see item 3 above |

Engine: `VOICE_HMAC_SECRET` (same) and `CRM_URL=http://127.0.0.1:8082`.

## Order on the day

1. Laptop: start the CRM and the engine; `/health` on the engine answers.
2. GPU machine: model loaded and warmed; SSH tunnel up; the CRM's test console with "Our own model" answers in ~1 s.
3. Tunnels up; settings filled in; CRM restarted.
4. In the CRM: one contact (the developer's own number) with AI-call consent recorded, an approved script, a one-contact
   campaign. Start it during the calling window (09:00–20:00 IST).
5. On the call, try: answering normally, the identity check with the right details, "call me back at 4", a question
   from the FAQ, "stop calling me". Note any long silences.
6. Afterwards: read the transcript and tool trail on `/voice/calls`; write down what Whisper heard against what was
   said, and the reply times. Then take the tunnels down and stop the GPU machine.

Known limits that stay for now: no call recording (the scripts say "may be recorded": adjust before live use); a desk
transfer cuts "I am connecting you"; the model loads on the first call after the engine starts.
