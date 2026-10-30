package com.aicallcrm.crm.voice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Plivo as the phone line, with our own speech engine holding the conversation: Plivo places and receives calls
 * and streams their audio to voice-engine/ (see {@link PlivoHooksController} for the callbacks). Selected with
 * {@code app.voice.platform=plivo}.
 */
@Component
public class PlivoClient implements VoicePlatformClient {

    private static final Logger log = LoggerFactory.getLogger(PlivoClient.class);

    private final PlivoProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public PlivoClient(PlivoProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "plivo";
    }

    @Override
    public Placed createOutboundCall(OutboundCall call) {
        if (!props.configured()) {
            return Placed.failed("Plivo is not set up");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", props.fromNumber());
        body.put("to", call.phoneE164());
        body.put("answer_url", props.url("/api/voice/hooks/plivo/answer?call_id=" + call.callId()));
        body.put("answer_method", "POST");
        body.put("hangup_url", props.url("/api/voice/hooks/plivo/hangup?call_id=" + call.callId()));
        body.put("hangup_method", "POST");
        body.put("ring_timeout", props.ringTimeoutSec());
        body.put("time_limit", call.maxDurationSec());
        HttpResponse<String> res = send("POST", "/Call/", body);
        if (res == null || res.statusCode() / 100 != 2) {
            return Placed.failed(res == null ? "Plivo could not be reached" : "Plivo answered " + res.statusCode());
        }
        Object uuid = mapper.readValue(res.body(), Map.class).get("request_uuid");
        return uuid == null ? Placed.failed("Plivo gave no request id") : Placed.ok(String.valueOf(uuid));
    }

    /** The answered call (its CallUUID) fetches the desk XML instead of carrying on with the AI. */
    @Override
    public boolean transfer(String providerCallId, String deskNumberE164, String summary) {
        if (!props.configured() || blank(providerCallId) || blank(deskNumberE164)) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("legs", "aleg");
        body.put("aleg_url", props.url("/api/voice/hooks/plivo/desk"));
        body.put("aleg_method", "POST");
        HttpResponse<String> res = send("POST", "/Call/" + providerCallId + "/", body);
        return res != null && res.statusCode() / 100 == 2;
    }

    @Override
    public boolean hangup(String providerCallId) {
        // Plivo's DELETE .../Call/ without a call id hangs up EVERY call on the account: never send one.
        if (!props.configured() || blank(providerCallId) || !providerCallId.matches("[A-Za-z0-9-]+")) {
            return false;
        }
        HttpResponse<String> res = send("DELETE", "/Call/" + providerCallId + "/", null);
        return res != null && res.statusCode() / 100 == 2;
    }

    private HttpResponse<String> send(String method, String path, Map<String, Object> body) {
        String auth = Base64.getEncoder().encodeToString((props.authId() + ":" + props.authToken())
                .getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(props.apiBase() + "/Account/" + props.authId() + path))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/json");
        req.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        try {
            return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            log.warn("Plivo {} {} failed: {}", method, path, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
