# AI voice calling agent: connecting a provider

Everything except the live phone call is built. With no provider connected the CRM runs in **simulated
mode**: consent, campaigns, scripts, the callback queue, transcripts, metrics and the test console all work,
but no phone rings. Campaign calls are saved with status `SIMULATED` and never appear in a contact's call
history.

## What is already in the CRM

| Piece | Where |
|---|---|
| Consent per phone number (AI calls, recording), opt-out | `voice/ConsentService`, contact page, Excel import |
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
| verify_identity | Checks the full name plus a field marked "Ask to confirm" (e.g. customer ID; the number part or last four characters are enough). Three failures lock the call and hand it to a person |
| get_contact_details | The fields the AI may say: "To anyone" fields at once, "Only after verified" fields after the check, "Never" fields never |
| search_faq | Answers from the AI knowledge base only |
| schedule_callback | Books a call-back for staff; an unknown caller becomes a contact |
| request_human_handoff | Live transfer, or an urgent call-back for complaints and distress |
| log_call_outcome | Acknowledged, promised to act, interested, not interested, wrong person... (updates the contact's status) |
| opt_out | Stops AI calls; "all calls" also marks the contact "Do not call" |

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
