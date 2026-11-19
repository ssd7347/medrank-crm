package com.aicallcrm.crm.voice;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.voice.VoiceCallService.Ended;
import com.aicallcrm.crm.voice.VoiceCallService.Inbound;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Plivo's callbacks. Every request must carry Plivo's V3 signature ({@link PlivoSignature}), made with this
 * account's auth token over the exact URL Plivo called, which is rebuilt from {@code app.voice.plivo.public-url}.
 * <ul>
 * <li>{@code answer}: a call connected. Outbound calls carry our {@code call_id}; an incoming call is looked up by
 * the caller's number. The reply streams the call's audio, both ways, to our speech engine. If the engine does not
 * answer at that moment, nobody would speak: a caller is put through to the desk while it is open, and otherwise
 * (and for an outbound call) told a member of the team will call back, with the call-back booked.</li>
 * <li>{@code hangup}: the call is over (whoever hung up).</li>
 * <li>{@code desk}: a transfer to the staff desk ({@link PlivoClient#transfer}).</li>
 * </ul>
 */
@RestController
@RequestMapping(value = "/api/voice/hooks/plivo", produces = MediaType.APPLICATION_XML_VALUE)
public class PlivoHooksController {

    static final String NOBODY ="<Speak>Sorry, no one can take your call right now. Please call again later."
            + "</Speak><Hangup/>";
    static final String CALL_BACK = "<Speak>Sorry, we cannot take this call right now. A member of our team will call "
            + "you back.</Speak><Hangup/>";

    private static final Logger log = LoggerFactory.getLogger(PlivoHooksController.class);

    private final PlivoProperties plivo;
    private final VoiceProperties voice;
    private final VoiceCallService calls;
    private final LiveCallService live;
    private final VoiceCallRepository callRepo;
    private final SpeechService speech;
    private final HandoffService handoffs;

    public PlivoHooksController(PlivoProperties plivo, VoiceProperties voice, VoiceCallService calls, LiveCallService live,
                                VoiceCallRepository callRepo, SpeechService speech, HandoffService handoffs) {
        this.plivo = plivo;
        this.voice = voice;
        this.calls = calls;
        this.live = live;
        this.callRepo = callRepo;
        this.speech = speech;
        this.handoffs = handoffs;
    }

    @PostMapping("/answer")
    public String answer(HttpServletRequest request) {
        Map<String, String> p = verified(request);
        String callUuid = p.get("CallUUID");
        UUID callId = uuid(request.getParameter("call_id"));
        if (callId != null) {
            // Outbound: the person picked up. A repeat delivery of the same answer still gets the stream; a call
            // this CRM did not place, or one already over, is hung up.
            calls.started(callId, callUuid);
            boolean ours = callRepo.findById(callId).filter(c -> !c.isTestCall() && c.getStatus().live()).isPresent();
            if (!ours) {
                return xml("<Hangup/>");
            }
            return xml(speech.answers() ? stream(callId) : cutOff(callId));
        }
        Inbound in = calls.inbound(p.get("From"), callUuid);
        if (in.fallback() || in.callId() == null) {
            return xml(voice.deskTransferNumber().isBlank() ? NOBODY : dial());
        }
        if (speech.answers()) {
            return xml(stream(in.callId()));
        }
        // The engine is down: a caller is put through to the desk while it is open, rather than to silence.
        return xml(handoffs.deskOpenNow() ? dial() : cutOff(in.callId()));
    }

    /**
     * The speech engine did not answer as the call connected, so nobody would speak. A call-back is booked, as for a
     * call cut off mid-way ({@link LiveCallService#trouble}), and the person is told so (again, if Plivo asks twice).
     * Anyone no call-back is booked for ({@link HandoffService#canCallBack}: their number was withheld, or they asked
     * not to be called) is only asked to call again.
     */
    private String cutOff(UUID callId) {
        log.warn("Call {} connected while the speech engine was not answering", callId);
        try {
            live.trouble(callId, "the speech engine was not answering when the call connected");
        } catch (ApiException e) {
            return NOBODY;
        }
        return handoffs.callBackOpen(callId) ? CALL_BACK : NOBODY;
    }

    @PostMapping("/hangup")
    public String hangup(HttpServletRequest request) {
        Map<String, String> p = verified(request);
        UUID callId = uuid(request.getParameter("call_id"));
        String callUuid = p.get("CallUUID");
        String status = switch (String.valueOf(p.get("CallStatus")).toLowerCase(java.util.Locale.ROOT)) {
            case "busy", "no-answer", "timeout", "cancel" -> "no_answer";
            case "failed" -> "failed";
            default -> "completed";
        };
        Integer duration = number(p.get("Duration"));
        calls.ended(new Ended(callId, callUuid, status, null, null, duration == null ? number(p.get("BillDuration")) : duration,
                p.get("HangupCause") != null ? p.get("HangupCause") : p.get("CallStatus"), null));
        live.hungUp(callId, callUuid);
        return xml("");
    }

    @PostMapping("/desk")
    public String desk(HttpServletRequest request) {
        verified(request);
        return xml(voice.deskTransferNumber().isBlank() ? NOBODY : dial());
    }

    /**
     * The URL Plivo streams to: our engine with this call's id and a token only Plivo is given (in this XML), which
     * the engine checks with the same shared secret ({@link #streamToken}).
     */
    private String stream(UUID callId) {
        String url = plivo.engineUrl() + (plivo.engineUrl().contains("?") ? "&" : "?") + "call_id=" + callId
                + "&token=" + streamToken(voice.hmacSecret(), callId);
        // keepCallAlive: the call stays up while the engine talks; if the engine drops, the call ends (Hangup).
        return "<Stream bidirectional=\"true\" keepCallAlive=\"true\" contentType=\"audio/x-mulaw;rate=8000\">"
                + escape(url) + "</Stream><Hangup/>";
    }

    private String dial() {
        return "<Dial callerId=\"" + escape(plivo.fromNumber()) + "\"><Number>" + escape(voice.deskTransferNumber())
                + "</Number></Dial>";
    }

    /** Hex HMAC-SHA256 of {@code stream:<call id>}; voice-engine/engine.py computes the same. */
    static String streamToken(String secret, UUID callId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("stream:" + callId).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ checks

    /** The form fields Plivo posted, after checking its signature; refuses everything until Plivo is set up. */
    private Map<String, String> verified(HttpServletRequest request) {
        if (!plivo.configured() || !voice.signingConfigured()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Plivo is not set up");
        }
        String query = request.getQueryString();
        Map<String, List<String>> inQuery = PlivoSignature.parseQuery(query);
        Map<String, List<String>> form = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> {
            if (!inQuery.containsKey(k)) {
                form.put(k, List.of(v));
            }
        });
        String url = plivo.url(request.getRequestURI()) + (query == null || query.isEmpty() ? "" : "?" + query);
        if (!PlivoSignature.valid(plivo.authToken(), request.getMethod(), url, form,
                request.getHeader("X-Plivo-Signature-V3-Nonce"), request.getHeader("X-Plivo-Signature-V3"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid signature");
        }
        Map<String, String> out = new LinkedHashMap<>();
        form.forEach((k, v) -> out.put(k, v.isEmpty() ? null : v.get(0)));
        return out;
    }

    private static String xml(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response>" + body + "</Response>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Integer number(String s) {
        try {
            return s == null ? null : Integer.valueOf(Math.max(0, Integer.parseInt(s.trim())));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
