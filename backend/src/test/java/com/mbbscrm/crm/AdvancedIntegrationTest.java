package com.mbbscrm.crm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.mbbscrm.crm.loan.LoanAlertJob;

/** Phase 5 end to end: loans, sessions, call log, scoring, agreements, the public assistant, languages. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdvancedIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(4000);

    @Autowired
    MockMvc mvc;
    @Autowired
    LoanAlertJob loanAlertJob;

    @Test
    void loanTrackingAndLateApprovalAlert() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String counsellor = login(user(admin, "COUNSELLOR"), "StaffPass123!");
        String loanDesk = login(user(admin, "LOAN_DESK"), "StaffPass123!");
        String telecaller = login(user(admin, "TELECALLER"), "StaffPass123!");
        long student = student(counsellor);

        mvc.perform(auth(post("/api/loan-partners"), counsellor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"active\":true}")).andExpect(status().isForbidden());
        long partner = id(mvc.perform(auth(post("/api/loan-partners"), loanDesk).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sample NBFC " + SEQ.incrementAndGet() + "\",\"interestInfo\":\"10.5% - 12.5%\","
                                + "\"maxAmount\":4000000,\"active\":true}"))
                .andExpect(status().isCreated()).andReturn());

        String neededBy = LocalDate.now().plusDays(4).toString();
        mvc.perform(auth(post("/api/students/" + student + "/loans"), loanDesk).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerId\":" + partner + ",\"amountRequested\":1500000,\"status\":\"SUBMITTED\"}"))
                .andExpect(status().isBadRequest()); // submitted needs the applied-on date
        long loan = id(mvc.perform(auth(post("/api/students/" + student + "/loans"), loanDesk)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerId\":" + partner + ",\"amountRequested\":1500000,\"status\":\"SUBMITTED\","
                                + "\"appliedOn\":\"" + LocalDate.now() + "\",\"neededBy\":\"" + neededBy + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.risk").value("AT_RISK"))
                .andExpect(jsonPath("$.daysLeft").value(4))
                .andReturn());
        mvc.perform(auth(get("/api/students/" + student + "/loans"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.applications.length()").value(1));
        mvc.perform(auth(get("/api/students/" + student + "/loans"), telecaller)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/loans"), loanDesk)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + loan + ")].risk").value("AT_RISK"));

        // The job alerts once per application and deadline, however often it runs.
        org.assertj.core.api.Assertions.assertThat(loanAlertJob.run()).isGreaterThanOrEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(loanAlertJob.run()).isZero();
        mvc.perform(auth(get("/api/notifications"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'LOAN_AT_RISK')]").isNotEmpty());

        mvc.perform(auth(put("/api/loans/" + loan), loanDesk).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerId\":" + partner + ",\"amountRequested\":1500000,\"status\":\"SANCTIONED\","
                                + "\"appliedOn\":\"" + LocalDate.now() + "\",\"neededBy\":\"" + neededBy + "\"}"))
                .andExpect(status().isBadRequest()); // needs the sanctioned amount
        mvc.perform(auth(put("/api/loans/" + loan), loanDesk).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerId\":" + partner + ",\"amountRequested\":1500000,\"amountSanctioned\":1200000,"
                                + "\"status\":\"SANCTIONED\",\"appliedOn\":\"" + LocalDate.now() + "\",\"neededBy\":\""
                                + neededBy + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risk").value("NONE"))
                .andExpect(jsonPath("$.decidedOn").isNotEmpty());
    }

    @Test
    void sessionsCallsAndScores() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String counsellor = login(user(admin, "COUNSELLOR"), "StaffPass123!");
        String other = login(user(admin, "COUNSELLOR"), "StaffPass123!");
        long student = student(counsellor);

        String at = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES).toString();
        mvc.perform(auth(post("/api/students/" + student + "/sessions"), other).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"VIDEO\",\"scheduledAt\":\"" + at + "\",\"durationMinutes\":30,\"topic\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/students/" + student + "/sessions"), counsellor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"VIDEO\",\"scheduledAt\":\"" + at + "\",\"durationMinutes\":30,\"topic\":\"x\","
                                + "\"meetingUrl\":\"http://insecure.example/room\"}"))
                .andExpect(status().isBadRequest());
        MvcResult created = mvc.perform(auth(post("/api/students/" + student + "/sessions"), counsellor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"VIDEO\",\"scheduledAt\":\"" + at + "\",\"durationMinutes\":30,"
                                + "\"topic\":\"Choice list review\",\"notifyFamily\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meetingUrl").value(Matchers.matchesPattern("https://meet\\.jit\\.si/counselling-[a-z2-9]{16}")))
                .andReturn();
        long session = id(created);
        // The invitation went to the family in the student's language (Tamil for this student).
        mvc.perform(auth(get("/api/students/" + student + "/messages"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.template == 'SESSION_INVITE')].body").value(
                        Matchers.hasItem(Matchers.containsString("ஆலோசனை அமர்வு"))));
        mvc.perform(auth(get("/api/sessions/mine"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + session + ")]").isNotEmpty());
        mvc.perform(auth(put("/api/sessions/" + session), counsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\",\"scheduledAt\":\"" + at + "\",\"durationMinutes\":30,"
                                + "\"topic\":\"Choice list review\",\"notes\":\"Agreed on 12 choices\","
                                + "\"recordingConsent\":false,\"recordingUrl\":\"https://rec.example/1\"}"))
                .andExpect(status().isBadRequest()); // no consent, no recording
        mvc.perform(auth(put("/api/sessions/" + session), counsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\",\"scheduledAt\":\"" + at + "\",\"durationMinutes\":30,"
                                + "\"topic\":\"Choice list review\",\"notes\":\"Agreed on 12 choices\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("Agreed on 12 choices"));

        // Calls: three that do not get through mark the student as harder to reach.
        for (String outcome : List.of("NO_ANSWER", "BUSY", "SWITCHED_OFF")) {
            mvc.perform(auth(post("/api/calls"), counsellor).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"studentId\":" + student + ",\"outcome\":\"" + outcome + "\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(auth(post("/api/calls"), counsellor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"CONNECTED\"}")).andExpect(status().isBadRequest());
        mvc.perform(auth(post("/api/calls"), other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":" + student + ",\"outcome\":\"CONNECTED\"}")).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/students/" + student + "/calls"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(auth(get("/api/students/" + student + "/risk"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(20))
                .andExpect(jsonPath("$.reasons[0]").value("Last 3 calls did not get through"));

        // A call to a lead shows up in its timeline and lifts its score.
        String phone = phone();
        long lead = id(mvc.perform(auth(post("/api/leads"), counsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Scored Lead\",\"phone\":\"" + phone + "\",\"source\":\"PHONE\"}"))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(auth(get("/api/leads/" + lead + "/score"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(30))
                .andExpect(jsonPath("$.nextAction").value("Not contacted yet: call now"));
        mvc.perform(auth(post("/api/calls"), counsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leadId\":" + lead + ",\"outcome\":\"CONNECTED\",\"durationSeconds\":240,"
                                + "\"notes\":\"Wants Tamil Nadu government colleges\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value(phone));
        mvc.perform(auth(get("/api/leads/" + lead + "/activities"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("CALL"))
                .andExpect(jsonPath("$[0].outcome").value("Connected"));
        mvc.perform(auth(get("/api/leads/" + lead + "/score"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(65))
                .andExpect(jsonPath("$.band").value("WARM"));
        mvc.perform(auth(get("/api/priorities"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.leads").isArray())
                .andExpect(jsonPath("$.students").isArray());
    }

    @Test
    void agreementsAreIssuedAcceptedInThePortalAndThenFrozen() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String counsellor = login(user(admin, "COUNSELLOR"), "StaffPass123!");
        String studentPhone = phone();
        long student = id(mvc.perform(auth(post("/api/students"), counsellor).contentType(MediaType.APPLICATION_JSON)
                .content(studentJson("Agreement Student", studentPhone))).andExpect(status().isCreated()).andReturn());

        MvcResult templates = mvc.perform(auth(get("/api/agreement-templates"), counsellor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(Matchers.greaterThanOrEqualTo(2))).andReturn();
        int template = ((List<Integer>) JsonPath.read(templates.getResponse().getContentAsString(),
                "$[?(@.kind == 'SERVICE_AGREEMENT')].id")).get(0);
        mvc.perform(auth(put("/api/agreement-templates/" + template), counsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SERVICE_AGREEMENT\",\"title\":\"x\",\"body\":\"y\",\"active\":true}"))
                .andExpect(status().isForbidden());

        long agreement = id(mvc.perform(auth(post("/api/students/" + student + "/agreements"), counsellor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"templateId\":" + template + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.body").value(Matchers.containsString("Agreement Student")))
                .andExpect(jsonPath("$.body").value(Matchers.not(Matchers.containsString("{{"))))
                .andReturn());
        mvc.perform(auth(post("/api/students/" + student + "/agreements"), counsellor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"templateId\":" + template + "}"))
                .andExpect(status().isConflict());

        // The family signs in to the portal and accepts it.
        MvcResult granted = mvc.perform(auth(post("/api/students/" + student + "/portal-access"), counsellor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"relation\":\"STUDENT\"}"))
                .andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(granted.getResponse().getContentAsString(), "$.activationCode");
        String portal = JsonPath.read(mvc.perform(post("/api/portal/auth/activate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + studentPhone + "\",\"code\":\"" + code + "\",\"password\":\"FamilyPass1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
        mvc.perform(auth(get("/api/portal/students/" + student), portal)).andExpect(status().isOk())
                .andExpect(jsonPath("$.agreements[0].status").value("PENDING"))
                .andExpect(jsonPath("$.loans").isArray())
                .andExpect(jsonPath("$.sessions").isArray());
        mvc.perform(auth(post("/api/portal/students/" + student + "/agreements/" + agreement + "/accept"), portal)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"typedName\":\"Agreement Student\",\"agreed\":false}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(post("/api/portal/students/" + student + "/agreements/" + agreement + "/accept"), portal)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"typedName\":\"Agreement Student\",\"agreed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"));

        mvc.perform(auth(get("/api/agreements/" + agreement), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.signMethod").value("PORTAL_ACCEPTANCE"))
                .andExpect(jsonPath("$.signerName").value("Agreement Student"))
                .andExpect(jsonPath("$.fingerprint").value(Matchers.matchesPattern("[0-9a-f]{16}")));
        // Signed means final: it cannot be cancelled or signed again.
        mvc.perform(auth(post("/api/agreements/" + agreement + "/cancel"), counsellor)).andExpect(status().isConflict());
        mvc.perform(auth(post("/api/agreements/" + agreement + "/paper"), counsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signerName\":\"Someone\",\"signerRelation\":\"PARENT\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void publicAssistantAnswersFromTheKnowledgeBaseAndCreatesALead() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");

        mvc.perform(get("/api/public/assistant/faq?language=TAMIL")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(Matchers.greaterThanOrEqualTo(3)));
        mvc.perform(post("/api/public/assistant/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"ENGLISH\",\"question\":\"Can you guarantee me a seat?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer.answer").value(Matchers.startsWith("No.")));
        mvc.perform(post("/api/public/assistant/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"ENGLISH\",\"question\":\"zzz qqq\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(Matchers.nullValue()));
        // The public side cannot read the staff knowledge base or anything else.
        mvc.perform(get("/api/faq")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/leads")).andExpect(status().isUnauthorized());

        String phone = phone();
        String body = "{\"fullName\":\"Website Visitor\",\"phone\":\"" + phone + "\",\"neetScore\":520,\"category\":\"OBC\","
                + "\"homeState\":\"Tamil Nadu\",\"language\":\"TAMIL\",\"questions\":[\"Can you guarantee me a seat?\"]}";
        mvc.perform(post("/api/public/assistant/callback").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        MvcResult found = mvc.perform(auth(get("/api/leads?q=" + phone), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].source").value("WEBSITE_CHAT"))
                .andReturn();
        int lead = JsonPath.read(found.getResponse().getContentAsString(), "$.items[0].id");
        mvc.perform(auth(get("/api/leads/" + lead), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.languagePreference").value("TAMIL"))
                .andExpect(jsonPath("$.notes").value(Matchers.containsString("guarantee")));

        // Asking again with the same number adds to the same lead instead of creating a second one.
        mvc.perform(post("/api/public/assistant/callback").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        mvc.perform(auth(get("/api/leads?q=" + phone), admin)).andExpect(jsonPath("$.items.length()").value(1));
        // A bot that fills the hidden field is ignored without an error.
        String botPhone = phone();
        mvc.perform(post("/api/public/assistant/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Bot\",\"phone\":\"" + botPhone + "\",\"website\":\"http://spam.example\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(auth(get("/api/leads?q=" + botPhone), admin)).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(post("/api/public/assistant/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Bad\",\"phone\":\"12345\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(auth(get("/api/integrations"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'DIGILOCKER')].state").value("NOT_CONNECTED"));
    }

    // ------------------------------------------------------------------ helpers

    private long student(String token) throws Exception {
        return id(mvc.perform(auth(post("/api/students"), token).contentType(MediaType.APPLICATION_JSON)
                .content(studentJson("Student " + SEQ.incrementAndGet(), phone()))).andExpect(status().isCreated())
                .andReturn());
    }

    private static String phone() {
        return "9" + String.format("%09d", SEQ.incrementAndGet());
    }

    private static String studentJson(String name, String phone) {
        return """
                {"fullName":"%s","phone":"%s","category":"OBC","pwd":false,"homeState":"Tamil Nadu",
                 "domicileStatus":"DOMICILED","nationality":"INDIAN","nriSponsored":false,"neetQualified":true,
                 "languagePreference":"TAMIL"}""".formatted(name, phone);
    }

    private String user(String adminToken, String role) throws Exception {
        String email = role.toLowerCase() + SEQ.incrementAndGet() + "@adv.test";
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
