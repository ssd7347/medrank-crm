# AI voice calling agent (Phase 6) — connecting a provider

Spec: section 18 of `MBBS_CRM_Master_Specification_Complete.pdf`.

Everything except the live phone call is built. With no provider connected the CRM runs in **simulated
mode**: consent, campaigns, scripts, the callback queue, transcripts, metrics and the test console all work,
but no phone rings. Campaign calls are saved with status `SIMULATED` and never appear in a family's call
history.

## What is already in the CRM

| Piece | Where |
|---|---|
| Consent per phone number (AI calls, recording), opt-out | `voice/ConsentService`, student "AI calls" tab, lead page |
| Do-not-disturb check with a 24 h cache | `voice/DndService` + `DndProvider` interface |
| Versioned call scripts with admin approval | `voice/VoiceScriptService`, `/voice/scripts` |
| Campaigns: audience, preview with reasons, start / pause / finish, "Pause all" | `voice/CampaignService`, `CampaignDispatcher`, `/voice/campaigns` |
| Eligibility at dial time: consent, DND, attempts, still needed, calling hours (IST), 2 h gap, 2 per day | `voice/CallEligibility` |
| Tool API (14 tools), verification levels enforced in code, audit of every tool call | `voice/VoiceTools`, `ToolExecutor` |
| Webhooks (call-started, transcript, call-ended), idempotent | `voice/VoiceHooksController`, `VoiceCallService` |
| Inbound lookup: known student, lead, or unknown caller | `VoiceCallService.inbound` |
| Handoff: live transfer when the desk is open, otherwise a callback task; grievance draft for refunds/disputes | `voice/HandoffService`, `/voice/callbacks` |
| Transcripts, tool trail, "flag as wrong", metrics, cost estimate, retention purge | `voice/VoiceQueryService`, `/voice`, `/voice/calls` |
| Test console with a rule-based stand-in for the AI model | `voice/SimulatedBrain`, `TestCallService`, `/voice/test` |

## Connecting a provider: the three steps

### 1. Write one adapter

Add a Spring bean that implements `com.mbbscrm.crm.voice.VoicePlatformClient`:

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
| `VOICE_DESK_NUMBER` | Counsellor desk number (+91...) for live transfers. Empty = callbacks only. |
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

## Before the first real call

1. Read and approve the scripts (the English starters are drafts from the spec; Tamil and Hindi need a native speaker).
2. Record consent for the families in the pilot, including parents' numbers for students under 18.
3. Run the test console through the scripted cases (anxious parent, wrong number, "stop calling me", distress,
   "should I withdraw?", prompt injection).
4. Pilot with one purpose (deadline reminders) to 20–50 consented families and read every transcript.

Open decisions from spec 18.18.2 (platform choice, telephony and DLT, legal classification of each call
purpose, vendor data terms, desk hours) are for the consultancy to close; nothing in the code assumes an answer.

## Tests

`backend/src/test/java/com/mbbscrm/crm/voice/`:
- `VoiceRulesTest`: calling-window maths in IST, retry timing, desk hours, signature and replay, name matching.
- `VoiceAgentIntegrationTest`: signed Tool API, verification levels and lock-out, handoff, opt-out, idempotent
  webhooks, simulated campaigns, the kill switch, script approval, and the scripted / red-team conversations.
- `VoiceLivePlatformTest`: a fake platform and a fake DND register installed as beans prove the plug-in seam:
  real dialling, blocked numbers skipped, attempts counted correctly, live transfer when the desk is open.
