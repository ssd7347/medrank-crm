package com.aicallcrm.crm.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.aicallcrm.crm.voice.VoiceSignatures;
import com.jayway.jsonpath.JsonPath;

/**
 * Erasing a person: only the admin, only after typing their number, never during a live call; afterwards nothing
 * in any table of the database names them, their AI calls remain only as numbers, and a number that had said no
 * stays uncallable when a list brings it back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErasureIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String SECRET = "test-only-voice-secret-0123456789-abcdefgh";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    String admin;
    String tagKey;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin@test.local", "AdminPass123!");
        tagKey = JsonPath.read(mvc.perform(auth(post("/api/fields"), admin).contentType(MediaType.APPLICATION_JSON).content(
                        "{\"label\":\"Erase Tag\",\"fieldType\":\"TEXT\",\"agentAccess\":\"NONE\",\"verifyWith\":false,"
                                + "\"sortOrder\":10,\"active\":true}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.key");
        approve("Incoming calls");
    }

    @Test
    void everythingAboutThePersonGoesAndTheirAiCallsStayOnlyAsNumbers() throws Exception {
        String name = "Zubin Erasewala";
        String main = phone();
        String earlier = phone();
        String email = "zubin.erasewala@erase.test";
        String tag = "erase-tag-" + SEQ.incrementAndGet();

        // He rang in once before he was in the CRM, from the number that later became his alternate.
        String before = inbound(earlier);
        hook("/webhooks/call-ended", "{\"call_id\":\"" + before + "\",\"status\":\"completed\"}");

        long id = id(mvc.perform(auth(post("/api/contacts"), admin).contentType(MediaType.APPLICATION_JSON).content(
                        "{\"fullName\":\"" + name + "\",\"phone\":\"" + main + "\",\"altPhone\":\"" + earlier
                                + "\",\"email\":\"" + email + "\",\"notes\":\"Lives near the old mill\",\"fields\":{\""
                                + tagKey + "\":\"" + tag + "\"}}"))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(auth(put("/api/contacts/" + id + "/tags"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tags\":[\"VIP\"]}")).andExpect(status().isOk());
        consent(id);
        mvc.perform(auth(post("/api/contacts/" + id + "/activities"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"NOTE\",\"notes\":\"Zubin says his brother handles the loan\"}"))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(auth(post("/api/contacts/" + id + "/follow-ups"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueAt\":\"" + Instant.now().plus(2, ChronoUnit.DAYS) + "\",\"purpose\":\"Call Zubin about the papers\"}"))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(auth(post("/api/calls"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactId\":" + id + ",\"phone\":\"" + main + "\",\"direction\":\"OUTBOUND\","
                        + "\"outcome\":\"CONNECTED\",\"durationSeconds\":60,\"notes\":\"Spoke to Zubin\"}"))
                .andExpect(status().is2xxSuccessful());

        // An AI call: words, a hand-over to a person (a call-back task and an alert), and the end of the call.
        String call = inbound(main);
        hook("/live/open", "{\"call_id\":\"" + call + "\"}").andExpect(status().isOk());
        hook("/live/last", "{\"call_id\":\"" + call + "\",\"said\":\"This is Zubin, my bill near the old mill is wrong\"}")
                .andExpect(status().isOk());
        hook("/tools/request_human_handoff", "{\"call_id\":\"" + call + "\",\"args\":{\"reason_code\":\"COMPLAINT_OR_DISPUTE\","
                + "\"summary\":\"Zubin Erasewala disputes the bill\"}}").andExpect(jsonPath("$.ok").value(true));
        hook("/webhooks/call-ended", "{\"call_id\":\"" + call + "\",\"status\":\"completed\",\"duration_sec\":95}");
        assertThat(jdbc.queryForObject("select count(*) from callback_request where voice_call_id = ?", Integer.class,
                java.util.UUID.fromString(call))).isPositive();

        // A campaign that includes him, and an import report that names him.
        mvc.perform(auth(post("/api/voice/campaigns"), admin).contentType(MediaType.APPLICATION_JSON).content(
                        "{\"name\":\"Erase test\",\"scriptName\":\"Payment reminder\",\"fieldKey\":\"" + tagKey
                                + "\",\"fieldOp\":\"EQUALS\",\"fieldValue\":\"" + tag + "\",\"maxAttempts\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targets[?(@.contactId == " + id + ")]").isNotEmpty());
        importCsv("Name,Phone\nSomebody Else," + main + "\n", "{\"mapping\":{\"Name\":\"name\",\"Phone\":\"phone\"}}")
                .andExpect(jsonPath("$.skipped").value(1));
        assertThat(cellsContaining(name)).isNotEmpty();

        // Only the admin, and only with his number typed.
        String manager = login(user("MANAGER"), "StaffPass123!");
        erase(manager, id, main).andExpect(status().isForbidden());
        erase(admin, id, earlier).andExpect(status().isBadRequest());
        erase(admin, id, "").andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/contacts/" + id), admin)).andExpect(status().isOk());

        erase(admin, id, "+91 " + main.substring(0, 5) + " " + main.substring(5)).andExpect(status().isOk())
                .andExpect(jsonPath("$.followUps").value(1))
                .andExpect(jsonPath("$.callLogs").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.aiCallsAnonymised").value(2))
                .andExpect(jsonPath("$.consentRecords").value(1))
                .andExpect(jsonPath("$.callbacks").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.alerts").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.campaignPlaces").value(1))
                .andExpect(jsonPath("$.importReportsCleaned").value(1))
                .andExpect(jsonPath("$.numbersKeptAsHash").value(0));

        mvc.perform(auth(get("/api/contacts/" + id), admin)).andExpect(status().isNotFound());
        erase(admin, id, main).andExpect(status().isNotFound());
        // Nowhere in the database: not his name, numbers, email, or anything he or staff said about him.
        for (String needle : List.of(name, "Zubin", main, earlier, email, "old mill")) {
            assertThat(cellsContaining(needle)).as(needle).isEmpty();
        }
        assertThat(jdbc.queryForObject("select count(*) from erased_number where phone_hash in (?, ?)", Integer.class,
                ErasedNumberRepository.hash(main), ErasedNumberRepository.hash(earlier))).isZero();

        // His AI calls remain for the reports, with nothing that leads back to him.
        for (String c : List.of(before, call)) {
            Map<String, Object> row = jdbc.queryForMap("select * from voice_call where id = ?", java.util.UUID.fromString(c));
            assertThat(row.get("contact_id")).isNull();
            assertThat(row.get("phone")).isEqualTo("");
            assertThat(row.get("transcript")).isNull();
            assertThat(row.get("summary")).isNull();
            assertThat(row.get("provider_call_id")).isNull();
            assertThat(row.get("status")).isEqualTo("COMPLETED");
        }
        assertThat(jdbc.queryForObject("select outcome from voice_call where id = ?", String.class,
                java.util.UUID.fromString(call))).isEqualTo("HANDED_OFF");
        assertThat(jdbc.queryForObject("select count(*) from voice_tool_audit where call_id = ?", Integer.class,
                java.util.UUID.fromString(call))).isPositive();
        String page = mvc.perform(auth(get("/api/voice/calls/" + call), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.call.outcome").value("HANDED_OFF"))
                .andReturn().getResponse().getContentAsString();
        assertThat(page).doesNotContain(main).doesNotContain("Zubin");

        // The erasure itself is on record, without the person.
        mvc.perform(auth(get("/api/voice/overview"), admin)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select details from audit_log where action = 'CONTACT_ERASED' and entity_id = ?",
                String.class, id)).startsWith("reason=Asked by email");
    }

    @Test
    void aNumberThatHadSaidNoStaysUncallableWhenAListBringsItBackOthersLeaveNoTrace() throws Exception {
        String refusedPhone = phone();
        String blockedPhone = phone();
        String plainPhone = phone();
        long refused = contact("Erase Refused", refusedPhone, null);
        consent(refused);
        mvc.perform(auth(post("/api/contacts/" + refused + "/voice-consent"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"alternate\":false,\"aiCallConsent\":false,\"recordingConsent\":false,\"source\":\"VERBAL_ON_CALL\"}"))
                .andExpect(jsonPath("$[0].state").value("REFUSED"));
        long blocked = contact("Erase Blocked", blockedPhone, null);
        consent(blocked);
        mvc.perform(auth(post("/api/contacts/" + blocked + "/status"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DO_NOT_CALL\"}")).andExpect(status().isOk());
        long plain = contact("Erase Plain", plainPhone, null);
        consent(plain);

        erase(admin, refused, refusedPhone).andExpect(jsonPath("$.numbersKeptAsHash").value(1));
        erase(admin, blocked, blockedPhone).andExpect(jsonPath("$.numbersKeptAsHash").value(1));
        erase(admin, plain, plainPhone).andExpect(jsonPath("$.numbersKeptAsHash").value(0));
        assertThat(jdbc.queryForObject("select count(*) from erased_number where phone_hash = ?", Integer.class,
                ErasedNumberRepository.hash(plainPhone))).isZero();
        for (String p : List.of(refusedPhone, blockedPhone, plainPhone)) {
            assertThat(cellsContaining(p)).as(p).isEmpty();
        }

        // The same three numbers come back in a list "with consent to AI calls".
        String tag = "back-tag-" + SEQ.incrementAndGet();
        importCsv("Name,Phone,Erase Tag\nBack One," + refusedPhone + "," + tag + "\nBack Two," + blockedPhone + "," + tag
                        + "\nBack Three," + plainPhone + "," + tag + "\n",
                "{\"mapping\":{\"Name\":\"name\",\"Phone\":\"phone\",\"Erase Tag\":\"field:" + tagKey + "\"},"
                        + "\"aiCallConsent\":true,\"consentEvidence\":\"Old signup sheet\"}")
                .andExpect(jsonPath("$.created").value(3));
        long backRefused = idByPhone(refusedPhone);
        long backBlocked = idByPhone(blockedPhone);
        long backPlain = idByPhone(plainPhone);
        mvc.perform(auth(get("/api/contacts/" + backRefused + "/voice-consent"), admin))
                .andExpect(jsonPath("$[0].state").value("OPTED_OUT"))
                .andExpect(jsonPath("$[0].revokeNote").value(Matchers.containsString("erased")));
        mvc.perform(auth(get("/api/contacts/" + backBlocked + "/voice-consent"), admin))
                .andExpect(jsonPath("$[0].state").value("OPTED_OUT"));
        mvc.perform(auth(get("/api/contacts/" + backPlain + "/voice-consent"), admin))
                .andExpect(jsonPath("$[0].state").value("GRANTED"));
        // What a campaign decides from (the same rule the dialler checks before every call).
        mvc.perform(auth(post("/api/voice/campaigns"), admin).contentType(MediaType.APPLICATION_JSON).content(
                        "{\"name\":\"Back again\",\"scriptName\":\"Payment reminder\",\"fieldKey\":\"" + tagKey
                                + "\",\"fieldOp\":\"EQUALS\",\"fieldValue\":\"" + tag + "\",\"maxAttempts\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targets", Matchers.hasSize(3)))
                .andExpect(jsonPath("$.preview.eligibleNow").value(1))
                .andExpect(jsonPath("$.preview.skipped.OPTED_OUT").value(2));

        // A fresh "yes" recorded by hand, by someone who asked the person, does count.
        consent(backRefused);
        mvc.perform(auth(get("/api/contacts/" + backRefused + "/voice-consent"), admin))
                .andExpect(jsonPath("$[0].state").value("GRANTED"));
        // Erased again: the number is kept once, not twice.
        erase(admin, backBlocked, blockedPhone).andExpect(jsonPath("$.numbersKeptAsHash").value(1));
        assertThat(jdbc.queryForObject("select count(*) from erased_number where phone_hash = ?", Integer.class,
                ErasedNumberRepository.hash(blockedPhone))).isEqualTo(1);
    }

    @Test
    void notDuringALiveCallAndOtherContactsAreLeftAlone() throws Exception {
        String phone = phone();
        String otherPhone = phone();
        long person = contact("Erase Twin", phone, null);
        long twin = contact("Erase Twin", otherPhone, null);
        mvc.perform(auth(post("/api/contacts/duplicates/dismiss"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + person + "," + twin + "]}")).andExpect(status().isOk());

        String call = inbound(phone);
        erase(admin, person, phone).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("in progress")));
        mvc.perform(auth(get("/api/contacts/" + person), admin)).andExpect(status().isOk());

        hook("/webhooks/call-ended", "{\"call_id\":\"" + call + "\",\"status\":\"completed\"}");
        erase(admin, person, phone).andExpect(status().isOk()).andExpect(jsonPath("$.aiCallsAnonymised").value(1));
        mvc.perform(auth(get("/api/contacts/" + twin), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Erase Twin"));
        assertThat(jdbc.queryForObject("select count(*) from duplicate_dismissal where contact_a = ? or contact_b = ?",
                Integer.class, twin, twin)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    /** Every text cell, in every table, that contains {@code needle}. */
    private List<String> cellsContaining(String needle) {
        List<Map<String, Object>> all = jdbc.queryForList("select table_schema, table_name, column_name, data_type"
                + " from information_schema.columns");
        List<Map<String, Object>> columns = all.stream()
                .filter(c -> "public".equalsIgnoreCase(String.valueOf(c.get("table_schema"))))
                .filter(c -> String.valueOf(c.get("data_type")).toUpperCase(java.util.Locale.ROOT).contains("CHAR"))
                .toList();
        assertThat(columns).as("text columns found among %s", all.stream().limit(5).toList()).hasSizeGreaterThan(50);
        List<String> found = new ArrayList<>();
        for (Map<String, Object> col : columns) {
            String table = (String) col.get("table_name");
            String column = (String) col.get("column_name");
            Integer n = jdbc.queryForObject("select count(*) from \"" + table + "\" where \"" + column + "\" like ?",
                    Integer.class, "%" + needle + "%");
            if (n != null && n > 0) {
                found.add(table + "." + column);
            }
        }
        return found;
    }

    private ResultActions erase(String token, long id, String phone) throws Exception {
        return mvc.perform(auth(post("/api/contacts/" + id + "/erase"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmPhone\":\"" + phone + "\",\"reason\":\"Asked by email on 15 Nov\"}"));
    }

    private ResultActions importCsv(String csv, String options) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "erase-list.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile opts = new MockMultipartFile("options", "", "application/json", options.getBytes(StandardCharsets.UTF_8));
        return mvc.perform(multipart("/api/contacts/import").file(file).file(opts)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andExpect(status().isOk());
    }

    private long idByPhone(String phone) {
        return jdbc.queryForObject("select id from contact where phone = ?", Long.class, phone);
    }

    private String inbound(String phone) throws Exception {
        return JsonPath.read(hook("/inbound/lookup", "{\"caller_number\":\"" + phone + "\",\"provider_call_id\":\"erase-"
                + phone + "\"}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.call_id");
    }

    private long contact(String name, String phone, String altPhone) throws Exception {
        return id(mvc.perform(auth(post("/api/contacts"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"" + name + "\",\"phone\":\"" + phone + "\""
                                + (altPhone == null ? "" : ",\"altPhone\":\"" + altPhone + "\"") + ",\"fields\":{}}"))
                .andExpect(status().isCreated()).andReturn());
    }

    private void consent(long contact) throws Exception {
        mvc.perform(auth(post("/api/contacts/" + contact + "/voice-consent"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"alternate\":false,\"aiCallConsent\":true,\"recordingConsent\":false,\"source\":\"SIGNUP_FORM\"}"))
                .andExpect(status().isOk());
    }

    private void approve(String scriptName) throws Exception {
        List<Integer> ids = JsonPath.read(mvc.perform(auth(get("/api/voice/scripts"), admin)).andReturn().getResponse()
                .getContentAsString(), "$[?(@.name == '" + scriptName + "')].id");
        mvc.perform(auth(post("/api/voice/scripts/" + ids.get(0) + "/approve"), admin)).andExpect(status().isOk());
    }

    private ResultActions hook(String path, String body) throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        return mvc.perform(post("/api/voice/hooks" + path).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Voice-Timestamp", ts).header("X-Voice-Signature", VoiceSignatures.sign(SECRET, ts, body)));
    }

    /** Numbers no other test uses: 77 followed by eight digits. */
    private static String phone() {
        return "77" + String.format("%08d", 41000 + SEQ.incrementAndGet());
    }

    private String user(String role) throws Exception {
        String email = role.toLowerCase() + "-erase" + SEQ.incrementAndGet() + "@erase.test";
        mvc.perform(auth(post("/api/users"), admin).contentType(MediaType.APPLICATION_JSON).content("""
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
