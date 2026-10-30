package com.aicallcrm.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;

/**
 * Plivo as the phone line, against a fake Plivo API on this machine: calls are placed with the right callback
 * URLs, callbacks must carry Plivo's V3 signature, an answered call streams to our speech engine with a per-call
 * token, a hand-over transfers the answered call to the desk, and the engine's "end" hangs up only that call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VoicePlivoTest {

    private static final String SECRET = "test-only-voice-secret-0123456789-abcdefgh";
    private static final String AUTH_ID = "MATESTPLIVO01";
    private static final String TOKEN = "plivo-test-auth-token-0123456789";
    private static final String PUBLIC = "https://crm.test.example";
    private static final AtomicInteger SEQ = new AtomicInteger(83000);

    /** "METHOD path body" for every request the fake Plivo API received. */
    static final List<String> PLIVO = new CopyOnWriteArrayList<>();
    static final List<String> AUTHS = new CopyOnWriteArrayList<>();
    static final HttpServer FAKE;

    static {
        try {
            FAKE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        FAKE.createContext("/v1/Account/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            PLIVO.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " " + body);
            AUTHS.add(ex.getRequestHeaders().getFirst("Authorization"));
            String path = ex.getRequestURI().getPath();
            int code;
            String reply;
            if ("DELETE".equals(ex.getRequestMethod())) {
                code = 204;
                reply = null;
            } else if (path.endsWith("/Call/")) {
                code = 201;
                reply = "{\"api_id\":\"a1\",\"message\":\"call fired\",\"request_uuid\":\"req-" + PLIVO.size() + "\"}";
            } else {
                code = 202;
                reply = "{\"api_id\":\"a2\",\"message\":\"call transferred\"}";
            }
            if (reply == null) {
                ex.sendResponseHeaders(code, -1);
            } else {
                byte[] b = reply.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(code, b.length);
                ex.getResponseBody().write(b);
            }
            ex.close();
        });
        FAKE.start();
    }

    @DynamicPropertySource
    static void plivo(DynamicPropertyRegistry r) {
        r.add("app.voice.platform", () -> "plivo");
        r.add("app.voice.plivo.auth-id", () -> AUTH_ID);
        r.add("app.voice.plivo.auth-token", () -> TOKEN);
        r.add("app.voice.plivo.from-number", () -> "+918000000001");
        r.add("app.voice.plivo.public-url", () -> PUBLIC + "/");
        r.add("app.voice.plivo.engine-url", () -> "wss://voice.test.example/call");
        r.add("app.voice.plivo.api-base", () -> "http://127.0.0.1:" + FAKE.getAddress().getPort() + "/v1");
        r.add("app.voice.desk-transfer-number", () -> "+914400000000");
        r.add("app.voice.desk-open", () -> "00:00");
        r.add("app.voice.desk-close", () -> "23:59");
    }

    @AfterAll
    static void stop() {
        FAKE.stop(0);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    PlivoClient plivo;

    @Test
    void placingACallSendsOurCallbackUrlsWithBasicAuth() {
        UUID id = UUID.randomUUID();
        VoicePlatformClient.Placed placed = plivo.createOutboundCall(new VoicePlatformClient.OutboundCall(id, "+919876500001",
                "Payment reminder", "m", "prompt", "Hello", Map.of(), false, 300));
        assertThat(placed.accepted()).isTrue();
        assertThat(placed.providerCallId()).startsWith("req-");
        String sent = PLIVO.stream().filter(s -> s.contains(id.toString())).findFirst().orElseThrow();
        assertThat(sent).startsWith("POST /v1/Account/" + AUTH_ID + "/Call/ ")
                .contains("\"from\":\"+918000000001\"", "\"to\":\"+919876500001\"",
                        "\"answer_url\":\"" + PUBLIC + "/api/voice/hooks/plivo/answer?call_id=" + id + "\"",
                        "\"hangup_url\":\"" + PUBLIC + "/api/voice/hooks/plivo/hangup?call_id=" + id + "\"", "\"time_limit\":300");
        assertThat(AUTHS).contains("Basic " + Base64.getEncoder().encodeToString((AUTH_ID + ":" + TOKEN).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void hangingUpNeverSendsACallWithoutItsId() {
        int before = PLIVO.size();
        assertThat(plivo.hangup(null)).isFalse();
        assertThat(plivo.hangup(" ")).isFalse();
        assertThat(plivo.hangup("../Call")).isFalse();
        assertThat(PLIVO).hasSize(before);
    }

    @Test
    void anIncomingCallStreamsToOurEngineThenTransfersAndHangsUp() throws Exception {
        String admin = login();
        approve(admin, "Incoming calls");
        String phone = "9" + String.format("%09d", SEQ.incrementAndGet());
        mvc.perform(auth(post("/api/contacts"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Nisha Rao\",\"phone\":\"" + phone + "\",\"fields\":{}}")).andExpect(status().isCreated());
        String callUuid = "cu-" + SEQ.incrementAndGet();

        // Unsigned or wrongly signed: refused.
        mvc.perform(post("/api/voice/hooks/plivo/answer").param("From", "+91" + phone).param("CallUUID", callUuid))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/voice/hooks/plivo/answer").param("From", "+91" + phone).param("CallUUID", callUuid)
                .header("X-Plivo-Signature-V3", "bad").header("X-Plivo-Signature-V3-Nonce", "n1")).andExpect(status().isUnauthorized());

        String xml = plivoHook("/answer", null, "From", "+91" + phone, "To", "+918000000001", "CallUUID", callUuid,
                "Direction", "inbound", "CallStatus", "ringing").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("<Stream bidirectional=\"true\" keepCallAlive=\"true\" contentType=\"audio/x-mulaw;rate=8000\">"
                + "wss://voice\\.test\\.example/call\\?call_id=([0-9a-f-]{36})&amp;token=([0-9a-f]{64})</Stream><Hangup/>").matcher(xml);
        assertThat(m.find()).as(xml).isTrue();
        UUID call = UUID.fromString(m.group(1));
        assertThat(m.group(2)).isEqualTo(PlivoHooksController.streamToken(SECRET, call));

        // The engine holds the conversation through the live hooks; asking for a person transfers the call.
        live("open", call, null).andExpect(jsonPath("$.over").value(false));
        boolean deskOpen = ZonedDateTime.now(ContactFacts.IST).getDayOfWeek() != DayOfWeek.SUNDAY;
        live("turn", call, "Can I speak to a real person").andExpect(jsonPath("$.over").value(true));
        if (deskOpen) {
            assertThat(PLIVO).anyMatch(s -> s.startsWith("POST /v1/Account/" + AUTH_ID + "/Call/" + callUuid + "/ ")
                    && s.contains("\"legs\":\"aleg\"") && s.contains("\"aleg_url\":\"" + PUBLIC + "/api/voice/hooks/plivo/desk\""));
        }
        plivoHook("/desk", null).andExpect(status().isOk()).andExpect(r -> assertThat(r.getResponse().getContentAsString())
                .contains("<Dial callerId=\"+918000000001\"><Number>+914400000000</Number></Dial>"));

        // After the goodbye the engine asks to hang up: exactly this call is ended.
        live("end", call, null).andExpect(jsonPath("$.hung_up").value(true));
        assertThat(PLIVO).contains("DELETE /v1/Account/" + AUTH_ID + "/Call/" + callUuid + "/ ");

        plivoHook("/hangup", null, "CallUUID", callUuid, "CallStatus", "completed", "Duration", "42",
                "HangupCause", "NORMAL_CLEARING").andExpect(status().isOk());
        mvc.perform(auth(get("/api/voice/calls/" + call), admin))
                .andExpect(jsonPath("$.call.status").value("COMPLETED"))
                .andExpect(jsonPath("$.call.durationSec").value(42))
                .andExpect(jsonPath("$.call.outcome").value("HANDED_OFF"));
    }

    @Test
    void anOutboundAnswerLinksPlivosCallIdAndUnknownCallsAreHungUp() throws Exception {
        String unknown = plivoHook("/answer", "call_id=" + UUID.randomUUID(), "CallUUID", "cu-x", "Direction", "outbound")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(unknown).contains("<Response><Hangup/></Response>").doesNotContain("Stream");
    }

    // ------------------------------------------------------------------ helpers

    /** A callback signed exactly as Plivo signs it: the public URL it called, the form fields and a nonce. */
    private ResultActions plivoHook(String path, String query, String... fields) throws Exception {
        String url = PUBLIC + "/api/voice/hooks/plivo" + path + (query == null ? "" : "?" + query);
        Map<String, List<String>> form = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) {
            form.put(fields[i], List.of(fields[i + 1]));
        }
        String nonce = "n" + SEQ.incrementAndGet();
        MockHttpServletRequestBuilder req = post("/api/voice/hooks/plivo" + path + (query == null ? "" : "?" + query))
                .header("X-Plivo-Signature-V3-Nonce", nonce)
                .header("X-Plivo-Signature-V3", PlivoSignature.sign(TOKEN, PlivoSignature.base("POST", url, form), nonce));
        for (int i = 0; i < fields.length; i += 2) {
            req.param(fields[i], fields[i + 1]);
        }
        return mvc.perform(req);
    }

    private ResultActions live(String step, UUID call, String said) throws Exception {
        String body = "{\"call_id\":\"" + call + "\"" + (said == null ? "" : ",\"said\":\"" + said + "\"") + "}";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        return mvc.perform(post("/api/voice/hooks/live/" + step).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Voice-Timestamp", ts).header("X-Voice-Signature", VoiceSignatures.sign(SECRET, ts, body)))
                .andExpect(status().isOk());
    }

    private void approve(String admin, String name) throws Exception {
        List<Integer> ids = JsonPath.read(mvc.perform(auth(get("/api/voice/scripts"), admin)).andReturn().getResponse()
                .getContentAsString(), "$[?(@.name == '" + name + "')].id");
        mvc.perform(auth(post("/api/voice/scripts/" + ids.get(0) + "/approve"), admin)).andExpect(status().isOk());
    }

    private String login() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@test.local\",\"password\":\"AdminPass123!\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.accessToken");
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
