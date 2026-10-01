package com.aicallcrm.crm.voice;

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
import com.aicallcrm.crm.voice.Voice.DndResult;

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
        mvc.perform(auth(post("/api/fields"), admin).contentType(MediaType.APPLICATION_JSON).content(
                "{\"label\":\"Live Tag\",\"fieldType\":\"TEXT\",\"agentAccess\":\"NONE\",\"verifyWith\":false,"
                        + "\"sortOrder\":10,\"active\":true}")).andExpect(status().isCreated());
        approve(admin, "Enquiry follow-up");
        String tag = "L" + SEQ.incrementAndGet();
        String answers = ending("1234");
        String blocked = ending("0000");
        String platformDown = ending("9999");
        String silent = ending("5678");
        long a = contact(admin, "Asha Pillai", answers, tag);
        long b = contact(admin, "Bala Murugan", blocked, tag);
        long c = contact(admin, "Chitra Devi", platformDown, tag);
        long d = contact(admin, "Dinesh Babu", silent, tag);
        for (long id : List.of(a, b, c, d)) {
            mvc.perform(auth(post("/api/contacts/" + id + "/voice-consent"), admin).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"alternate\":false,\"aiCallConsent\":true,\"recordingConsent\":true,\"source\":\"SIGNUP_FORM\"}"))
                    .andExpect(status().isOk());
        }

        MvcResult created = mvc.perform(auth(post("/api/voice/campaigns"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Follow-ups\",\"scriptName\":\"Enquiry follow-up\",\"fieldKey\":\"live_tag\","
                                + "\"fieldOp\":\"EQUALS\",\"fieldValue\":\"" + tag + "\",\"maxAttempts\":2,"
                                + "\"maxConcurrent\":20,\"retryGapMin\":60}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preview.dndChecked").value(true))
                .andExpect(jsonPath("$.preview.skipped.DND").value(1))
                .andReturn();
        long campaign = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.campaign.id")).longValue();
        mvc.perform(auth(post("/api/voice/campaigns/" + campaign + "/start"), admin)).andExpect(status().isOk());
        assertThat(dispatcher.dispatch()).isGreaterThanOrEqualTo(3);

        VoicePlatformClient.OutboundCall dialled = DIALLED.get("+91" + answers);
        assertThat(dialled).isNotNull();
        assertThat(dialled.recording()).isTrue();
        assertThat(dialled.dynamicVariables()).containsEntry("first_name", "Asha").containsKey("company_name");
        assertThat(dialled.openingLine()).contains("Asha");
        assertThat(dialled.dynamicVariables().toString() + dialled.openingLine()).doesNotContain("Pillai").doesNotContain(tag);
        assertThat(DIALLED).doesNotContainKey("+91" + blocked);

        mvc.perform(auth(get("/api/voice/campaigns/" + campaign), admin))
                .andExpect(jsonPath("$.targets[?(@.contactId == " + b + ")].skipReason").value(Matchers.hasItem("DND")))
                // A platform failure is not the customer's missed call: no attempt is counted.
                .andExpect(jsonPath("$.targets[?(@.contactId == " + c + ")].status").value(Matchers.hasItem("PENDING")))
                .andExpect(jsonPath("$.targets[?(@.contactId == " + c + ")].attempts").value(Matchers.hasItem(0)));
        dispatcher.dispatch();
        assertThat(DIAL_LOG).containsOnlyOnce("+91" + answers);

        String provider = "fake-" + dialled.callId();
        hook("/webhooks/call-started", "{\"provider_call_id\":\"" + provider + "\"}").andExpect(jsonPath("$.applied").value(true));
        hook("/webhooks/call-started", "{\"provider_call_id\":\"" + provider + "\"}").andExpect(jsonPath("$.applied").value(false));
        boolean deskOpen = ZonedDateTime.now(ContactFacts.IST).getDayOfWeek() != DayOfWeek.SUNDAY;
        hook("/tools/request_human_handoff", "{\"call_id\":\"" + dialled.callId() + "\",\"args\":{\"reason_code\":"
                + "\"CALLER_REQUEST\",\"summary\":\"Wants to talk to sales.\"}}")
                .andExpect(jsonPath("$.data.handoff_mode").value(deskOpen ? "TRANSFER_NOW" : "CALLBACK_TASK"));
        if (deskOpen) {
            assertThat(TRANSFERS).contains(provider + "->+914400000000");
        }
        hook("/webhooks/call-ended", "{\"provider_call_id\":\"" + provider + "\",\"status\":\"completed\","
                + "\"duration_sec\":130,\"recording_url\":\"https://rec.example/a.mp3\"}")
                .andExpect(jsonPath("$.applied").value(true));
        mvc.perform(auth(get("/api/voice/calls/" + dialled.callId()), admin))
                .andExpect(jsonPath("$.call.status").value("COMPLETED"))
                .andExpect(jsonPath("$.call.outcome").value("HANDED_OFF"))
                .andExpect(jsonPath("$.call.provider").value("FAKE"))
                .andExpect(jsonPath("$.recordingRef").value("https://rec.example/a.mp3"));
        // Being reached moves a new contact on to "contacted".
        mvc.perform(auth(get("/api/contacts/" + a), admin)).andExpect(jsonPath("$.status").value("CONTACTED"));

        String silentCall = "fake-" + DIALLED.get("+91" + silent).callId();
        hook("/webhooks/call-ended", "{\"provider_call_id\":\"" + silentCall + "\",\"status\":\"no_answer\"}")
                .andExpect(jsonPath("$.applied").value(true));
        mvc.perform(auth(get("/api/voice/campaigns/" + campaign), admin))
                .andExpect(jsonPath("$.targets[?(@.contactId == " + a + ")].status").value(Matchers.hasItem("DONE")))
                .andExpect(jsonPath("$.targets[?(@.contactId == " + d + ")].status").value(Matchers.hasItem("PENDING")))
                .andExpect(jsonPath("$.targets[?(@.contactId == " + d + ")].attempts").value(Matchers.hasItem(1)));
        mvc.perform(auth(get("/api/contacts/" + d + "/calls"), admin))
                .andExpect(jsonPath("$[?(@.provider == 'AI_AGENT')].outcome").value(Matchers.hasItem("NO_ANSWER")));
        mvc.perform(auth(get("/api/integrations"), admin))
                .andExpect(jsonPath("$[?(@.key == 'VOICE_AGENT')].state").value(Matchers.hasItem("CONNECTED")))
                .andExpect(jsonPath("$[?(@.key == 'DND')].state").value(Matchers.hasItem("CONNECTED")));
        mvc.perform(auth(post("/api/voice/campaigns/" + campaign + "/finish"), admin))
                .andExpect(jsonPath("$.campaign.status").value("DONE"));
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions hook(String path, String body) throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        return mvc.perform(post("/api/voice/hooks" + path).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Voice-Timestamp", ts).header("X-Voice-Signature", VoiceSignatures.sign(SECRET, ts, body)))
                .andExpect(status().isOk());
    }

    private void approve(String admin, String name) throws Exception {
        List<Integer> ids = JsonPath.read(mvc.perform(auth(get("/api/voice/scripts"), admin)).andReturn().getResponse()
                .getContentAsString(), "$[?(@.name == '" + name + "')].id");
        mvc.perform(auth(post("/api/voice/scripts/" + ids.get(0) + "/approve"), admin)).andExpect(status().isOk());
    }

    private long contact(String token, String name, String phone, String tag) throws Exception {
        return ((Number) JsonPath.read(mvc.perform(auth(post("/api/contacts"), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"" + name + "\",\"phone\":\"" + phone + "\",\"fields\":{\"live_tag\":\"" + tag + "\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** A unique 10-digit mobile number with the given last four digits. */
    private static String ending(String last4) {
        return "9" + String.format("%05d", SEQ.incrementAndGet()) + last4;
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
}
