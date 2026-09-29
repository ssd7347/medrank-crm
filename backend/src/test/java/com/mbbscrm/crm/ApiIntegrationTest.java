package com.mbbscrm.crm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

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

import jakarta.servlet.http.Cookie;

/** End-to-end API tests against H2 (PostgreSQL mode) with the real Flyway schema. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    private static final String ADMIN = "admin@test.local";
    private static final String ADMIN_PW = "AdminPass123!";
    private static final String PW = "StaffPass123!";
    private static final AtomicInteger SEQ = new AtomicInteger(100);

    @Autowired
    MockMvc mvc;

    // ------------------------------------------------------------------ auth

    @Test
    void loginRefreshAndProtectedEndpoints() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", ADMIN, "password", "wrong-password")))
                .andExpect(status().isUnauthorized());

        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", ADMIN, "password", ADMIN_PW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("SUPER_ADMIN"))
                .andExpect(cookie().httpOnly("crm_refresh", true))
                .andReturn();
        String token = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
        mvc.perform(auth(get("/api/auth/me"), token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(ADMIN));

        Cookie refresh = login.getResponse().getCookie("crm_refresh");
        MvcResult rotated = mvc.perform(post("/api/auth/refresh").cookie(refresh))
                .andExpect(status().isOk()).andReturn();
        // The old refresh token is single-use; replaying it is treated as theft and kills all sessions.
        mvc.perform(post("/api/auth/refresh").cookie(refresh)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(rotated.getResponse().getCookie("crm_refresh")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedFailuresLockTheAccount() throws Exception {
        String admin = login(ADMIN, ADMIN_PW);
        String email = createUser(admin, "TELECALLER");
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(json("email", email, "password", "bad-password-" + i)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", email, "password", PW))).andExpect(status().isTooManyRequests());
    }

    @Test
    void onlyAdminsManageUsers() throws Exception {
        String admin = login(ADMIN, ADMIN_PW);
        String counsellor = login(createUser(admin, "COUNSELLOR"), PW);
        mvc.perform(auth(get("/api/users"), counsellor)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/users/assignable"), counsellor)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ leads & students

    @Test
    void leadLifecycleWithDuplicatesScopingAndConversion() throws Exception {
        String admin = login(ADMIN, ADMIN_PW);
        String c1 = login(createUser(admin, "COUNSELLOR"), PW);
        String c2 = login(createUser(admin, "COUNSELLOR"), PW);
        String phone = uniquePhone();
        String roll = "R" + SEQ.incrementAndGet();

        long leadId = id(mvc.perform(auth(post("/api/leads"), c1).contentType(MediaType.APPLICATION_JSON)
                        .content(leadJson("Priya S", phone, roll, false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.assignedCounsellor.id").isNumber())
                .andReturn());

        // Same phone -> warned with the duplicate; can be overridden. Same roll number -> always blocked.
        mvc.perform(auth(post("/api/leads"), c1).contentType(MediaType.APPLICATION_JSON)
                        .content(leadJson("Priya's sibling", "+91 " + phone, null, false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.duplicates[0].id").value(leadId))
                .andExpect(jsonPath("$.canOverride").value(true));
        mvc.perform(auth(post("/api/leads"), c1).contentType(MediaType.APPLICATION_JSON)
                        .content(leadJson("Priya's sibling", phone, null, true)))
                .andExpect(status().isCreated());
        mvc.perform(auth(post("/api/leads"), c1).contentType(MediaType.APPLICATION_JSON)
                        .content(leadJson("Someone else", uniquePhone(), roll, true)))
                .andExpect(status().isConflict());

        // Another counsellor cannot see or touch it.
        mvc.perform(auth(get("/api/leads/" + leadId), c2)).andExpect(status().isNotFound());

        mvc.perform(auth(post("/api/leads/" + leadId + "/status"), c1).contentType(MediaType.APPLICATION_JSON)
                        .content(json("status", "ADMISSION_CONFIRMED")))
                .andExpect(status().isBadRequest());

        mvc.perform(auth(post("/api/leads/" + leadId + "/activities"), c1).contentType(MediaType.APPLICATION_JSON)
                        .content(json("type", "CALL", "outcome", "Interested", "notes", "Wants TN govt seats")))
                .andExpect(status().isCreated());

        MvcResult converted = mvc.perform(auth(post("/api/leads/" + leadId + "/convert"), c1)
                        .contentType(MediaType.APPLICATION_JSON).content(studentJson("Priya S", phone, roll)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUALIFIED"))
                .andReturn();
        Number studentId = JsonPath.read(converted.getResponse().getContentAsString(), "$.studentId");

        mvc.perform(auth(get("/api/students/" + studentId), c1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.leadId").value(leadId))
                .andExpect(jsonPath("$.eligibility.quotas[0].quota").value("AIQ"))
                .andExpect(jsonPath("$.eligibility.quotas[0].eligible").value(true));
        mvc.perform(auth(get("/api/students/" + studentId), c2)).andExpect(status().isNotFound());

        mvc.perform(auth(post("/api/leads/" + leadId + "/status"), c1).contentType(MediaType.APPLICATION_JSON)
                .content(json("status", "ACTIVELY_COUNSELLED"))).andExpect(status().isOk());
        mvc.perform(auth(get("/api/leads/" + leadId + "/activities"), c1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mvc.perform(auth(get("/api/dashboard"), c1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.leadsByStatus.ACTIVELY_COUNSELLED").value(1))
                .andExpect(jsonPath("$.students").value(1));
    }

    @Test
    void telecallerCannotReadStudentsAndDataExecCannotReadLeads() throws Exception {
        String admin = login(ADMIN, ADMIN_PW);
        String telecaller = login(createUser(admin, "TELECALLER"), PW);
        String dataExec = login(createUser(admin, "DATA_EXEC"), PW);
        mvc.perform(auth(get("/api/students"), telecaller)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/leads"), dataExec)).andExpect(status().isForbidden());
    }

    @Test
    void followUpsShowUpForTheOwner() throws Exception {
        String admin = login(ADMIN, ADMIN_PW);
        String tc = login(createUser(admin, "TELECALLER"), PW);
        long leadId = id(mvc.perform(auth(post("/api/leads"), tc).contentType(MediaType.APPLICATION_JSON)
                .content(leadJson("Arun K", uniquePhone(), null, false))).andExpect(status().isCreated()).andReturn());
        String due = java.time.Instant.now().plusSeconds(3600).toString();
        mvc.perform(auth(post("/api/leads/" + leadId + "/follow-ups"), tc).contentType(MediaType.APPLICATION_JSON)
                        .content(json("dueAt", due, "purpose", "Call back after results")))
                .andExpect(status().isCreated());
        mvc.perform(auth(get("/api/follow-ups/mine"), tc)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].leadId").value(leadId))
                .andExpect(jsonPath("$[0].overdue").value(false));
    }

    @Test
    void csvLeadImportSkipsDuplicatesAndReportsBadRows() throws Exception {
        String admin = login(ADMIN, ADMIN_PW);
        String p1 = uniquePhone();
        String p2 = uniquePhone();
        String csv = "full_name,phone,neet_score,category,source\n"
                + "Kavya,+91 " + p1 + ",610,OBC,SEMINAR\n"
                + "Kavya again," + p1 + ",610,OBC,SEMINAR\n"
                + "Ravi," + p2 + ",abc,GEN,PHONE\n"
                + "No phone,,500,GEN,PHONE\n";
        mvc.perform(multipart("/api/leads/import").file(new MockMultipartFile("file", "leads.csv", "text/csv",
                        csv.getBytes(StandardCharsets.UTF_8))).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.skippedDuplicates").value(1))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].row").value(4));
    }

    // ------------------------------------------------------------------ master data approval

    @Test
    void dataExecChangesNeedAdminApproval() throws Exception {
        String admin = login(ADMIN, ADMIN_PW);
        String dataExec = login(createUser(admin, "DATA_EXEC"), PW);
        String counsellor = login(createUser(admin, "COUNSELLOR"), PW);
        String code = "C" + SEQ.incrementAndGet();

        String college = """
                {"entityType":"COLLEGE","action":"CREATE","payload":{"name":"Test Medical College %s",
                 "code":"%s","collegeType":"GOVERNMENT","state":"Tamil Nadu","city":"Chennai","nmcRecognized":true}}
                """.formatted(code, code);
        mvc.perform(auth(post("/api/change-requests"), counsellor).contentType(MediaType.APPLICATION_JSON)
                .content(college)).andExpect(status().isForbidden());

        long requestId = id(mvc.perform(auth(post("/api/change-requests"), dataExec)
                        .contentType(MediaType.APPLICATION_JSON).content(college))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn());
        mvc.perform(auth(get("/api/colleges").param("q", code), counsellor))
                .andExpect(jsonPath("$.totalItems").value(0));

        // Data exec cannot approve their own change.
        mvc.perform(auth(post("/api/change-requests/" + requestId + "/approve"), dataExec))
                .andExpect(status().isForbidden());
        MvcResult approved = mvc.perform(auth(post("/api/change-requests/" + requestId + "/approve"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn();
        Number collegeId = JsonPath.read(approved.getResponse().getContentAsString(), "$.entityId");
        mvc.perform(auth(get("/api/colleges").param("q", code), counsellor))
                .andExpect(jsonPath("$.totalItems").value(1));

        // Invalid payloads never reach the queue.
        mvc.perform(auth(post("/api/change-requests"), dataExec).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entityType":"CUTOFF","action":"CREATE","payload":{"collegeId":%s,"course":"MBBS",
                                 "quota":"AIQ","category":"GEN","counsellingRound":"ROUND_1","academicYear":2025,
                                 "closingRank":0}}""".formatted(collegeId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.closingRank").exists());

        // Bulk cutoff CSV -> one request; admin submissions apply immediately.
        String csv = "college_code,course,quota,category,pwd,round,academic_year,closing_rank\n"
                + code + ",MBBS,AIQ,GEN,no,ROUND_1,2025,1520\n"
                + code + ",MBBS,AIQ,OBC,no,ROUND_1,2025,2210\n";
        mvc.perform(multipart("/api/change-requests/bulk/cutoffs").file(new MockMultipartFile("file", "c.csv",
                        "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(auth(get("/api/colleges/" + collegeId), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.cutoffs.length()").value(2));

        // Rejection requires a reason.
        long second = id(mvc.perform(auth(post("/api/change-requests"), dataExec)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"entityType":"FEE","action":"CREATE","payload":{"collegeId":%s,"course":"MBBS",
                         "quota":"STATE","academicYear":2025,"annualTuition":13610}}""".formatted(collegeId)))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(auth(post("/api/change-requests/" + second + "/reject"), admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(auth(post("/api/change-requests/" + second + "/reject"), admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Wrong year\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
    }

    // ------------------------------------------------------------------ helpers

    private String login(String email, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", email, "password", password))).andExpect(status().isOk()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.accessToken");
    }

    private String createUser(String adminToken, String role) throws Exception {
        String email = role.toLowerCase() + SEQ.incrementAndGet() + "@test.local";
        mvc.perform(auth(post("/api/users"), adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(json("fullName", role + " user", "email", email, "role", role, "password", PW)))
                .andExpect(status().isCreated());
        return email;
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static long id(MvcResult r) throws Exception {
        Number n = JsonPath.read(r.getResponse().getContentAsString(), "$.id");
        return n.longValue();
    }

    private static String uniquePhone() {
        return "9" + String.format("%09d", SEQ.incrementAndGet() * 7919L % 1_000_000_000L);
    }

    private static String leadJson(String name, String phone, String roll, boolean allowDup) {
        return """
                {"fullName":"%s","phone":"%s",%s"neetScore":612,"neetAir":14500,"category":"OBC",
                 "homeState":"Tamil Nadu","domicileStatus":"DOMICILED","source":"PHONE",
                 "allowDuplicatePhone":%s}""".formatted(name, phone,
                roll == null ? "" : "\"neetRollNo\":\"" + roll + "\",", allowDup);
    }

    private static String studentJson(String name, String phone, String roll) {
        return """
                {"fullName":"%s","phone":"%s","category":"OBC","pwd":false,"homeState":"Tamil Nadu",
                 "domicileStatus":"DOMICILED","nationality":"INDIAN","nriSponsored":false,"neetYear":2026,
                 "neetRollNo":"%s","neetQualified":true,"neetScore":612,"neetAir":14500,
                 "categoryCertValidUntil":"2030-03-31","languagePreference":"TAMIL"}""".formatted(name, phone, roll);
    }

    private static String json(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(kv[i]).append("\":\"").append(kv[i + 1].replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
