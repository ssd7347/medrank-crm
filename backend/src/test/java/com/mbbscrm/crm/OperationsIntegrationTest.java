package com.mbbscrm.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

/** Phase 3 end to end: documents, fees & refunds, commissions, helpdesk, grievances, refund rules, staff. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperationsIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(8000);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    @Autowired
    MockMvc mvc;

    @Test
    void documentsChecklistUploadVerifyAndAccess() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String counsellor = login(user(admin, "COUNSELLOR"), "StaffPass123!");
        String docExec = login(user(admin, "DOCUMENTATION_EXEC"), "StaffPass123!");
        String telecaller = login(user(admin, "TELECALLER"), "StaffPass123!");
        long student = student(counsellor, "OBC");

        MvcResult list = mvc.perform(auth(get("/api/students/" + student + "/documents"), docExec))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.code == 'CATEGORY_CERT')].required").value(true))
                .andExpect(jsonPath("$.items[?(@.code == 'EWS_CERT')]").isEmpty())
                .andExpect(jsonPath("$.missingRequired.length()").value(Matchers.greaterThan(5)))
                .andReturn();
        Integer catType = ((List<Integer>) JsonPath.read(list.getResponse().getContentAsString(),
                "$.items[?(@.code == 'CATEGORY_CERT')].typeId")).get(0);

        mvc.perform(auth(get("/api/students/" + student + "/documents"), telecaller)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/students/" + student + "/documents/" + catType + "/files")
                        .file(new MockMultipartFile("file", "cert.html", "image/png", "<html>".getBytes()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + docExec))
                .andExpect(status().isBadRequest());
        MvcResult up = mvc.perform(multipart("/api/students/" + student + "/documents/" + catType + "/files")
                        .file(new MockMultipartFile("file", "community cert.png", "application/octet-stream", PNG))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + docExec))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.code == 'CATEGORY_CERT')].status").value("COLLECTED"))
                .andReturn();
        Integer fileId = ((List<Integer>) JsonPath.read(up.getResponse().getContentAsString(),
                "$.items[?(@.code == 'CATEGORY_CERT')].files[0].id")).get(0);
        mvc.perform(auth(get("/api/document-files/" + fileId), counsellor))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
        mvc.perform(auth(get("/api/document-files/" + fileId), telecaller)).andExpect(status().isForbidden());

        mvc.perform(auth(get("/api/documents/queue"), docExec)).andExpect(status().isOk())
                .andExpect(jsonPath("$.awaitingVerification[?(@.studentId == " + student + ")]").isNotEmpty());
        String until = LocalDate.now().plusDays(10).toString();
        mvc.perform(auth(put("/api/students/" + student + "/documents/" + catType), docExec)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"VERIFIED\",\"validUntil\":\"" + until + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.code == 'CATEGORY_CERT')].verifiedBy.fullName").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.code == 'CATEGORY_CERT')].expiringSoon").value(true));
    }

    @Test
    void feesPaymentsRefundsAndCommission() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String counsellor = login(user(admin, "COUNSELLOR"), "StaffPass123!");
        String accountant = login(user(admin, "ACCOUNTANT"), "StaffPass123!");

        long pkg = id(mvc.perform(auth(post("/api/fee-packages"), accountant).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Premium counselling","totalAmount":50000,"active":true,"installments":[
                          {"label":"Registration","amount":20000,"dueOffsetDays":0},
                          {"label":"After allotment","amount":30000,"dueOffsetDays":30}]}"""))
                .andExpect(status().isCreated()).andReturn());

        // Referral lead -> student -> fee plan -> admission confirmed creates a commission.
        long associate = id(mvc.perform(auth(post("/api/referral-associates"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Madurai Agent\",\"phone\":\"9" + String.format("%09d", SEQ.incrementAndGet())
                        + "\",\"district\":\"Madurai\",\"commissionRate\":10,\"active\":true}"))
                .andExpect(status().isCreated()).andReturn());
        String phone = "9" + String.format("%09d", SEQ.incrementAndGet());
        long lead = id(mvc.perform(auth(post("/api/leads"), counsellor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Referred Kid\",\"phone\":\"" + phone + "\",\"source\":\"REFERRAL_ASSOCIATE\","
                        + "\"referralAssociateId\":" + associate + "}"))
                .andExpect(status().isCreated()).andReturn());
        MvcResult conv = mvc.perform(auth(post("/api/leads/" + lead + "/convert"), counsellor)
                .contentType(MediaType.APPLICATION_JSON).content(studentJson("Referred Kid", phone, "GEN")))
                .andExpect(status().isOk()).andReturn();
        long student = ((Number) JsonPath.read(conv.getResponse().getContentAsString(), "$.studentId")).longValue();

        mvc.perform(auth(post("/api/students/" + student + "/fee-plans"), counsellor)
                .contentType(MediaType.APPLICATION_JSON).content("{\"packageId\":" + pkg + "}"))
                .andExpect(status().isForbidden());
        MvcResult plan = mvc.perform(auth(post("/api/students/" + student + "/fee-plans"), accountant)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"packageId\":" + pkg + ",\"discount\":5000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.netAmount").value(45000.0))
                .andExpect(jsonPath("$.installments.length()").value(2))
                .andReturn();
        long planId = id(plan);

        mvc.perform(auth(post("/api/fee-plans/" + planId + "/payments"), accountant).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50000,\"method\":\"CASH\",\"paidOn\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(post("/api/fee-plans/" + planId + "/payments"), accountant).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10000,\"method\":\"UPI\",\"paidOn\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().isBadRequest()); // UPI needs a reference
        MvcResult pay = mvc.perform(auth(post("/api/fee-plans/" + planId + "/payments"), accountant)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":20000,\"method\":\"UPI\","
                                + "\"reference\":\"UTR123\",\"paidOn\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptNo").value(Matchers.startsWith("RC-")))
                .andReturn();
        long paymentId = id(pay);
        mvc.perform(auth(get("/api/payments/" + paymentId + "/receipt"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(25000.0));
        mvc.perform(auth(get("/api/students/" + student + "/fees"), counsellor)).andExpect(status().isOk())
                // Discount scales instalments to 18,000 + 27,000: the 20,000 paid clears the first and part-pays the second.
                .andExpect(jsonPath("$[0].installments[0].state").value("PAID"))
                .andExpect(jsonPath("$[0].installments[1].state").value("PARTIAL"));

        // Void: admin only.
        mvc.perform(auth(post("/api/payments/" + paymentId + "/void"), accountant).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"typo\"}")).andExpect(status().isForbidden());

        // Refund workflow: counsellor requests, admin approves, accounts pays out.
        mvc.perform(auth(post("/api/fee-plans/" + planId + "/refunds"), counsellor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":25000,\"reason\":\"too much\"}")).andExpect(status().isBadRequest());
        long refund = id(mvc.perform(auth(post("/api/fee-plans/" + planId + "/refunds"), counsellor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5000,\"reason\":\"Service not used\"}"))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(auth(post("/api/refunds/" + refund + "/decide"), accountant).contentType(MediaType.APPLICATION_JSON)
                .content("{\"approve\":true}")).andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/refunds/" + refund + "/decide"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"approve\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(auth(post("/api/refunds/" + refund + "/paid"), accountant).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidOn\":\"" + LocalDate.now() + "\",\"reference\":\"NEFT9\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));

        mvc.perform(auth(get("/api/fees/dues"), accountant)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.planId == " + planId + ")]").isNotEmpty());

        mvc.perform(auth(post("/api/leads/" + lead + "/status"), counsellor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ADMISSION_CONFIRMED\"}")).andExpect(status().isOk());
        mvc.perform(auth(get("/api/commissions"), accountant)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.leadId == " + lead + ")].amount").value(4500.0));
    }

    @Test
    void helpdeskAndGrievanceRegister() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String counsellor = login(user(admin, "COUNSELLOR"), "StaffPass123!");
        String telecaller = login(user(admin, "TELECALLER"), "StaffPass123!");
        String officer = login(user(admin, "GRIEVANCE_OFFICER"), "StaffPass123!");
        long student = student(counsellor, "GEN");

        MvcResult t = mvc.perform(auth(post("/api/tickets"), counsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":" + student + ",\"raisedVia\":\"PHONE\",\"subject\":\"Choice list doubt\","
                                + "\"category\":\"COUNSELLING\",\"priority\":\"NORMAL\",\"deadlineLinked\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andReturn();
        long ticket = id(t);
        Instant due = Instant.parse(JsonPath.read(t.getResponse().getContentAsString(), "$.dueAt"));
        assertThat(due).isBefore(Instant.now().plus(4, ChronoUnit.HOURS).plusSeconds(60));

        mvc.perform(auth(get("/api/tickets/" + ticket), telecaller)).andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/tickets/" + ticket + "/comments"), counsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Called back, family upset about fees\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.comments.length()").value(1));
        mvc.perform(auth(put("/api/tickets/" + ticket), counsellor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RESOLVED\",\"priority\":\"HIGH\"}")).andExpect(status().isBadRequest());

        // Escalate the ticket into the grievance register.
        long grievance = id(mvc.perform(auth(post("/api/grievances"), counsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketId\":" + ticket + ",\"category\":\"FEE_REFUND\",\"description\":\"Wants refund\","
                                + "\"amountInDispute\":20000,\"receivedVia\":\"PHONE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.referenceNo").value(Matchers.startsWith("GRV-")))
                .andExpect(jsonPath("$.trail[0].type").value("CREATED"))
                .andReturn());
        mvc.perform(auth(get("/api/tickets/" + ticket), counsellor)).andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(auth(get("/api/grievances/" + grievance), counsellor)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/grievances/" + grievance), telecaller)).andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/grievances/" + grievance + "/actions"), counsellor)
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"NOTE\",\"details\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/grievances/" + grievance + "/actions"), officer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"CONTACTED\",\"details\":\"Spoke to father\"}"))
                .andExpect(status().isOk());
        mvc.perform(auth(post("/api/grievances/" + grievance + "/actions"), officer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"ESCALATED\",\"details\":\"Needs owner call\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ESCALATED"));
        mvc.perform(auth(post("/api/grievances/" + grievance + "/actions"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"RESOLVED\",\"details\":\"Partial refund agreed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.trail.length()").value(4));
        mvc.perform(auth(get("/api/notifications"), admin))
                .andExpect(jsonPath("$[?(@.type == 'GRIEVANCE_ESCALATED')]").isNotEmpty());
    }

    @Test
    void refundRulesDriveTheWithdrawPreviewAndStaffTools() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String dataExec = login(user(admin, "DATA_EXEC"), "StaffPass123!");
        String counsellorEmail = user(admin, "COUNSELLOR");
        String counsellor = login(counsellorEmail, "StaffPass123!");
        String code = "RR" + SEQ.incrementAndGet();
        long college = ((Number) JsonPath.read(mvc.perform(auth(post("/api/change-requests"), admin)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"entityType":"COLLEGE","action":"CREATE","payload":{"name":"Rule College %s","code":"%s",
                         "collegeType":"PRIVATE","state":"Tamil Nadu","nmcRecognized":true}}""".formatted(code, code)))
                .andReturn().getResponse().getContentAsString(), "$.entityId")).longValue();
        MvcResult auths = mvc.perform(auth(get("/api/counselling/authorities"), admin)).andReturn();
        long tn = ((List<Integer>) JsonPath.read(auths.getResponse().getContentAsString(), "$[?(@.code == 'TN-SELECTION')].id")).get(0);

        // Round 2 of a separate year keeps this test independent of the others.
        Instant now = Instant.now();
        long round = id(mvc.perform(auth(post("/api/counselling/rounds"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"authorityId":%d,"academicYear":2027,"roundType":"ROUND_2","reportingEnd":"%s"}"""
                        .formatted(tn, now.plus(5, ChronoUnit.DAYS)))).andExpect(status().isCreated()).andReturn());

        long request = id(mvc.perform(auth(post("/api/change-requests"), dataExec).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entityType":"REFUND_RULE","action":"CREATE","payload":{"authorityId":%d,
                                 "roundType":"ROUND_2","academicYear":2027,"depositForfeited":true,"depositAmount":100000,
                                 "tuitionRefundPercent":0,"barredFromLaterRounds":true,
                                 "source":"TN prospectus 2027, clause 12"}}""".formatted(tn)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")).andReturn());
        mvc.perform(auth(post("/api/change-requests/" + request + "/approve"), admin)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/refund-rules").param("year", "2027"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].source").value("TN prospectus 2027, clause 12"));

        long student = student(counsellor, "GEN");
        long track = id(mvc.perform(auth(post("/api/students/" + student + "/counselling"), counsellor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"authorityId\":" + tn + ",\"academicYear\":2027,\"status\":\"REGISTERED\"}"))
                .andExpect(status().isCreated()).andReturn());
        long allotment = id(mvc.perform(auth(post("/api/counselling/tracks/" + track + "/allotments"), counsellor)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"roundId":%d,"collegeId":%d,"course":"MBBS","quota":"MANAGEMENT"}""".formatted(round, college)))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(auth(get("/api/allotments/" + allotment + "/decision-preview").param("decision", "WITHDRAW"), counsellor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundRuleFound").value(true))
                .andExpect(jsonPath("$.consequences[0]").value(Matchers.containsString("100000")))
                .andExpect(jsonPath("$.consequences", Matchers.hasItem(Matchers.containsString("clause 12"))));

        // Staff tools: performance view and bulk reassignment.
        mvc.perform(auth(get("/api/staff/performance"), counsellor)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/staff/performance"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName").isNotEmpty());
        String other = user(admin, "COUNSELLOR");
        MvcResult users = mvc.perform(auth(get("/api/users"), admin)).andReturn();
        String body = users.getResponse().getContentAsString();
        long fromId = ((List<Integer>) JsonPath.read(body, "$[?(@.email == '" + counsellorEmail + "')].id")).get(0);
        long toId = ((List<Integer>) JsonPath.read(body, "$[?(@.email == '" + other + "')].id")).get(0);
        mvc.perform(auth(post("/api/staff/reassign"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromUserId\":" + fromId + ",\"toUserId\":" + toId + ",\"leads\":true,\"students\":true,"
                                + "\"followUps\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students").value(1));
        mvc.perform(auth(get("/api/students/" + student), counsellor)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private long student(String token, String category) throws Exception {
        int n = SEQ.incrementAndGet();
        return id(mvc.perform(auth(post("/api/students"), token).contentType(MediaType.APPLICATION_JSON)
                .content(studentJson("Student " + n, "9" + String.format("%09d", n), category)))
                .andExpect(status().isCreated()).andReturn());
    }

    private static String studentJson(String name, String phone, String category) {
        return """
                {"fullName":"%s","phone":"%s","category":"%s","pwd":false,"homeState":"Tamil Nadu",
                 "domicileStatus":"DOMICILED","nationality":"INDIAN","nriSponsored":false,"neetQualified":true,
                 "languagePreference":"ENGLISH"}""".formatted(name, phone, category);
    }

    private String user(String adminToken, String role) throws Exception {
        String email = role.toLowerCase() + SEQ.incrementAndGet() + "@ops.test";
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
