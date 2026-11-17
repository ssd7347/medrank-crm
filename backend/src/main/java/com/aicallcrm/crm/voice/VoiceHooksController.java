package com.aicallcrm.crm.voice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.voice.VoiceCallService.Ended;
import com.aicallcrm.crm.voice.VoiceCallService.Inbound;
import com.aicallcrm.crm.voice.VoiceCallService.Line;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The endpoints the voice platform calls: tools during a call, call events, and "who is
 * ringing in?". They take no staff login; instead every request must carry a valid signature
 * ({@link VoiceSignatures}), which is checked against the exact bytes received before anything is parsed.
 */
@RestController
@RequestMapping("/api/voice/hooks")
public class VoiceHooksController {

    private final VoiceSignatures signatures;
    private final ToolExecutor executor;
    private final VoiceCallService calls;
    private final ObjectMapper mapper;
    private final LiveCallService live;
    private final RecordingService recordings;

    public VoiceHooksController(VoiceSignatures signatures, ToolExecutor executor, VoiceCallService calls,
                                ObjectMapper mapper, LiveCallService live, RecordingService recordings) {
        this.signatures = signatures;
        this.executor = executor;
        this.calls = calls;
        this.mapper = mapper;
        this.live = live;
        this.recordings = recordings;
    }

    /** {@code { "call_id": "...", "args": { } }}. The contact is never taken from the arguments. */
    @PostMapping("/tools/{tool}")
    public Map<String, Object> tool(@PathVariable String tool, @RequestBody(required = false) String raw,
                                    @RequestHeader(value = VoiceSignatures.SIGNATURE_HEADER, required = false) String sig,
                                    @RequestHeader(value = VoiceSignatures.TIMESTAMP_HEADER, required = false) String ts) {
        Map<String, Object> body = verified(raw, sig, ts);
        UUID callId = uuid(body.get("call_id"));
        Object args = body.get("args");
        return executor.execute(callId, tool, asMap(args)).envelope();
    }

    /** {@code call-started}, {@code transcript} and {@code call-ended}. Safe to deliver more than once. */
    @PostMapping("/webhooks/{event}")
    public Map<String, Object> webhook(@PathVariable String event, @RequestBody(required = false) String raw,
                                       @RequestHeader(value = VoiceSignatures.SIGNATURE_HEADER, required = false) String sig,
                                       @RequestHeader(value = VoiceSignatures.TIMESTAMP_HEADER, required = false) String ts) {
        Map<String, Object> body = verified(raw, sig, ts);
        UUID callId = uuid(body.get("call_id"));
        String providerCallId = string(body.get("provider_call_id"));
        boolean applied = switch (event) {
            case "call-started" -> calls.started(callId, providerCallId);
            case "transcript" -> calls.transcript(callId, providerCallId, lines(body.get("transcript")));
            case "call-ended" -> calls.ended(new Ended(callId, providerCallId, string(body.get("status")),
                    lines(body.get("transcript")), string(body.get("recording_url")), integer(body.get("duration_sec")),
                    string(body.get("disconnect_reason")), string(body.get("summary"))));
            default -> throw ApiException.notFound("Event");
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("applied", applied);
        return out;
    }

    /** {@code { "caller_number": "...", "provider_call_id": "..." }} when someone rings the company. */
    @PostMapping("/inbound/lookup")
    public Map<String, Object> inbound(@RequestBody(required = false) String raw,
                                       @RequestHeader(value = VoiceSignatures.SIGNATURE_HEADER, required = false) String sig,
                                       @RequestHeader(value = VoiceSignatures.TIMESTAMP_HEADER, required = false) String ts) {
        Map<String, Object> body = verified(raw, sig, ts);
        Inbound in = calls.inbound(string(body.get("caller_number")), string(body.get("provider_call_id")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fallback", in.fallback());
        out.put("call_id", in.callId());
        out.put("dynamic_variables", in.variables());
        out.put("opening_line", in.openingLine());
        out.put("system_prompt", in.systemPrompt());
        out.put("model", in.model());
        return out;
    }

    // ------------------------------------------------------------------ our own speech engine on a real call

    /**
     * {@code open}: {@code {"call_id"}} when the call connects; {@code turn}: {@code {"call_id", "said"}} for each
     * thing the caller says; {@code end}: {@code {"call_id"}} after the goodbye was heard, to hang up. Replies
     * {@code {"say": "...", "over": false}} ({@code end}: {@code {"hung_up": true, "transferred": false}}, transferred
     * when the caller had been told they are being connected to the desk); {@code open} adds
     * {@code "hint"}, the called contact's name for the engine's ear, when the contact is known. {@code trouble}:
     * {@code {"call_id", "what"}} when the engine hit a fault, told the caller someone will call back and is hanging
     * up; replies {@code {"callback_booked": true|false}}. {@code last}: {@code {"call_id", "said"}}, what the caller said
     * that the engine had not passed on when the stream closed (they hung up mid-sentence); nothing is said back;
     * replies {@code {"opted_out": true|false}}. {@code open} also replies {@code "record"}: whether the engine records
     * this call. {@code recording}: {@code {"call_id", "audio"}}, the call's recording as base64 Ogg Opus, sent once
     * the call has ended; replies {@code {"kept": true|false}} (RecordingService.receive).
     */
    @PostMapping("/live/{step}")
    public Map<String, Object> live(@PathVariable String step, @RequestBody(required = false) String raw,
                                    @RequestHeader(value = VoiceSignatures.SIGNATURE_HEADER, required = false) String sig,
                                    @RequestHeader(value = VoiceSignatures.TIMESTAMP_HEADER, required = false) String ts) {
        Map<String, Object> body = verified(raw, sig, ts);
        UUID callId = uuid(body.get("call_id"));
        if (callId == null) {
            throw ApiException.badRequest("call_id is needed");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        switch (step) {
            case "open", "turn" -> {
                LiveCallService.Reply r = "open".equals(step) ? live.open(callId) : live.turn(callId, string(body.get("said")));
                out.put("say", r.say());
                out.put("over", r.over());
                if (r.hint() != null) {
                    out.put("hint", r.hint());
                }
                if ("open".equals(step)) {
                    out.put("record", r.record());
                }
            }
            case "end" -> {
                LiveCallService.Ended e = live.end(callId);
                out.put("hung_up", e.hungUp());
                out.put("transferred", e.transferred());
            }
            case "trouble" -> out.put("callback_booked", live.trouble(callId, string(body.get("what"))));
            case "last" -> out.put("opted_out", live.lastWords(callId, string(body.get("said"))));
            case "recording" -> out.put("kept", recordings.receive(callId, string(body.get("audio"))));
            default -> throw ApiException.notFound("Step");
        }
        return out;
    }

    // ------------------------------------------------------------------ parsing

    private Map<String, Object> verified(String raw, String signature, String timestamp) {
        signatures.verify(raw, signature, timestamp);
        try {
            return asMap(raw == null || raw.isBlank() ? Map.of() : mapper.readValue(raw, Map.class));
        } catch (JacksonException e) {
            throw ApiException.badRequest("Malformed request");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<Line> lines(Object o) {
        List<Line> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> m = asMap(item);
                String text = string(m.get("text"));
                String role = string(m.get("role"));
                if (text == null && string(m.get("tool")) == null) {
                    continue;
                }
                Instant at;
                try {
                    at = m.get("ts") == null ? Instant.now() : Instant.parse(String.valueOf(m.get("ts")));
                } catch (RuntimeException e) {
                    at = Instant.now();
                }
                out.add(new Line("caller".equals(role) || "user".equals(role) ? "caller"
                        : "tool".equals(role) ? "tool" : "agent",
                        text == null ? null : text.length() > 2000 ? text.substring(0, 2000) : text,
                        string(m.get("tool")), string(m.get("status")), at));
            }
        }
        return out;
    }

    private static UUID uuid(Object o) {
        try {
            return o == null ? null : UUID.fromString(String.valueOf(o));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String string(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }

    private static Integer integer(Object o) {
        return o instanceof Number n ? Integer.valueOf(Math.max(0, n.intValue())) : null;
    }
}
