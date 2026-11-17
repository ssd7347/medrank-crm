package com.aicallcrm.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.aicallcrm.crm.voice.Voice.CallStatus;
import com.aicallcrm.crm.voice.Voice.Direction;
import com.jayway.jsonpath.JsonPath;

/**
 * Call recordings (17 Nov 2026): a real call whose opening says it may be recorded is recorded by the engine and kept
 * as a file on the CRM server; only the admin and staff the admin allows can play it, and each play is audited; it
 * goes with the transcript after the retention period (never under 120 days) and when the person is erased; a
 * test-console call, a call whose opening did not say so, or a second copy is never kept.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecordingIntegrationTest {

    private static final String SECRET = "test-only-voice-secret-0123456789-abcdefgh";
    private static final AtomicInteger SEQ = new AtomicInteger();
    /** Not real audio: the CRM checks only that it is an Ogg file, and keeps it byte for byte. */
    private static final byte[] OGG = ("OggS" + "\0".repeat(20) + "pretend opus audio").getBytes(StandardCharsets.ISO_8859_1);

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    VoiceCallRepository calls;
    @Autowired
    CampaignDispatcher dispatcher;
    @Autowired
    VoiceProperties props;

    @Test
    void aRecordedCallIsKeptAndOnlyTheAdminAndAllowedStaffCanPlayIt() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        approve(admin, "Incoming calls");
        String agentEmail = "rec.agent" + SEQ.incrementAndGet() + "@recording.test";
        long agentId = id(mvc.perform(auth(post("/api/users"), admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Ravi Recordwala","email":"%s","role":"AGENT","password":"StaffPass123!"}""".formatted(agentEmail)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.canHearRecordings").value(false)).andReturn());
        String agent = login(agentEmail, "StaffPass123!");
        String phone = phone();
        long contact = id(mvc.perform(auth(post("/api/contacts"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Kavya Recorded\",\"phone\":\"" + phone + "\",\"assignedToId\":" + agentId + "}"))
                .andExpect(status().isCreated()).andReturn());

        // The person rings in; the opening tells them the call may be recorded, so the engine is told to record.
        String call = JsonPath.read(hook("/inbound/lookup", "{\"caller_number\":\"" + phone + "\"}")
                .andReturn().getResponse().getContentAsString(), "$.call_id");
        hook("/live/open", "{\"call_id\":\"" + call + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.say").value(Matchers.containsString("this call may be recorded")))
                .andExpect(jsonPath("$.record").value(true));
        // After the call the engine sends the recording; it is kept once, never replaced.
        hook("/webhooks/call-ended", "{\"call_id\":\"" + call + "\",\"status\":\"completed\",\"duration_sec\":40}");
        // Not an Ogg file: refused.
        upload(call, "RIFF....WAVE".getBytes(StandardCharsets.ISO_8859_1)).andExpect(status().isBadRequest());
        upload(call, OGG).andExpect(status().isOk()).andExpect(jsonPath("$.kept").value(true));
        upload(call, "OggS other".getBytes(StandardCharsets.ISO_8859_1)).andExpect(jsonPath("$.kept").value(false));
        String month = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneId.of("Asia/Kolkata"))
                .format(calls.findById(UUID.fromString(call)).orElseThrow().getCreatedAt());
        Path file = Path.of(props.recordingsDir()).toAbsolutePath().resolve(month).resolve(call + ".ogg");
        assertThat(Files.readAllBytes(file)).isEqualTo(OGG);
        assertThat(jdbc.queryForObject("select recording_ref from voice_call where id = ?", String.class,
                UUID.fromString(call))).isEqualTo("file:" + month + "/" + call + ".ogg");
        // Unsigned: refused.
        mvc.perform(post("/api/voice/hooks/live/recording").contentType(MediaType.APPLICATION_JSON)
                .content("{\"call_id\":\"" + call + "\",\"audio\":\"\"}")).andExpect(status().isUnauthorized());

        // The agent owns the contact and sees the call, but may not play the recording until the admin allows it.
        mvc.perform(auth(get("/api/voice/calls/" + call), agent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingHeld").value(true))
                .andExpect(jsonPath("$.canHearRecordings").value(false))
                .andExpect(jsonPath("$.recordingRef").isEmpty());
        mvc.perform(auth(get("/api/voice/calls/" + call + "/recording"), agent)).andExpect(status().isForbidden());
        allow(admin, agentId, agentEmail, true);
        mvc.perform(auth(get("/api/users"), admin))
                .andExpect(jsonPath("$[?(@.id == " + agentId + ")].canHearRecordings").value(Matchers.contains(true)))
                .andExpect(jsonPath("$[?(@.role == 'SUPER_ADMIN')].canHearRecordings").value(Matchers.contains(true)));
        mvc.perform(auth(get("/api/voice/calls/" + call), agent)).andExpect(jsonPath("$.canHearRecordings").value(true));
        mvc.perform(auth(get("/api/voice/calls/" + call + "/recording"), agent)).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "audio/ogg"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("no-store")))
                .andExpect(content().bytes(OGG));
        mvc.perform(auth(get("/api/voice/calls/" + call + "/recording"), admin)).andExpect(status().isOk());
        // Each play is in the audit log, with who and which call.
        mvc.perform(auth(get("/api/audit").param("action", "RECORDING_PLAYED").param("by", String.valueOf(agentId)), admin))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].link").value("/voice/calls/" + call));
        mvc.perform(auth(get("/api/audit").param("action", "USER_UPDATED").param("size", "1"), admin))
                .andExpect(jsonPath("$.items[0].details").value(Matchers.endsWith("recordings=true")));
        // Allowed staff still hear only calls they can see: another agent's contact's call is not theirs.
        String otherEmail = "rec.other" + SEQ.incrementAndGet() + "@recording.test";
        long otherId = id(mvc.perform(auth(post("/api/users"), admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Other Agent","email":"%s","role":"AGENT","password":"StaffPass123!","canHearRecordings":true}"""
                        .formatted(otherEmail))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.canHearRecordings").value(true)).andReturn());
        mvc.perform(auth(get("/api/voice/calls/" + call + "/recording"), login(otherEmail, "StaffPass123!")))
                .andExpect(status().isNotFound());
        // Taken away: at once, without signing in again.
        allow(admin, agentId, agentEmail, false);
        mvc.perform(auth(get("/api/voice/calls/" + call + "/recording"), agent)).andExpect(status().isForbidden());
        assertThat(otherId).isPositive();

        // Erasing the person deletes the file.
        mvc.perform(auth(post("/api/contacts/" + contact + "/erase"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmPhone\":\"" + phone + "\",\"reason\":\"Recording test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recordingsDeleted").value(1));
        assertThat(file).doesNotExist();
        mvc.perform(auth(get("/api/voice/calls/" + call + "/recording"), admin)).andExpect(status().isNotFound());
        // And a recording that arrives after the erasure is not kept.
        upload(call, OGG).andExpect(jsonPath("$.kept").value(false));
    }

    @Test
    void callsNotToBeRecordedAreNeverKept() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        approve(admin, "Incoming calls");
        String phone = phone();
        long contact = id(mvc.perform(auth(post("/api/contacts"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Arjun Unrecorded\",\"phone\":\"" + phone + "\"}")).andExpect(status().isCreated())
                .andReturn());

        // A test-console call.
        String test = JsonPath.read(mvc.perform(auth(post("/api/voice/test-calls"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactId\":" + contact + ",\"scriptName\":\"Incoming calls\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.callId");
        upload(test, OGG).andExpect(status().isOk()).andExpect(jsonPath("$.kept").value(false));

        // A live call whose script's opening does not say it may be recorded (approved before 17 Nov 2026).
        jdbc.update("insert into voice_script (name, kind, version, model, system_prompt, opening_line,"
                + " approved_at, active, created_at) values ('Quiet opening', 'INBOUND', 1, 'm', 'p', 'Hello, you have"
                + " reached {{company_name}}. I am {{agent_name}}, an AI assistant. How can I help you today?',"
                + " current_timestamp, false, current_timestamp)");
        Long quiet = jdbc.queryForObject("select id from voice_script where name = 'Quiet opening'", Long.class);
        VoiceCall unannounced = new VoiceCall(Direction.INBOUND, "Quiet opening", contact, phone, "PLIVO");
        unannounced.setScriptId(quiet);
        unannounced.setStatus(CallStatus.CONNECTED);
        unannounced.setRecordingAllowed(RecordingService.announces("Hello, you have reached {{company_name}}."));
        calls.save(unannounced);
        hook("/live/open", "{\"call_id\":\"" + unannounced.getId() + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.record").value(false));
        upload(unannounced.getId().toString(), OGG).andExpect(jsonPath("$.kept").value(false));
        // Even if a call were marked for recording, the engine records only when the opening as said told them.
        VoiceCall marked = new VoiceCall(Direction.INBOUND, "Quiet opening", contact, phone, "PLIVO");
        marked.setScriptId(quiet);
        marked.setStatus(CallStatus.CONNECTED);
        marked.setRecordingAllowed(true);
        calls.save(marked);
        hook("/live/open", "{\"call_id\":\"" + marked.getId() + "\"}").andExpect(jsonPath("$.record").value(false));

        // A recorded call older than the retention period: nothing new is kept, and the old file goes with the transcript.
        String old = JsonPath.read(hook("/inbound/lookup", "{\"caller_number\":\"" + phone + "\"}")
                .andReturn().getResponse().getContentAsString(), "$.call_id");
        hook("/live/open", "{\"call_id\":\"" + old + "\"}").andExpect(jsonPath("$.record").value(true));
        upload(old, OGG).andExpect(jsonPath("$.kept").value(true));
        String ref = jdbc.queryForObject("select recording_ref from voice_call where id = ?", String.class, UUID.fromString(old));
        Path file = Path.of(props.recordingsDir()).toAbsolutePath().resolve(ref.substring("file:".length()));
        assertThat(file).exists();
        jdbc.update("update voice_call set created_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(props.retentionDays() + 1))),
                UUID.fromString(old));
        assertThat(dispatcher.purgeOldConversations()).isPositive();
        assertThat(file).doesNotExist();
        assertThat(jdbc.queryForObject("select recording_ref from voice_call where id = ?", String.class,
                UUID.fromString(old))).isNull();
        upload(old, OGG).andExpect(jsonPath("$.kept").value(false));
    }

    /** The recording is played only from inside the folder: a reference that leads out of it is not a recording. */
    @Test
    void onlyFilesInsideTheFolderArePlayed(@Autowired RecordingService recordings) {
        assertThat(recordings.file("file:2026-11/" + UUID.randomUUID() + ".ogg")).isNotNull();
        for (String bad : List.of("file:../../etc/passwd", "file:2026-11/../../x.ogg", "file:/etc/passwd",
                "https://rec.example/a.mp3", "file:2026-11/" + UUID.randomUUID() + ".wav")) {
            assertThat(recordings.file(bad)).as(bad).isNull();
        }
    }

    @Test
    void recordingsAreKeptAtLeast120Days() {
        assertThat(props.retentionDays()).isGreaterThanOrEqualTo(120);
        assertThatThrownBy(() -> new VoiceProperties(true, "simulated", "", 2500, LocalTime.of(9, 0), LocalTime.of(18, 0),
                2, 120, 24, 119, "m", "m", "", LocalTime.of(9, 30), LocalTime.of(18, 30), BigDecimal.TEN,
                BigDecimal.valueOf(60000), "data/recordings"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 120 days");
        new VoiceProperties(true, "simulated", "", 2500, LocalTime.of(9, 0), LocalTime.of(18, 0), 2, 120, 24, 120, "m",
                "m", "", LocalTime.of(9, 30), LocalTime.of(18, 30), BigDecimal.TEN, BigDecimal.valueOf(60000),
                "data/recordings");
    }

    private void allow(String admin, long userId, String email, boolean allowed) throws Exception {
        mvc.perform(auth(put("/api/users/" + userId), admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Ravi Recordwala","role":"AGENT","active":true,"canHearRecordings":%s}""".formatted(allowed)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canHearRecordings").value(allowed));
        assertThat(email).isNotBlank();
    }

    private ResultActions upload(String call, byte[] audio) throws Exception {
        return hook("/live/recording", "{\"call_id\":\"" + call + "\",\"audio\":\""
                + Base64.getEncoder().encodeToString(audio) + "\"}");
    }

    private void approve(String admin, String scriptName) throws Exception {
        List<Integer> ids = JsonPath.read(mvc.perform(auth(get("/api/voice/scripts"), admin)).andReturn().getResponse()
                .getContentAsString(), "$[?(@.name == '" + scriptName + "')].id");
        mvc.perform(auth(post("/api/voice/scripts/" + ids.get(0) + "/approve"), admin)).andExpect(status().isOk());
    }

    private ResultActions hook(String path, String body) throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        return mvc.perform(post("/api/voice/hooks" + path).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Voice-Timestamp", ts).header("X-Voice-Signature", VoiceSignatures.sign(SECRET, ts, body)));
    }

    private String login(String email, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String phone() {
        return "68" + String.format("%08d", 52000 + SEQ.incrementAndGet());
    }

    private static long id(MvcResult r) throws Exception {
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
