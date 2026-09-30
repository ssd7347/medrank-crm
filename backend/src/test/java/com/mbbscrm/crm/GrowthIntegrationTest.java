package com.mbbscrm.crm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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

/** Phase 4 end to end: branches, marketing, sub-agents, alumni, analytics & reports, family portal. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GrowthIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(6000);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    @Autowired
    MockMvc mvc;

    @Test
    void branchStaffOnlySeeTheirOwnBranch() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        long chennai = branch(admin, "Chennai");
        long madurai = branch(admin, "Madurai");
        String chennaiCounsellor = login(user(admin, "COUNSELLOR", chennai), "StaffPass123!");
        String maduraiCounsellor = login(user(admin, "COUNSELLOR", madurai), "StaffPass123!");
        String maduraiDocs = login(user(admin, "DOCUMENTATION_EXEC", madurai), "StaffPass123!");
        String headOfficeDocs = login(user(admin, "DOCUMENTATION_EXEC", null), "StaffPass123!");

        // A branch counsellor cannot place a lead in another branch: theirs is always used.
        String phone = phone();
        long lead = id(mvc.perform(auth(post("/api/leads"), chennaiCounsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Chennai Lead\",\"phone\":\"" + phone + "\",\"source\":\"WALK_IN\","
                                + "\"branchId\":" + madurai + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.branch.id").value((int) chennai))
                .andReturn());
        mvc.perform(auth(get("/api/leads/" + lead), maduraiCounsellor)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/leads?q=" + phone), maduraiCounsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(auth(get("/api/leads?branchId=" + chennai + "&q=" + phone), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(auth(get("/api/leads?branchId=" + madurai + "&q=" + phone), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        // The student created from the lead stays in the lead's branch.
        MvcResult conv = mvc.perform(auth(post("/api/leads/" + lead + "/convert"), chennaiCounsellor)
                        .contentType(MediaType.APPLICATION_JSON).content(studentJson("Chennai Lead", phone)))
                .andExpect(status().isOk()).andReturn();
        long student = ((Number) JsonPath.read(conv.getResponse().getContentAsString(), "$.studentId")).longValue();
        mvc.perform(auth(get("/api/students/" + student), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.branch.id").value((int) chennai));
        mvc.perform(auth(get("/api/students/" + student + "/documents"), maduraiDocs)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/students/" + student + "/documents"), headOfficeDocs)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/students?branchId=" + madurai), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == " + student + ")]").isEmpty());

        mvc.perform(auth(post("/api/branches"), chennaiCounsellor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"code\":\"XX\",\"active\":true}")).andExpect(status().isForbidden());
    }

    @Test
    void marketingReportAssociatesAndAlumni() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String counsellor = login(user(admin, "COUNSELLOR", null), "StaffPass123!");

        long campaign = id(mvc.perform(auth(post("/api/marketing/campaigns"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NEET seminar " + SEQ.incrementAndGet() + "\",\"channel\":\"SEMINAR\","
                                + "\"budget\":20000,\"active\":true}"))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(auth(post("/api/marketing/campaigns/" + campaign + "/spend"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spentOn\":\"" + LocalDate.now() + "\",\"amount\":9000,\"note\":\"Hall\"}"))
                .andExpect(status().isCreated());
        mvc.perform(auth(post("/api/marketing/campaigns/" + campaign + "/spend"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spentOn\":\"" + LocalDate.now().plusDays(3) + "\",\"amount\":100}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/marketing/campaign-options"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + campaign + ")]").isNotEmpty());
        mvc.perform(auth(get("/api/marketing/report"), counsellor)).andExpect(status().isForbidden());

        // Three tagged leads, one of them admitted: cost per lead 3000, cost per admission 9000.
        long admittedLead = 0;
        String admittedPhone = null;
        for (int i = 0; i < 3; i++) {
            String p = phone();
            long l = id(mvc.perform(auth(post("/api/leads"), counsellor).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fullName\":\"Seminar Lead\",\"phone\":\"" + p + "\",\"source\":\"SEMINAR\","
                                    + "\"campaignId\":" + campaign + "}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.campaign.id").value((int) campaign)).andReturn());
            admittedLead = l;
            admittedPhone = p;
        }
        MvcResult conv = mvc.perform(auth(post("/api/leads/" + admittedLead + "/convert"), counsellor)
                        .contentType(MediaType.APPLICATION_JSON).content(studentJson("Seminar Lead", admittedPhone)))
                .andExpect(status().isOk()).andReturn();
        long student = ((Number) JsonPath.read(conv.getResponse().getContentAsString(), "$.studentId")).longValue();
        mvc.perform(auth(post("/api/leads/" + admittedLead + "/status"), counsellor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ADMISSION_CONFIRMED\"}")).andExpect(status().isOk());

        mvc.perform(auth(get("/api/marketing/report"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.campaigns[?(@.id == " + campaign + ")].leads").value(3))
                .andExpect(jsonPath("$.campaigns[?(@.id == " + campaign + ")].admissions").value(1))
                .andExpect(jsonPath("$.campaigns[?(@.id == " + campaign + ")].costPerLead").value(3000))
                .andExpect(jsonPath("$.campaigns[?(@.id == " + campaign + ")].costPerAdmission").value(9000))
                .andExpect(jsonPath("$.channels[?(@.channel == 'SEMINAR')].campaignSpend").value(
                        Matchers.hasItem(Matchers.greaterThanOrEqualTo(9000.0))));

        // Sub-agent directory with territory and agreement, and the performance view.
        mvc.perform(auth(post("/api/referral-associates"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Salem Agent\",\"phone\":\"" + phone() + "\",\"district\":\"Salem\","
                                + "\"commissionRate\":8,\"active\":true,\"territory\":\"Salem, Namakkal\","
                                + "\"agreementStart\":\"2026-01-01\",\"agreementEnd\":\"2025-01-01\"}"))
                .andExpect(status().isBadRequest());
        long associate = id(mvc.perform(auth(post("/api/referral-associates"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Salem Agent\",\"phone\":\"" + phone() + "\",\"district\":\"Salem\","
                                + "\"commissionRate\":8,\"active\":true,\"territory\":\"Salem, Namakkal\","
                                + "\"agreementStart\":\"2026-01-01\",\"agreementEnd\":\"2027-01-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.territory").value("Salem, Namakkal")).andReturn());
        mvc.perform(auth(get("/api/referral-associates/performance"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.associate.id == " + associate + ")].leads").value(0));
        mvc.perform(auth(get("/api/referral-associates/performance"), counsellor)).andExpect(status().isForbidden());

        // Alumni: directory entry, survey, a referral from the alumnus, and testimonial approval rules.
        long alumni = ((Number) JsonPath.read(mvc.perform(auth(post("/api/students/" + student + "/alumni"), counsellor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collegeName\":\"Madras Medical College\",\"course\":\"MBBS\",\"quota\":\"STATE\","
                                + "\"admissionYear\":2026,\"willingToRefer\":true}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.alumni.id"))
                .longValue();
        mvc.perform(auth(post("/api/students/" + student + "/alumni"), counsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collegeName\":\"X\",\"course\":\"MBBS\",\"admissionYear\":2026}"))
                .andExpect(status().isConflict());
        mvc.perform(auth(post("/api/alumni/" + alumni + "/surveys"), counsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overallRating\":9,\"wouldRecommend\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(post("/api/alumni/" + alumni + "/surveys"), counsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overallRating\":5,\"counsellorRating\":4,\"wouldRecommend\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alumni.latestRating").value(5));
        mvc.perform(auth(post("/api/leads"), counsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Friend\",\"phone\":\"" + phone() + "\",\"source\":\"PAST_STUDENT_REFERRAL\","
                                + "\"referredByStudentId\":" + student + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.referredByStudent.id").value((int) student));
        MvcResult t = mvc.perform(auth(post("/api/alumni/" + alumni + "/testimonials"), counsellor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quote\":\"Great guidance\",\"consent\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alumni.referrals").value(1)).andReturn();
        int testimonial = JsonPath.read(t.getResponse().getContentAsString(), "$.testimonials[0].id");
        mvc.perform(auth(post("/api/testimonials/" + testimonial + "/approve"), counsellor))
                .andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/testimonials/" + testimonial + "/approve"), admin))
                .andExpect(status().isBadRequest()); // no consent
        mvc.perform(auth(get("/api/alumni"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.total").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.rows[?(@.id == " + alumni + ")].referrals").value(1));
        mvc.perform(auth(get("/api/students/" + student + "/alumni"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.alumni.alumni.collegeName").value("Madras Medical College"));

        // Analytics and the report builder are for the owner only.
        mvc.perform(auth(get("/api/analytics/overview"), counsellor)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/analytics/overview"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.funnel[0].stage").value("Inquiries"))
                .andExpect(jsonPath("$.funnel[0].count").value(Matchers.greaterThanOrEqualTo(4)))
                .andExpect(jsonPath("$.funnel[4].count").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.revenue.monthly.length()").value(12))
                .andExpect(jsonPath("$.byCategory[0].students").value(Matchers.greaterThanOrEqualTo(1)));
        for (String dataset : List.of("LEADS", "STUDENTS", "PAYMENTS", "ADMISSIONS", "ALLOTMENTS", "TICKETS")) {
            mvc.perform(auth(get("/api/analytics/reports/" + dataset), admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.columns.length()").value(Matchers.greaterThan(5)));
        }
        mvc.perform(auth(get("/api/analytics/reports/ADMISSIONS"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@[2] == 'Madras Medical College')]").isNotEmpty());
        mvc.perform(auth(get("/api/analytics/reports/LEADS"), counsellor)).andExpect(status().isForbidden());
    }

    @Test
    void familyPortalIsSeparateFromStaffAccess() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String counsellor = login(user(admin, "COUNSELLOR", null), "StaffPass123!");
        String otherCounsellor = login(user(admin, "COUNSELLOR", null), "StaffPass123!");
        String studentPhone = phone();
        long student = id(mvc.perform(auth(post("/api/students"), counsellor).contentType(MediaType.APPLICATION_JSON)
                .content(studentJson("Portal Student", studentPhone))).andExpect(status().isCreated()).andReturn());
        long otherStudent = id(mvc.perform(auth(post("/api/students"), otherCounsellor)
                .contentType(MediaType.APPLICATION_JSON).content(studentJson("Someone Else", phone())))
                .andExpect(status().isCreated()).andReturn());

        mvc.perform(auth(post("/api/students/" + student + "/portal-access"), counsellor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"relation\":\"PARENT\"}"))
                .andExpect(status().isBadRequest()); // no parent phone on the profile
        mvc.perform(auth(post("/api/students/" + student + "/portal-access"), otherCounsellor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"relation\":\"STUDENT\"}"))
                .andExpect(status().isNotFound());
        MvcResult granted = mvc.perform(auth(post("/api/students/" + student + "/portal-access"), counsellor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"relation\":\"STUDENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access.activated").value(false))
                .andExpect(jsonPath("$.activationCode").value(Matchers.matchesPattern("[A-Z2-9]{8}")))
                .andReturn();
        String code = JsonPath.read(granted.getResponse().getContentAsString(), "$.activationCode");
        int account = JsonPath.read(granted.getResponse().getContentAsString(), "$.access.accountId");

        // Cannot log in before activating; a wrong code is refused; a short password is refused.
        mvc.perform(post("/api/portal/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + studentPhone + "\",\"password\":\"whatever123\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/portal/auth/activate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + studentPhone + "\",\"code\":\"AAAAAAAA\",\"password\":\"FamilyPass1\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/portal/auth/activate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + studentPhone + "\",\"code\":\"" + code + "\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
        MvcResult session = mvc.perform(post("/api/portal/auth/activate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + studentPhone + "\",\"code\":\"" + code.toLowerCase()
                                + "\",\"password\":\"FamilyPass1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Portal Student"))
                .andReturn();
        String portal = JsonPath.read(session.getResponse().getContentAsString(), "$.accessToken");
        // The code works once only.
        mvc.perform(post("/api/portal/auth/activate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + studentPhone + "\",\"code\":\"" + code + "\",\"password\":\"FamilyPass2\"}"))
                .andExpect(status().isUnauthorized());

        // A portal login cannot use staff endpoints, and staff cannot use the portal API.
        mvc.perform(auth(get("/api/students/" + student), portal)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/leads"), portal)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/auth/me"), portal)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/portal/me"), admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/portal/me")).andExpect(status().isUnauthorized());

        mvc.perform(auth(get("/api/portal/me"), portal)).andExpect(status().isOk())
                .andExpect(jsonPath("$.students.length()").value(1))
                .andExpect(jsonPath("$.students[0].id").value((int) student));
        MvcResult overview = mvc.perform(auth(get("/api/portal/students/" + student), portal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.student.fullName").value("Portal Student"))
                .andExpect(jsonPath("$.student.counsellorName").isNotEmpty())
                .andExpect(jsonPath("$.documents.requiredCount").value(Matchers.greaterThan(5)))
                .andReturn();
        mvc.perform(auth(get("/api/portal/students/" + otherStudent), portal)).andExpect(status().isNotFound());

        // Upload lands in the staff checklist as "collected", flagged as coming from the portal.
        int typeId = JsonPath.read(overview.getResponse().getContentAsString(), "$.documents.items[0].typeId");
        mvc.perform(multipart("/api/portal/students/" + student + "/documents/" + typeId + "/files")
                        .file(new MockMultipartFile("file", "scan.exe", "image/png", "MZ".getBytes()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + portal))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/portal/students/" + otherStudent + "/documents/" + typeId + "/files")
                        .file(new MockMultipartFile("file", "scan.png", "image/png", PNG))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + portal))
                .andExpect(status().isNotFound());
        mvc.perform(multipart("/api/portal/students/" + student + "/documents/" + typeId + "/files")
                        .file(new MockMultipartFile("file", "scan.png", "image/png", PNG))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + portal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("COLLECTED"))
                .andExpect(jsonPath("$.items[0].files").value(1));
        mvc.perform(auth(get("/api/students/" + student + "/documents"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].files[0].fromPortal").value(true));

        // A question becomes a helpdesk ticket for the counsellor.
        MvcResult q = mvc.perform(auth(post("/api/portal/students/" + student + "/questions"), portal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"When is round 2?\",\"category\":\"COUNSELLING\"}"))
                .andExpect(status().isCreated()).andReturn();
        int ticket = JsonPath.read(q.getResponse().getContentAsString(), "$.id");
        mvc.perform(auth(get("/api/tickets/" + ticket), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.raisedVia").value("PORTAL"))
                .andExpect(jsonPath("$.assignedTo.fullName").isNotEmpty());

        // Password login works; resetting access from the staff side kills the password.
        mvc.perform(post("/api/portal/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + studentPhone + "\",\"password\":\"FamilyPass1\"}"))
                .andExpect(status().isOk());
        mvc.perform(auth(post("/api/students/" + student + "/portal-access/" + account + "/reset"), counsellor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activationCode").isNotEmpty())
                .andExpect(jsonPath("$.access.activated").value(false));
        mvc.perform(post("/api/portal/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + studentPhone + "\",\"password\":\"FamilyPass1\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private long branch(String admin, String name) throws Exception {
        return id(mvc.perform(auth(post("/api/branches"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"code\":\"B" + SEQ.incrementAndGet() + "\",\"active\":true}"))
                .andExpect(status().isCreated()).andReturn());
    }

    private static String phone() {
        return "9" + String.format("%09d", SEQ.incrementAndGet());
    }

    private static String studentJson(String name, String phone) {
        return """
                {"fullName":"%s","phone":"%s","category":"OBC","pwd":false,"homeState":"Tamil Nadu",
                 "domicileStatus":"DOMICILED","nationality":"INDIAN","nriSponsored":false,"neetQualified":true,
                 "languagePreference":"ENGLISH"}""".formatted(name, phone);
    }

    private String user(String adminToken, String role, Long branchId) throws Exception {
        String email = role.toLowerCase() + SEQ.incrementAndGet() + "@growth.test";
        mvc.perform(auth(post("/api/users"), adminToken).contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"%s user","email":"%s","role":"%s","password":"StaffPass123!","branchId":%s}"""
                        .formatted(role, email, role, branchId)))
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
