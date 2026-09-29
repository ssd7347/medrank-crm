package com.mbbscrm.crm;

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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.mbbscrm.crm.alert.EscalationJob;
import com.mbbscrm.crm.alert.MessageDispatcher;
import com.mbbscrm.crm.counselling.DeadlineAlertJob;

/**
 * Phase 2 end to end: calendar, predictor, tracks, choice lists and locking, deadline alerts, allotments,
 * decisions, message dispatch and escalation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.alerts.escalate-after=PT0S")
class CounsellingIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(5000);

    @Autowired
    MockMvc mvc;
    @Autowired
    DeadlineAlertJob deadlineJob;
    @Autowired
    MessageDispatcher dispatcher;
    @Autowired
    EscalationJob escalation;

    @Test
    void fullRoundLifecycle() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String counsellorEmail = "c" + SEQ.incrementAndGet() + "@test.local";
        mvc.perform(auth(post("/api/users"), admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Counsellor","email":"%s","role":"COUNSELLOR","password":"StaffPass123!"}"""
                .formatted(counsellorEmail))).andExpect(status().isCreated());
        String counsellor = login(counsellorEmail, "StaffPass123!");

        // ---- master data: one Tamil Nadu college with cutoffs (admin changes apply immediately)
        String code = "TN" + SEQ.incrementAndGet();
        long collegeId = entityId(mvc.perform(auth(post("/api/change-requests"), admin)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"entityType":"COLLEGE","action":"CREATE","payload":{"name":"Govt Medical College %s",
                         "code":"%s","collegeType":"GOVERNMENT","state":"Tamil Nadu","nmcRecognized":true}}"""
                        .formatted(code, code))).andExpect(status().isCreated()).andReturn());
        String cutoffs = "college_code,course,quota,category,round,academic_year,closing_rank\n"
                + code + ",MBBS,STATE,OBC,ROUND_1,2025,3000\n"
                + code + ",MBBS,STATE,OBC,MOP_UP,2025,4200\n"
                + code + ",MBBS,AIQ,OBC,ROUND_1,2025,9000\n";
        mvc.perform(multipart("/api/change-requests/bulk/cutoffs").file(csv(cutoffs))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andExpect(status().isCreated());

        // ---- two students owned by the counsellor
        long s1 = createStudent(counsellor, "Anitha R", 3500);
        long s2 = createStudent(counsellor, "Bala K", 3900);

        // ---- predictor from the student profile: domiciled TN OBC, AIR 3500
        mvc.perform(auth(post("/api/predictor"), counsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":" + s1 + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[?(@.collegeId == " + collegeId + " && @.quota == 'STATE')].band")
                        .value("MODERATE"))
                .andExpect(jsonPath("$.results[?(@.collegeId == " + collegeId + " && @.quota == 'AIQ')].band")
                        .value("HIGH"))
                .andExpect(jsonPath("$.disclaimer").isNotEmpty());
        mvc.perform(auth(post("/api/students/" + s1 + "/shortlist"), counsellor)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"collegeId":%d,"course":"MBBS","quota":"STATE","band":"MODERATE"}""".formatted(collegeId)))
                .andExpect(status().isCreated());

        // ---- calendar: TN round 1 with choice filling closing in 12h and reporting closing in 20h
        MvcResult auths = mvc.perform(auth(get("/api/counselling/authorities"), counsellor)).andReturn();
        List<Integer> tnIds = JsonPath.read(auths.getResponse().getContentAsString(),
                "$[?(@.code == 'TN-SELECTION')].id");
        long tn = tnIds.get(0);
        Instant now = Instant.now();
        mvc.perform(auth(post("/api/counselling/rounds"), counsellor).contentType(MediaType.APPLICATION_JSON)
                .content(roundJson(tn, now))).andExpect(status().isForbidden());
        long roundId = entityId(mvc.perform(auth(post("/api/counselling/rounds"), admin)
                .contentType(MediaType.APPLICATION_JSON).content(roundJson(tn, now)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phase").value("CHOICE_FILLING")).andReturn());

        // ---- tracks
        long t1 = entityId(mvc.perform(auth(post("/api/students/" + s1 + "/counselling"), counsellor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"authorityId\":" + tn + ",\"academicYear\":2026,\"status\":\"REGISTERED\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rounds[0].id").value(roundId)).andReturn());
        long t2 = entityId(mvc.perform(auth(post("/api/students/" + s2 + "/counselling"), counsellor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"authorityId\":" + tn + ",\"academicYear\":2026,\"status\":\"REGISTERED\"}"))
                .andExpect(status().isCreated()).andReturn());

        // ---- choice list for student 1: AIQ is rejected under a state authority, STATE accepted, then locked
        long list = entityId(mvc.perform(auth(post("/api/counselling/tracks/" + t1 + "/rounds/" + roundId
                + "/choice-list"), counsellor)).andExpect(status().isOk()).andReturn());
        mvc.perform(auth(put("/api/choice-lists/" + list + "/items"), counsellor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"collegeId\":" + collegeId + ",\"course\":\"MBBS\",\"quota\":\"AIQ\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(put("/api/choice-lists/" + list + "/items"), counsellor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"collegeId\":" + collegeId + ",\"course\":\"MBBS\",\"quota\":\"STATE\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].position").value(1));
        mvc.perform(auth(post("/api/choice-lists/" + list + "/lock"), counsellor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmedByName\":\"Anitha's father\",\"confirmationMethod\":\"PHONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"));
        mvc.perform(auth(put("/api/choice-lists/" + list + "/items"), counsellor)
                .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}")).andExpect(status().isConflict());
        mvc.perform(auth(post("/api/choice-lists/" + list + "/unlock"), counsellor)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());

        // ---- deadline scan: only student 2 (no locked list) gets a choice-filling alert; running twice is a no-op
        DeadlineAlertJob.ScanResult first = deadlineJob.run();
        assertThat(first.choiceFillingAlerts()).isEqualTo(1);
        assertThat(deadlineJob.run().choiceFillingAlerts()).isZero();
        mvc.perform(auth(get("/api/students/" + s2 + "/messages"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].template").value("CHOICE_FILLING_CLOSING"))
                .andExpect(jsonPath("$[0].priority").value("URGENT"));

        // ---- result: student 1 allotted; urgent message + counsellor notification
        long allotment = entityId(mvc.perform(auth(post("/api/counselling/tracks/" + t1 + "/allotments"), counsellor)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"roundId":%d,"collegeId":%d,"course":"MBBS","quota":"STATE"}"""
                                .formatted(roundId, collegeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionDeadline").isNotEmpty()).andReturn());
        mvc.perform(auth(get("/api/notifications"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'ALLOTMENT')].priority").value("URGENT"));

        // ---- deadline scan now also flags the undecided seat (reporting closes within 24h)
        assertThat(deadlineJob.run().decisionAlerts()).isEqualTo(1);

        // ---- dispatch (simulated provider) and escalation of unacknowledged urgent messages
        assertThat(dispatcher.dispatchPending()).isGreaterThanOrEqualTo(0);
        mvc.perform(auth(get("/api/students/" + s1 + "/messages"), counsellor))
                .andExpect(jsonPath("$[?(@.status == 'QUEUED')]").isEmpty())
                .andExpect(jsonPath("$[0].status").value("SIMULATED"));
        assertThat(escalation.run()).isGreaterThanOrEqualTo(1);
        mvc.perform(auth(get("/api/notifications/unread-count"), counsellor))
                .andExpect(jsonPath("$.unread").isNumber());
        mvc.perform(auth(get("/api/notifications"), counsellor))
                .andExpect(jsonPath("$[?(@.type == 'ESCALATION')]").isNotEmpty());

        // ---- decision: preview warns, then FREEZE admits the student
        mvc.perform(auth(get("/api/allotments/" + allotment + "/decision-preview").param("decision", "WITHDRAW"),
                        counsellor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundRuleFound").value(false))
                .andExpect(jsonPath("$.consequences", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("Round 1"))));
        mvc.perform(auth(post("/api/allotments/" + allotment + "/decision"), counsellor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"FREEZE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("FREEZE"));
        mvc.perform(auth(get("/api/students/" + s1 + "/counselling"), counsellor))
                .andExpect(jsonPath("$[0].status").value("ADMITTED"))
                .andExpect(jsonPath("$[0].choiceLists[0].status").value("LOCKED"));

        // ---- bulk results: student 2 gets no seat
        String roll2 = lastRoll;
        String bulk = "neet_roll_no,college_code,course,quota,category\n" + roll2 + ",,,,\n";
        mvc.perform(multipart("/api/counselling/rounds/" + roundId + "/allotments/import").file(csv(bulk))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noAllotment").value(1));
        mvc.perform(multipart("/api/counselling/rounds/" + roundId + "/allotments/import")
                        .file(csv("neet_roll_no,college_code\nNOPE123,\n"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isBadRequest());

        mvc.perform(auth(get("/api/counselling/desk"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.upcomingDeadlines").isArray());
    }

    /** Creates a domiciled TN OBC student with roll number RB<seq>; remembers the roll for bulk tests. */
    private long createStudent(String token, String name, int air) throws Exception {
        long seq = SEQ.incrementAndGet();
        MvcResult r = mvc.perform(auth(post("/api/students"), token).contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"%s","phone":"9%09d","parentPhone":"8%09d","category":"OBC","pwd":false,
                 "homeState":"Tamil Nadu","domicileStatus":"DOMICILED","nationality":"INDIAN","nriSponsored":false,
                 "neetYear":2026,"neetRollNo":"RB%d","neetQualified":true,"neetAir":%d,
                 "categoryCertValidUntil":"2030-01-01","languagePreference":"TAMIL"}"""
                .formatted(name, seq, seq, seq, air))).andExpect(status().isCreated()).andReturn();
        lastRoll = "RB" + seq;
        return entityId(r);
    }

    private String lastRoll;

    private static String roundJson(long authorityId, Instant now) {
        return """
                {"authorityId":%d,"academicYear":2026,"roundType":"ROUND_1",
                 "choiceFillingStart":"%s","choiceFillingEnd":"%s","resultAt":"%s",
                 "reportingStart":"%s","reportingEnd":"%s"}"""
                .formatted(authorityId, now.minus(2, ChronoUnit.DAYS), now.plus(12, ChronoUnit.HOURS),
                        now.plus(14, ChronoUnit.HOURS), now.plus(15, ChronoUnit.HOURS),
                        now.plus(20, ChronoUnit.HOURS));
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

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "data.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private static long entityId(MvcResult r) throws Exception {
        String body = r.getResponse().getContentAsString();
        Object v = body.contains("\"entityId\"") ? JsonPath.read(body, "$.entityId") : JsonPath.read(body, "$.id");
        return ((Number) v).longValue();
    }
}
