package com.mbbscrm.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.mbbscrm.crm.voice.Voice.DndResult;

/**
 * Proves the "plug a provider in" seam: with a fake voice platform and a fake do-not-disturb register
 * installed as beans, and nothing else changed, campaigns really dial, webhooks move calls along, blocked
 * numbers are never dialled, attempts are counted correctly and an open desk gets a live transfer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.voice.platform=fake", "app.voice.desk-transfer-number=+914400000000",
        "app.voice.desk-open=00:00", "app.voice.desk-close=23:59"})
@Import(VoiceLivePlatformTest.Fakes.class)
class VoiceLivePlatformTest {

    private static final AtomicInteger SEQ = new AtomicInteger(71000);
    private static final String SECRET = "test-only-voice-secret-0123456789-abcdefgh";
    static final Map<String, VoicePlatformClient.OutboundCall> DIALLED = new ConcurrentHashMap<>();
    static final List<String> DIAL_LOG = new CopyOnWriteArrayList<>();
    static final List<String> TRANSFERS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Fakes {
        @Bean
        VoicePlatformClient fakePlatform() {
            return new VoicePlatformClient() {
                @Override
                public String name() {
                    return "fake";
                }

                @Override
                public Placed createOutboundCall(OutboundCall call) {
                    // Numbers ending 9999 stand for "the platform is down".
                    if (call.phoneE164().endsWith("9999")) {
                        return Placed.failed("platform unavailable");
                    }
                    DIALLED.put(call.phoneE164(), call);
                    DIAL_LOG.add(call.phoneE164());
                    return Placed.ok("fake-" + call.callId());
                }

                @Override
                public boolean transfer(String providerCallId, String deskNumberE164, String summary) {
                    TRANSFERS.add(providerCallId + "->" + deskNumberE164);
                    return true;
                }
            };
        }

        @Bean
        DndProvider fakeDnd() {
            return phone -> phone.endsWith("0000") ? DndResult.BLOCKED : DndResult.ALLOWED;
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    CampaignDispatcher dispatcher;

    @Test
    void campaignDialsThroughTheInstalledPlatform() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String counsellor = login(user(admin, "COUNSELLOR"), "StaffPass123!");
        approve(admin, "MISSED_CALL_FOLLOWUP");
        String answers = ending("1234");
        String blocked = ending("0000");
        String platformDown = ending("9999");
        String silent = ending("5678");
        String roll = "LV" + SEQ.incrementAndGet();
        long a = student(counsellor, "Asha Pillai", answers, roll);
        long b = student(counsellor, "Bala Murugan", blocked, "LV" + SEQ.incrementAndGet());
        long c = student(counsellor, "Chitra Devi", platformDown, "LV" + SEQ.incrementAndGet());
        long d = student(counsellor, "Dinesh Babu", silent, "LV" + SEQ.incrementAndGet());
        for (long s : List.of(a, b, c, d)) {
            // A counsellor's call that nobody picked up is what puts a student in this campaign.
            mvc.perform(auth(post("/api/calls"), counsellor).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"studentId\":" + s + ",\"outcome\":\"NO_ANSWER\"}")).andExpect(status().isCreated());
            mvc.perform(auth(post("/api/students/" + s + "/voice-consent"), counsellor)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"personType\":\"STUDENT\",\"aiCallConsent\":true,\"recordingConsent\":true,"
                            + "\"source\":\"ONBOARDING\"}")).andExpect(status().isOk());
        }

        MvcResult created = mvc.perform(auth(post("/api/voice/campaigns"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Missed calls\",\"purpose\":\"MISSED_CALL_FOLLOWUP\",\"maxAttempts\":2,"
                                + "\"maxConcurrent\":20,\"retryGapMin\":60,\"lookaheadDays\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preview.dndChecked").value(true))
                .andExpect(jsonPath("$.preview.skipped.DND").value(Matchers.greaterThanOrEqualTo(1)))
                .andReturn();
        long campaign = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.campaign.id")).longValue();
        mvc.perform(auth(post("/api/voice/campaigns/" + campaign + "/start"), admin)).andExpect(status().isOk());
        assertThat(dispatcher.dispatch()).isGreaterThanOrEqualTo(3);

        // The platform was given the number in international form and only what the call needs.
        VoicePlatformClient.OutboundCall dialled = DIALLED.get("+91" + answers);
        assertThat(dialled).isNotNull();
        assertThat(dialled.recording()).isTrue();
        assertThat(dialled.dynamicVariables()).containsEntry("first_name", "Asha").containsKey("consultancy_name");
        assertThat(dialled.dynamicVariables().toString() + dialled.openingLine()).doesNotContain(roll)
                .doesNotContain("Pillai");
        assertThat(dialled.openingLine()).contains("Asha");
        assertThat(DIALLED).doesNotContainKey("+91" + blocked);

        String targets = mvc.perform(auth(get("/api/voice/campaigns/" + campaign), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.targets[?(@.studentId == " + b + ")].skipReason").value(Matchers.hasItem("DND")))
                // A platform failure is not the family's missed call: no attempt is counted.
                .andExpect(jsonPath("$.targets[?(@.studentId == " + c + ")].status").value(Matchers.hasItem("PENDING")))
                .andExpect(jsonPath("$.targets[?(@.studentId == " + c + ")].attempts").value(Matchers.hasItem(0)))
                .andExpect(jsonPath("$.campaign.status").value("RUNNING"))
                .andReturn().getResponse().getContentAsString();
        assertThat(targets).isNotEmpty();
        mvc.perform(auth(get("/api/students/" + c + "/voice-calls"), counsellor))
                .andExpect(jsonPath("$[0].status").value("FAILED"));
        // While a call is in progress the same number is not dialled again.
        dispatcher.dispatch();
        assertThat(DIAL_LOG).containsOnlyOnce("+91" + answers);

        // Asha's parent picks up, asks for a person, and is transferred because the desk is open.
        String provider = "fake-" + dialled.callId();
        hook("/webhooks/call-started", "{\"provider_call_id\":\"" + provider + "\"}")
                .andExpect(jsonPath("$.applied").value(true));
        hook("/webhooks/call-started", "{\"provider_call_id\":\"" + provider + "\"}")
                .andExpect(jsonPath("$.applied").value(false));
        boolean deskOpen = ZonedDateTime.now(VoiceFacts.IST).getDayOfWeek() != DayOfWeek.SUNDAY;
        hook("/tools/request_human_handoff", "{\"call_id\":\"" + dialled.callId() + "\",\"args\":{\"reason_code\":"
                + "\"CALLER_REQUEST\",\"summary\":\"Wants to talk to the counsellor.\"}}")
                .andExpect(jsonPath("$.data.handoff_mode").value(deskOpen ? "TRANSFER_NOW" : "CALLBACK_TASK"));
        if (deskOpen) {
            assertThat(TRANSFERS).contains(provider + "->+914400000000");
        }
        hook("/webhooks/call-ended", "{\"provider_call_id\":\"" + provider + "\",\"status\":\"completed\","
                + "\"duration_sec\":130,\"recording_url\":\"https://rec.example/a.mp3\","
                + "\"transcript\":[{\"role\":\"agent\",\"text\":\"Hello\"}]}")
                .andExpect(jsonPath("$.applied").value(true));
        mvc.perform(auth(get("/api/voice/calls/" + dialled.callId()), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.call.status").value("COMPLETED"))
                .andExpect(jsonPath("$.call.outcome").value("HANDED_OFF"))
                .andExpect(jsonPath("$.call.provider").value("FAKE"))
                // Recording consent was given for this number, so the recording is kept.
                .andExpect(jsonPath("$.recordingRef").value("https://rec.example/a.mp3"));

        // Dinesh does not pick up: one attempt used, another one scheduled.
        String silentCall = "fake-" + DIALLED.get("+91" + silent).callId();
        hook("/webhooks/call-ended", "{\"provider_call_id\":\"" + silentCall + "\",\"status\":\"no_answer\"}")
                .andExpect(jsonPath("$.applied").value(true));
        mvc.perform(auth(get("/api/voice/campaigns/" + campaign), admin))
                .andExpect(jsonPath("$.targets[?(@.studentId == " + a + ")].status").value(Matchers.hasItem("DONE")))
                .andExpect(jsonPath("$.targets[?(@.studentId == " + a + ")].attempts").value(Matchers.hasItem(1)))
                .andExpect(jsonPath("$.targets[?(@.studentId == " + d + ")].status").value(Matchers.hasItem("PENDING")))
                .andExpect(jsonPath("$.targets[?(@.studentId == " + d + ")].attempts").value(Matchers.hasItem(1)))
                .andExpect(jsonPath("$.targets[?(@.studentId == " + d + ")].nextAttemptAt").value(
                        Matchers.hasItem(Matchers.notNullValue())));
        mvc.perform(auth(get("/api/students/" + d + "/calls"), counsellor))
                .andExpect(jsonPath("$[?(@.provider == 'AI_AGENT')].outcome").value(Matchers.hasItem("NO_ANSWER")));
        mvc.perform(auth(get("/api/integrations"), admin))
                .andExpect(jsonPath("$[?(@.key == 'VOICE_AGENT')].state").value(Matchers.hasItem("CONNECTED")));
        mvc.perform(auth(post("/api/voice/campaigns/" + campaign + "/finish"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.campaign.status").value("DONE"));
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions hook(String path, String body) throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        return mvc.perform(post("/api/voice/hooks" + path).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Voice-Timestamp", ts).header("X-Voice-Signature", VoiceSignatures.sign(SECRET, ts, body)))
                .andExpect(status().isOk());
    }

    private void approve(String admin, String purpose) throws Exception {
        List<Integer> ids = JsonPath.read(mvc.perform(auth(get("/api/voice/scripts"), admin)).andReturn().getResponse()
                .getContentAsString(), "$[?(@.purpose == '" + purpose + "' && @.language == 'ENGLISH')].id");
        mvc.perform(auth(post("/api/voice/scripts/" + ids.get(0) + "/approve"), admin)).andExpect(status().isOk());
    }

    private long student(String token, String name, String phone, String roll) throws Exception {
        return id(mvc.perform(auth(post("/api/students"), token).contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"%s","phone":"%s","category":"OBC","pwd":false,"homeState":"Tamil Nadu",
                 "domicileStatus":"DOMICILED","nationality":"INDIAN","nriSponsored":false,"neetQualified":true,
                 "neetRollNo":"%s","languagePreference":"ENGLISH"}""".formatted(name, phone, roll)))
                .andExpect(status().isCreated()).andReturn());
    }

    /** A unique 10-digit mobile number with the given last four digits. */
    private static String ending(String last4) {
        return "9" + String.format("%05d", SEQ.incrementAndGet()) + last4;
    }

    private String user(String adminToken, String role) throws Exception {
        String email = role.toLowerCase() + SEQ.incrementAndGet() + "@live.test";
        mvc.perform(auth(post("/api/users"), adminToken).contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"%s user","email":"%s","role":"%s","password":"StaffPass123!"}""".formatted(role, email, role)))
                .andExpect(status().isCreated());
        return email;
    }

    private String login(String email, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.accessToken");
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static long id(MvcResult r) throws Exception {
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
