# AI voice calling agent: connecting a provider

Everything except the live phone call is built. With no provider connected the CRM runs in **simulated
mode**: consent, campaigns, scripts, the callback queue, transcripts, metrics and the test console all work,
but no phone rings. Campaign calls are saved with status `SIMULATED` and never appear in a contact's call
history.

## What is already in the CRM

| Piece | Where |
|---|---|
| Consent per phone number (AI calls, recording), opt-out | `voice/ConsentService`, contact page, Excel import |
| Erasing a person on request; an erased number that had said no stays opted out (imported consent ignored) | `privacy/ErasureService`, `voice/ConsentService`, contact page |
| Do-not-disturb check with a 24 h cache | `voice/DndService` + `DndProvider` interface |
| Versioned call scripts with admin approval | `voice/VoiceScriptService`, `/voice/scripts` |
| Campaigns: contacts chosen by status, upload and a custom-field condition, preview with reasons, start / pause / finish, "Pause all" | `voice/CampaignService`, `CampaignDispatcher`, `/voice/campaigns` |
| Eligibility at dial time: consent, DND, attempts, still needed, calling hours (IST), 2 h gap, 2 per day | `voice/CallEligibility` |
| Tool API (8 tools), identity checks against the customer's own fields, per-field control of what the AI may say, audit of every tool call | `voice/VoiceTools`, `ToolExecutor` |
| Webhooks (call-started, transcript, call-ended), idempotent | `voice/VoiceHooksController`, `VoiceCallService` |
| Inbound lookup: known contact or unknown caller (who becomes a contact on a call-back) | `VoiceCallService.inbound` |
| Handoff: live transfer when the desk is open, otherwise a call-back task; complaints and distress are urgent | `voice/HandoffService`, `/voice/callbacks` |
| Transcripts, tool trail, "flag as wrong", metrics, cost estimate, retention purge | `voice/VoiceQueryService`, `/voice`, `/voice/calls` |
| Test console with a rule-based stand-in for the AI model | `voice/SimulatedBrain`, `TestCallService`, `/voice/test` |

## Plivo as the phone line (with our own model and speech engine)

Built in: `PlivoClient`, `PlivoHooksController`, `LiveCallService`, and `voice-engine/phone.py`. Tested with a fake
Plivo API and on one machine with the real speech models; **not yet tried on a real phone line**.

**Each customer's copy of the CRM has its own Plivo account**: their own KYC, numbers and DLT registration. Your
own account is for demos and test calls to your team.

1. In Plivo, choose the **India data region** at sign-up (it cannot be changed), finish KYC and rent a number.
2. Host the CRM and `voice-engine/` with the brain on a GPU server, and make only Plivo's callbacks and the call
   stream public, through `voice-engine/gateway.py` (see `voice-engine/README.md`). The CRM itself goes on a public
   address only once staff sign-in codes are really sent (`OTP_SHOW_ON_SCREEN=false`).
3. CRM settings: `VOICE_PLATFORM=plivo`, `VOICE_HMAC_SECRET`, `PLIVO_AUTH_ID`, `PLIVO_AUTH_TOKEN`,
   `PLIVO_FROM_NUMBER`, `PLIVO_PUBLIC_URL` (the CRM's address exactly as Plivo reaches it: signatures are checked
   against it), `PLIVO_ENGINE_URL` (`wss://your-engine/call`), and `AI_BRAIN`. `PLIVO_API_BASE` stays unset; it is
   only for rehearsing a call on one machine (`voice-engine/rehearse.py`, see its README).
4. Engine settings: the same `VOICE_HMAC_SECRET`, `CRM_URL`, and `STT_MODEL=small.en` (on the phone line it heard 34 of
   37 key words of the one Indian voice recorded so far, base.en 27; it needs the GPU machine for speed). On a call to a known contact the CRM sends the contact's name, and with small.en
   the engine gives it to Whisper so names come out as registered (`NAME_HINTS`, see `voice-engine/README.md`).
5. For incoming calls, create a Plivo application and attach it to the number, with answer URL
   `https://your-crm/api/voice/hooks/plivo/answer` and hangup URL `https://your-crm/api/voice/hooks/plivo/hangup`
   (both POST). Outgoing calls set these URLs themselves.
6. Make one test call to your own phone and read its transcript before anything else (the plan for it, with what
   is still missing: `docs/first-real-call.md`).

Known limits: Plivo's recording is not switched on, so calls keep a transcript but no recording; the starter
openings do not say the call may be recorded (add that line if recording is switched on). The speech engine loads
and runs both its models as it starts (`WARM_UP`, on by default), so the first caller does not wait for them; the
brain is warmed by the CRM while each call's opening line plays.

A transfer to the desk on Plivo waits until the caller has heard "I am connecting you": the hand-over marks the call
(`transfer_pending`), and when the engine has played the line and asks to end the call, the CRM transfers it instead
of hanging up (`end` replies `"transferred": true`). The engine then leaves the stream for Plivo to close, and closes it
itself only after 10 seconds. If the transfer fails, or the caller hangs up first, an urgent call-back is booked.

## Connecting a provider: the three steps

### 1. Write one adapter

Add a Spring bean that implements `com.aicallcrm.crm.voice.VoicePlatformClient`:

```java
@Component
class RetellClient implements VoicePlatformClient {
    public String name() { return "retell"; }

    public Placed createOutboundCall(OutboundCall call) {
        // Call the platform's "create phone call" API with:
        //   call.phoneE164(), call.systemPrompt(), call.openingLine(), call.model(),
        //   call.dynamicVariables(), call.recording(), call.maxDurationSec()
        // and pass call.callId() as metadata so it comes back on every tool call and webhook.
        return Placed.ok(platformCallId);          // or Placed.failed("reason")
    }

    public boolean transfer(String providerCallId, String deskNumberE164, String summary) {
        // Ask the platform to warm-transfer the call; return false to fall back to a callback task.
    }
}
```

A platform that streams the call to our own speech engine (as Plivo does) also overrides
`transfersAfterGoodbye()` to return true, so the transfer waits until the agent's last line has been heard.

If the platform signs or shapes its webhooks differently from ours, the adapter (or a small controller next
to it) translates them into calls on `VoiceCallService.started / transcript / ended` and
`ToolExecutor.execute`. Nothing else in the CRM changes.

A DND scrubbing service plugs in the same way: a bean implementing `DndProvider`.

### 2. Server settings (`backend/.env` or real environment variables)

| Key | Meaning |
|---|---|
| `VOICE_PLATFORM` | The adapter's `name()`, e.g. `retell`. `simulated` until then. |
| `VOICE_HMAC_SECRET` | 32+ random characters, also entered in the platform. Empty = every hook request is refused. |
| `VOICE_DESK_NUMBER` | Staff desk number (+91...) for live transfers. Empty = callbacks only. |
| `VOICE_DESK_OPEN` / `VOICE_DESK_CLOSE` | Desk hours, IST (default 09:30–18:30, Monday–Saturday). |
| `VOICE_WINDOW_START` / `VOICE_WINDOW_END` | Legal calling window, IST (default 09:00–20:00). Confirm with your compliance adviser. |
| `VOICE_COST_PER_MINUTE_INR`, `VOICE_MONTHLY_CAP_INR` | For the cost estimate and the monthly warning. |
| `VOICE_RETENTION_DAYS` | Transcripts and recordings are deleted after this (default 365). |
| `VOICE_ENABLED` | `false` switches the whole feature off. |

### 3. Platform configuration

The admin page **AI voice agent → Provider setup** lists the exact URLs and every tool with its JSON Schema
(copy button). The CRM must be on a public HTTPS address.

- Tools: `POST /api/voice/hooks/tools/{tool}` with body `{"call_id": "...", "args": {...}}`.
- Incoming calls: `POST /api/voice/hooks/inbound/lookup` with `{"caller_number": "...", "provider_call_id": "..."}`.
  The reply gives `call_id`, the opening line, the rendered script, the model and the dynamic variables, or
  `fallback: true` (feature off, paused, or no approved script).
- Events: `POST /api/voice/hooks/webhooks/call-started | transcript | call-ended`. `call-ended` takes
  `call_id` or `provider_call_id`, `status` (`completed`, `no_answer`, `busy`, `failed`), `duration_sec`,
  `recording_url`, `disconnect_reason`, `summary` and `transcript` (`[{role, text, ts}]`).

Every request must carry:

```
X-Voice-Timestamp: <unix seconds>
X-Voice-Signature: sha256=<hex HMAC-SHA256(secret, timestamp + "." + raw body)>
```

The timestamp is inside the signature so a captured request cannot be replayed; anything older than five
minutes is refused.

## The tools the agent can use

| Tool | What it does |
|---|---|
| get_call_context | Why the call is happening, the first name, and which identity questions to ask |
| verify_identity | Checks the full name plus a field marked "Ask to confirm" (e.g. customer ID; the number part or last four characters are enough). The name is matched the way a phone ear spells it (`NameMatch`): Surish or Sirish for Suresh, Erfan for Irfan, Laxminarayanan for Lakshmi Narayanan pass; a different consonant (Rajesh for Ramesh), another ending (Kumari for Kumar) or a short name spelled differently (Mona for Meena) does not. A few different names still pass as one (Karan and Kiran, Sunil and Sonal, Kamal and Komal), so the identity answer must always be right too. Three failures lock the call and hand it to a person. A check with only a first name, or the right name with no answer to the identity question, is answered by asking for it without using a try, twice; the third locks the call the same way |
| get_contact_details | The fields the AI may say: "To anyone" fields at once, "Only after verified" fields after the check, "Never" fields never |
| search_faq | Answers from the AI knowledge base only |
| schedule_callback | Books a call-back for staff; an unknown caller becomes a contact. The caller is promised the time they asked for when it is clear and can be kept (`CallbackTime`): "4 pm" → "on Monday 16 November at 4:00 PM"; "tomorrow morning" → "on Tuesday 17 November, between 9:30 AM and 12:00 PM"; "after 5", "between 4 and 5 pm", "in 2 hours", "before 3", a weekday. A time already gone today with no day given is the next working day (the day is always spoken). Outside desk hours or on a Sunday they hear the hours, then the usual time; anything unclear, negated ("not tomorrow", "busy at 4"), two different times, or under 15 minutes away gets the usual time: two hours of desk time, never after closing. The task is due at the promised time, and the staff alert says it ("Call back on Tuesday 17 November at 4:00 PM: …"). Booked again in the same call: still one task. A new clear time that the booked one does not fit ("actually, make it 5 pm") moves the task, and whoever has it is told ("Call-back time changed, …", with the old time); the same request said vaguer ("this afternoon" after "4 pm"), or anything unclear or outside desk hours, keeps the booked time. If the caller's last word on it is a "no" ("no need to call me back", "cancel the callback"; not "don't call me back after 6 pm" or "... call me tomorrow instead": `CallerSignals.callbackWithdrawn`), the CRM checks when the call ends and when the last words arrive: the call-back this call booked (only that: a "no" to the agent's offer says nothing about one from another day) stays open but starts "CHECK BEFORE CALLING: later in the AI call they said they do not need a call-back...", and whoever has it, else the owner or the managers, gets "Call-back may not be needed: <name>" with the caller's words. It is not closed, since a closed call-back cannot be reopened. Logging the outcome after such a "no" no longer books one from the earlier request |
| request_human_handoff | Live transfer, or an urgent call-back for complaints and distress |
| log_call_outcome | Acknowledged, promised to act, interested, not interested, wrong person... (updates the contact's status). "Call-back requested" with none booked yet books one, so nobody waits for a call the team does not know about. So does any outcome when the caller asked to be called back or for a new date ("call me back at 4", "can we change it to Tuesday") and the model did not book it (`CallerSignals.CALLBACK`); the model's outcome is kept, and the caller's words go on the task. One call never gets two call-back tasks |
| opt_out | Stops AI calls; "all calls" also marks the contact "Do not call". If the caller said "stop calling", "don't call me again", "remove my number" and the like, the CRM runs this instead of whatever outcome the model records (`CallerSignals.OPT_OUT`). If the call ends without it (the caller hung up first, or the model asked again instead), the CRM records it from the caller's words when the call ends (`VoiceCallService.optOutIfAsked`). Words the engine had not passed on when the stream closed (said as the caller hung up, or over the goodbye) are heard then and sent as last words (`POST /api/voice/hooks/live/last`), which opt the caller out at once if they asked to stop. Their open call-backs (from this call or earlier ones) are dealt with too (`HandoffService.optedOut`): "all calls" cancels them (closed with "Cancelled by the CRM: ...", and the urgent alert says how many); AI calls only keeps them, since the caller may still want the person they asked for, but each starts "CHECK BEFORE CALLING: ..." and whoever has it is told to read the transcript first. Any contact that becomes "Do not call" (also on the contact page, by a bulk stage change or a merge: `ContactMarkedDoNotCall`, `HandoffService.markedDoNotCall`) has its open call-backs closed: "Cancelled by the CRM: the contact was marked 'Do not call'." |

## Keeping the reason for the call private

When a call needs the identity check, our own model's replies are checked before the caller hears them
(`FactGuard.reasonWords`, in `LlmBrain`): until the check passes, words such as overdue, EMI, loan, payment, fee or amount,
and the other packs' reasons (appointment, check-up, doctor, course, class, admission, policy, renewal, insurance, claim,
property, visit, flat, plot), are stopped unless the opening line, an identity question, the caller or a tool used that word first. The model is asked
again with a private note; if it repeats it, the caller hears a fixed line that names nothing: "It is about your account
with us. May I have your full name, please?" If the caller just asked whether this is a person or a machine, the line
starts "I am an AI assistant, not a person." This protects only what
the model says. **The opening line is the customer's own**, and so are the names of the identity questions: asking for a
"loan account number" hints at a loan. The Scripts page flags both with the same word list
(`VoiceScriptService.openingWarnings` under each version's opening line, and approving one asks again;
`identityWarnings` at the top of the page, from `/voice/scripts/identity-warnings`), but a word outside the list is not
flagged. Review these with the
customer's compliance adviser before going live.

## When the model gets stuck

If the model is about to say, word for word, the reply it already gave the last two times, the caller does not hear it a
third time: a person takes over (`LlmBrain`, the same hand-over as when the model fails). Closing words after an
outcome is recorded are not counted.

## Before the first real call

1. Read, adapt and approve the scripts for this customer (the starters are generic drafts).
2. Make sure everyone in the first campaign gave consent, and that it is recorded (per contact, or for a whole
   imported list).
3. Run the test console through the scripted cases (wrong person, busy, "stop calling me", complaint, distress,
   prompt injection).
4. Pilot one campaign with 20–50 contacts and read every transcript before scaling up.

Decisions that are the customer's (platform choice, telephony and DLT registration, the legal classification of
each kind of call, vendor data terms, desk hours) are settings or contracts; nothing in the code assumes an answer.

## Tests

`backend/src/test/java/com/aicallcrm/crm/voice/`:
- `VoiceRulesTest`: calling-window maths in IST, retry timing, desk hours, signature and replay, name and reference matching, field conditions, spreadsheet parsing.
- `VoiceAgentIntegrationTest`: signed Tool API, verification levels and lock-out, handoff, opt-out, idempotent
  webhooks, simulated campaigns, the kill switch, script approval, and the scripted / red-team conversations.
- `VoiceLivePlatformTest`: a fake platform and a fake DND register installed as beans prove the plug-in seam:
  real dialling, blocked numbers skipped, attempts counted correctly, live transfer when the desk is open.
