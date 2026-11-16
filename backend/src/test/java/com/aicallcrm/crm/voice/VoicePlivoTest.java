package com.aicallcrm.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * token, a hand-over transfers the answered call to the desk once the caller has heard "I am connecting you" (the
 * engine's "end"), and otherwise "end" hangs up only that call. A transfer that fails, or a caller who hangs up before
 * it, gets an urgent call-back.
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
            } else if (path.contains("refuse")) {
                code = 400;
                reply = "{\"api_id\":\"a3\",\"error\":\"call not in progress\"}";
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

    /** Monday 16 Nov 2026, 11:00 in India: the desk is open whatever day the tests run (it is shut on Sundays). */
    @TestConfiguration
    static class Weekday {
        @Bean
        @Primary
        Clock weekdayClock() {
            return Clock.fixed(Instant.parse("2026-11-16T05:30:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    PlivoClient plivo;
    @Autowired
    JdbcTemplate jdbc;

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
    void anIncomingCallStreamsToOurEngineAndIsTransferredOnlyAfterHearingSo() throws Exception {
        String admin = login();
        String callUuid = "cu-" + SEQ.incrementAndGet();
        UUID call = incomingCallAskingForAPerson(admin, callUuid);

        // Not yet: Plivo would move the call at once and cut off "I am connecting you".
        assertThat(PLIVO).noneMatch(s -> s.startsWith("POST /v1/Account/" + AUTH_ID + "/Call/" + callUuid + "/ "));
        plivoHook("/desk", null).andExpect(status().isOk()).andExpect(r -> assertThat(r.getResponse().getContentAsString())
                .contains("<Dial callerId=\"+918000000001\"><Number>+914400000000</Number></Dial>"));

        // The engine played the line and asks to end: the call goes to the desk instead of being hung up.
        live("end", call, null).andExpect(jsonPath("$.transferred").value(true)).andExpect(jsonPath("$.hung_up").value(false));
        assertThat(PLIVO).anyMatch(s -> s.startsWith("POST /v1/Account/" + AUTH_ID + "/Call/" + callUuid + "/ ")
                && s.contains("\"legs\":\"aleg\"") && s.contains("\"aleg_url\":\"" + PUBLIC + "/api/voice/hooks/plivo/desk\""));
        assertThat(PLIVO).doesNotContain("DELETE /v1/Account/" + AUTH_ID + "/Call/" + callUuid + "/ ");

        // The desk talks to them; when that call ends nobody needs a call-back.
        plivoHook("/hangup", null, "CallUUID", callUuid, "CallStatus", "completed", "Duration", "42",
                "HangupCause", "NORMAL_CLEARING").andExpect(status().isOk());
        mvc.perform(auth(get("/api/voice/calls/" + call), admin))
                .andExpect(jsonPath("$.call.status").value("COMPLETED"))
                .andExpect(jsonPath("$.call.durationSec").value(42))
                .andExpect(jsonPath("$.call.outcome").value("HANDED_OFF"));
        assertThat(callbacks(call)).isEmpty();
    }

    @Test
    void aTransferThatFailsBooksAnUrgentCallBackAndHangsUp() throws Exception {
        String admin = login();
        String callUuid = "cu-refuse-" + SEQ.incrementAndGet();
        UUID call = incomingCallAskingForAPerson(admin, callUuid);

        live("end", call, null).andExpect(jsonPath("$.transferred").value(false)).andExpect(jsonPath("$.hung_up").value(true));
        assertThat(PLIVO).anyMatch(s -> s.startsWith("POST /v1/Account/" + AUTH_ID + "/Call/" + callUuid + "/ "));
        assertThat(PLIVO).contains("DELETE /v1/Account/" + AUTH_ID + "/Call/" + callUuid + "/ ");
        assertThat(callbacks(call)).containsExactly("CALLER_REQUEST URGENT");

        // Plivo then reports the hang-up: still one call-back.
        plivoHook("/hangup", null, "CallUUID", callUuid, "CallStatus", "completed", "Duration", "20",
                "HangupCause", "NORMAL_CLEARING").andExpect(status().isOk());
        assertThat(callbacks(call)).containsExactly("CALLER_REQUEST URGENT");
    }

    @Test
    void aCallerWhoHangsUpBeforeTheTransferGetsAnUrgentCallBack() throws Exception {
        String admin = login();
        String callUuid = "cu-" + SEQ.incrementAndGet();
        UUID call = incomingCallAskingForAPerson(admin, callUuid);

        plivoHook("/hangup", null, "CallUUID", callUuid, "CallStatus", "completed", "Duration", "15",
                "HangupCause", "NORMAL_CLEARING").andExpect(status().isOk());
        assertThat(callbacks(call)).containsExactly("CALLER_REQUEST URGENT");

        // An "end" arriving late transfers nothing and books nothing more.
        live("end", call, null).andExpect(jsonPath("$.transferred").value(false));
        assertThat(PLIVO).noneMatch(s -> s.startsWith("POST /v1/Account/" + AUTH_ID + "/Call/" + callUuid + "/ "));
        assertThat(callbacks(call)).containsExactly("CALLER_REQUEST URGENT");
    }

    @Test
    void aCallerIsPromisedTheCallBackTimeTheyAskedFor() throws Exception {
        String admin = login();
        UUID call = incomingCall(admin, "cu-" + SEQ.incrementAndGet());
        // Rehearsal of 16 Nov: asked for 4 pm at 11 in the morning, and was told "by 1:00 PM" (then 9:05 PM).
        String said = JsonPath.read(live("turn", call, "Please call me back at 4 pm").andExpect(jsonPath("$.over").value(true))
                .andReturn().getResponse().getContentAsString(), "$.say");
        assertThat(said).contains("A member of our team will call you back on Monday 16 November at 4:00 PM.");
        assertThat(jdbc.queryForObject("select due_at from callback_request where voice_call_id = ?", Instant.class, call))
                .isEqualTo(Instant.parse("2026-11-16T10:30:00Z"));
        assertThat(jdbc.queryForObject("select preferred_time_text from callback_request where voice_call_id = ?",
                String.class, call)).isEqualTo("Please call me back at 4 pm");
        assertThat(jdbc.queryForList("select title from notification where link = '/voice/callbacks' and title like ?", String.class,
                "%Nisha Rao%")).contains("Call back on Monday 16 November at 4:00 PM: Nisha Rao");
    }

    @Test
    void aCallerWhoChangesTheTimeIsPromisedTheNewOneAndStaffAreTold() throws Exception {
        String admin = login();
        UUID call = incomingCall(admin, "cu-" + SEQ.incrementAndGet());
        String due = "select due_at from callback_request where voice_call_id = ?";
        tool(call, "schedule_callback", "{\"preferred_time_text\":\"4 pm\"}")
                .andExpect(jsonPath("$.speak_hint").value("A member of our team will call you back on Monday 16 November at 4:00 PM."));

        // Said again, vaguer: the 4 pm booking stands.
        tool(call, "schedule_callback", "{\"preferred_time_text\":\"this afternoon\"}")
                .andExpect(jsonPath("$.speak_hint").value("A member of our team will call you back on Monday 16 November at 4:00 PM."));
        assertThat(jdbc.queryForObject(due, Instant.class, call)).isEqualTo(Instant.parse("2026-11-16T10:30:00Z"));

        // "Actually, make it 5 pm": the task moves, and staff are told what it was.
        tool(call, "schedule_callback", "{\"preferred_time_text\":\"actually, make it 5 pm\"}")
                .andExpect(jsonPath("$.speak_hint").value("A member of our team will call you back on Monday 16 November at 5:00 PM."))
                .andExpect(jsonPath("$.data.confirmed_slot").value("Monday 16 November, 5:00 PM"));
        assertThat(jdbc.queryForObject(due, Instant.class, call)).isEqualTo(Instant.parse("2026-11-16T11:30:00Z"));
        assertThat(jdbc.queryForObject("select preferred_time_text from callback_request where voice_call_id = ?",
                String.class, call)).isEqualTo("actually, make it 5 pm");
        assertThat(jdbc.queryForList("select title || ' | ' || body from notification where link = '/voice/callbacks' "
                + "and title like 'Call-back time changed%'", String.class))
                .contains("Call-back time changed, call back on Monday 16 November at 5:00 PM: Nisha Rao | Asked for: "
                        + "actually, make it 5 pm. It was on Monday 16 November at 4:00 PM.");

        // Nothing clear, or a time that cannot be kept: the 5 pm booking, with the desk hours first for the latter.
        tool(call, "schedule_callback", "{\"preferred_time_text\":\"soon\"}")
                .andExpect(jsonPath("$.speak_hint").value("A member of our team will call you back on Monday 16 November at 5:00 PM."));
        tool(call, "schedule_callback", "{\"preferred_time_text\":\"Sunday at 4\"}")
                .andExpect(jsonPath("$.speak_hint").value("Our team makes calls between 12:00 AM and 11:59 PM, Monday to "
                        + "Saturday, so a member of our team will call you back on Monday 16 November at 5:00 PM."));
        assertThat(jdbc.queryForObject(due, Instant.class, call)).isEqualTo(Instant.parse("2026-11-16T11:30:00Z"));
        assertThat(callbacks(call)).containsExactly("CALLBACK_REQUESTED NORMAL");
    }

    @Test
    void aCallerWhoAsksForNoCallsAtAllHasTheirCallBackCancelled() throws Exception {
        String admin = login();
        UUID call = incomingCall(admin, "cu-" + SEQ.incrementAndGet());
        tool(call, "schedule_callback", "{\"preferred_time_text\":\"4 pm\"}");
        tool(call, "opt_out", "{\"scope\":\"ALL_CALLS\"}").andExpect(jsonPath("$.ok").value(true));
        assertThat(jdbc.queryForObject("select status || ' | ' || done_note from callback_request where voice_call_id = ?",
                String.class, call)).isEqualTo("DONE | " + HandoffService.CANCELLED_NOTE);
        assertThat(jdbc.queryForList("select body from notification where title like 'Asked not to be called at all%' "
                + "and dedup_key = ?", String.class, "VINF:" + call)).isNotEmpty()
                .allMatch(b -> b.endsWith("Their open call-back was cancelled."));
    }

    @Test
    void aCallerWhoStopsOnlyAiCallsKeepsTheirCallBackWithAWarning() throws Exception {
        String admin = login();
        UUID call = incomingCall(admin, "cu-" + SEQ.incrementAndGet());
        tool(call, "schedule_callback", "{\"preferred_time_text\":\"4 pm\",\"reason\":\"Wants to talk about the plan.\"}");
        tool(call, "opt_out", "{\"scope\":\"AI_CALLS\"}").andExpect(jsonPath("$.ok").value(true));
        tool(call, "opt_out", "{\"scope\":\"AI_CALLS\"}").andExpect(jsonPath("$.ok").value(true));
        assertThat(jdbc.queryForObject("select status from callback_request where voice_call_id = ?", String.class, call))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select summary from callback_request where voice_call_id = ?", String.class, call))
                .isEqualTo(HandoffService.AI_ONLY_NOTE + "\n\nWants to talk about the plan.");
        assertThat(jdbc.queryForList("select title || ' | ' || body from notification where dedup_key = ?", String.class,
                "VOPT:" + call)).isNotEmpty().allMatch(t -> t.equals("Check before calling back: Nisha Rao | Nisha Rao "
                + "asked on an AI call to stop AI calls and has 1 open call-back. They did not say no to a call from staff, "
                + "but read the transcript first."));
    }

    @Test
    void anOptOutHeardOnlyAtTheHangUpFlagsTheCallBackToo() throws Exception {
        String admin = login();
        String callUuid = "cu-" + SEQ.incrementAndGet();
        UUID call = incomingCall(admin, callUuid);
        tool(call, "schedule_callback", "{\"preferred_time_text\":\"4 pm\"}");
        // The stand-in brain does not catch these words; the CRM does when the call ends (VoiceCallService.optOutIfAsked).
        live("turn", call, "Please take me off your list.");
        plivoHook("/hangup", null, "CallUUID", callUuid, "CallStatus", "completed", "Duration", "30",
                "HangupCause", "NORMAL_CLEARING").andExpect(status().isOk());
        mvc.perform(auth(get("/api/voice/calls/" + call), admin)).andExpect(jsonPath("$.call.outcome").value("CALLBACK_REQUESTED"));
        assertThat(jdbc.queryForObject("select status || ' | ' || summary from callback_request where voice_call_id = ?",
                String.class, call)).startsWith("OPEN | " + HandoffService.AI_ONLY_NOTE);
    }

    @Test
    void aCallerWhoTakesBackTheirCallBackHasItFlagged() throws Exception {
        String admin = login();
        String callUuid = "cu-" + SEQ.incrementAndGet();
        UUID call = incomingCall(admin, callUuid);
        tool(call, "schedule_callback", "{\"preferred_time_text\":\"4 pm\",\"reason\":\"Wants to talk about the plan.\"}");
        live("turn", call, "Cancel the callback please, I will pay online.");
        hangUp(callUuid);
        assertThat(jdbc.queryForList("select status || ' | ' || summary from callback_request where voice_call_id = ?",
                String.class, call)).containsExactly("OPEN | " + HandoffService.WITHDRAWN_NOTE + "\n\nWants to talk about the plan.");
        assertThat(jdbc.queryForList("select title || ' | ' || body from notification where dedup_key = ?", String.class,
                "VWD:" + call)).isNotEmpty().allMatch(t -> t.equals("Call-back may not be needed: Nisha Rao | Nisha Rao said "
                + "on an AI call: \"cancel the callback please, i will pay online.\" Read the transcript before calling; "
                + "if they do not need the call-back, mark it done."));
    }

    @Test
    void aCallBackBookedOnANoOrTakenBackInTheLastWordsIsFlaggedToo() throws Exception {
        String admin = login();
        // The caller says no, and the model books a call-back anyway.
        String firstUuid = "cu-" + SEQ.incrementAndGet();
        UUID first = incomingCall(admin, firstUuid);
        live("turn", first, "No need to call me back, thanks.");
        tool(first, "schedule_callback", "{\"preferred_time_text\":\"later\"}");
        assertThat(jdbc.queryForObject("select summary from callback_request where voice_call_id = ?", String.class, first))
                .doesNotContain(HandoffService.WITHDRAWN_NOTE);
        hangUp(firstUuid);
        assertThat(jdbc.queryForObject("select status || ' | ' || summary from callback_request where voice_call_id = ?",
                String.class, first)).startsWith("OPEN | " + HandoffService.WITHDRAWN_NOTE);
        // Said only after the hang-up, in the last words the engine sends.
        String secondUuid = "cu-" + SEQ.incrementAndGet();
        UUID second = incomingCall(admin, secondUuid);
        tool(second, "schedule_callback", "{\"preferred_time_text\":\"4 pm\"}");
        hangUp(secondUuid);
        assertThat(jdbc.queryForObject("select summary from callback_request where voice_call_id = ?", String.class, second))
                .doesNotContain(HandoffService.WITHDRAWN_NOTE);
        live("last", second, "Oh, no need to call me back.");
        assertThat(jdbc.queryForObject("select summary from callback_request where voice_call_id = ?", String.class, second))
                .startsWith(HandoffService.WITHDRAWN_NOTE);
    }

    @Test
    void aCallerWhoMovesTheirCallBackIsNotFlagged() throws Exception {
        String admin = login();
        String callUuid = "cu-" + SEQ.incrementAndGet();
        UUID call = incomingCall(admin, callUuid);
        tool(call, "schedule_callback", "{\"preferred_time_text\":\"4 pm\"}");
        live("turn", call, "No need to call me back today, call me tomorrow.");
        hangUp(callUuid);
        assertThat(jdbc.queryForList("select status || ' | ' || summary from callback_request where voice_call_id = ?",
                String.class, call)).singleElement().asString().startsWith("OPEN").doesNotContain(HandoffService.WITHDRAWN_NOTE);
        assertThat(jdbc.queryForList("select id from notification where dedup_key = ?", Long.class, "VWD:" + call)).isEmpty();
    }

    @Test
    void aNoToTheAgentsOfferLeavesACallBackFromAnotherCallAlone() throws Exception {
        String admin = login();
        String firstUuid = "cu-" + SEQ.incrementAndGet();
        UUID first = incomingCall(admin, firstUuid);
        tool(first, "schedule_callback", "{\"preferred_time_text\":\"4 pm\",\"reason\":\"Disputes the charge.\"}");
        hangUp(firstUuid);
        String phone = jdbc.queryForObject("select phone from voice_call where id = ?", String.class, first);
        // Rings in again; asked whether they want someone to call them back, they say no.
        String secondUuid = "cu-" + SEQ.incrementAndGet();
        UUID second = ringsIn(phone.replaceFirst("^\\+91", ""), secondUuid);
        live("turn", second, "No need to call me back, thanks.");
        hangUp(secondUuid);
        assertThat(jdbc.queryForObject("select count(*) from callback_request where voice_call_id = ?", Integer.class, second)).isZero();
        assertThat(jdbc.queryForObject("select status || ' | ' || summary from callback_request where voice_call_id = ?",
                String.class, first)).isEqualTo("OPEN | Disputes the charge.");
        assertThat(jdbc.queryForList("select id from notification where dedup_key = ?", Long.class, "VWD:" + second)).isEmpty();
    }

    @Test
    void anOutboundAnswerLinksPlivosCallIdAndUnknownCallsAreHungUp() throws Exception {
        String unknown = plivoHook("/answer", "call_id=" + UUID.randomUUID(), "CallUUID", "cu-x", "Direction", "outbound")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(unknown).contains("<Response><Hangup/></Response>").doesNotContain("Stream");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A known contact rings in, the call streams to our engine (checking the stream URL and its token), and they ask
     * for a person: the agent says it is connecting them and the call is over for the engine. Returns our call id.
     */
    private UUID incomingCallAskingForAPerson(String admin, String callUuid) throws Exception {
        UUID call = incomingCall(admin, callUuid);
        String said = JsonPath.read(live("turn", call, "Can I speak to a real person").andExpect(jsonPath("$.over").value(true))
                .andReturn().getResponse().getContentAsString(), "$.say");
        assertThat(said).contains("I am connecting you to a member of our team now");
        assertThat(callbacks(call)).isEmpty();
        return call;
    }

    /** Nisha Rao rings in and the call streams to our engine, which opens it. Returns our call id. */
    private UUID incomingCall(String admin, String callUuid) throws Exception {
        approve(admin, "Incoming calls");
        String phone = "9" + String.format("%09d", SEQ.incrementAndGet());
        mvc.perform(auth(post("/api/contacts"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Nisha Rao\",\"phone\":\"" + phone + "\",\"fields\":{}}")).andExpect(status().isCreated());
        return ringsIn(phone, callUuid);
    }

    /** The contact with this phone number rings in and the call streams to our engine, which opens it. */
    private UUID ringsIn(String phone, String callUuid) throws Exception {

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

        live("open", call, null).andExpect(jsonPath("$.over").value(false));
        return call;
    }

    /** "REASON PRIORITY" of each call-back task booked for the call. */
    private List<String> callbacks(UUID call) {
        return jdbc.queryForList("select reason || ' ' || priority from callback_request where voice_call_id = ? order by id",
                String.class, call);
    }

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

    /** Plivo reports that the call ended normally. */
    private void hangUp(String callUuid) throws Exception {
        plivoHook("/hangup", null, "CallUUID", callUuid, "CallStatus", "completed", "Duration", "30",
                "HangupCause", "NORMAL_CLEARING").andExpect(status().isOk());
    }

    private ResultActions live(String step, UUID call, String said) throws Exception {
        String body = "{\"call_id\":\"" + call + "\"" + (said == null ? "" : ",\"said\":\"" + said + "\"") + "}";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        return mvc.perform(post("/api/voice/hooks/live/" + step).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Voice-Timestamp", ts).header("X-Voice-Signature", VoiceSignatures.sign(SECRET, ts, body)))
                .andExpect(status().isOk());
    }

    private ResultActions tool(UUID call, String tool, String args) throws Exception {
        String body = "{\"call_id\":\"" + call + "\",\"args\":" + args + "}";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        return mvc.perform(post("/api/voice/hooks/tools/" + tool).contentType(MediaType.APPLICATION_JSON).content(body)
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
