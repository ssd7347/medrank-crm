package com.mbbscrm.crm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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

/**
 * The common login: one page, a mobile number and a one-time code (shown on screen in this temporary mode).
 * The number decides whether you are the admin, a staff member or a student. Only students register
 * themselves, and there is exactly one admin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OtpLoginTest {

    @Autowired
    MockMvc mvc;

    @Test
    void adminAndStaffSignInWithMobileNumberAndCode() throws Exception {
        // The first admin got their number from ADMIN_PHONE. Spaces and +91 are accepted.
        String code = requestCode("+91 90000 00000");
        mvc.perform(verify("9000000000", "000000".equals(code) ? "111111" : "000000"))
                .andExpect(status().isUnauthorized());
        MvcResult ok = mvc.perform(verify("9000000000", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("STAFF"))
                .andExpect(jsonPath("$.user.role").value("SUPER_ADMIN"))
                .andExpect(cookie().httpOnly("crm_refresh", true))
                .andReturn();
        String admin = JsonPath.read(ok.getResponse().getContentAsString(), "$.accessToken");
        // A code works once.
        mvc.perform(verify("9000000000", code)).andExpect(status().isUnauthorized());

        // A number nobody has registered gets the same reply but no code, and cannot sign in.
        mvc.perform(post("/api/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"9111111111\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codeOnScreen").value(Matchers.nullValue()));
        mvc.perform(verify("9111111111", "123456")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"12345\"}"))
                .andExpect(status().isBadRequest());

        // Staff IDs are created by the admin, with a unique 10-digit mobile number.
        MvcResult created = mvc.perform(auth(post("/api/users"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Otp Counsellor\",\"email\":\"otp.counsellor@otp.test\",\"role\":\"COUNSELLOR\","
                                + "\"phone\":\"98400 12345\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value("9840012345"))
                .andReturn();
        int counsellorId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mvc.perform(auth(post("/api/users"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Same Phone\",\"email\":\"same.phone@otp.test\",\"role\":\"COUNSELLOR\","
                                + "\"phone\":\"9840012345\"}"))
                .andExpect(status().isConflict());
        String counsellor = JsonPath.read(mvc.perform(verify("9840012345", requestCode("9840012345")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("STAFF"))
                .andExpect(jsonPath("$.user.role").value("COUNSELLOR"))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
        mvc.perform(auth(get("/api/auth/me"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Otp Counsellor"));

        // There is only one admin: no second one can be created, nobody can be promoted, and staff cannot
        // create accounts at all.
        mvc.perform(auth(post("/api/users"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Second Admin\",\"email\":\"second.admin@otp.test\",\"role\":\"SUPER_ADMIN\","
                                + "\"phone\":\"9840012399\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(put("/api/users/" + counsellorId), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Otp Counsellor\",\"phone\":\"9840012345\",\"role\":\"SUPER_ADMIN\",\"active\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(post("/api/users"), counsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"By Staff\",\"email\":\"by.staff@otp.test\",\"role\":\"TELECALLER\","
                                + "\"phone\":\"9840012398\"}"))
                .andExpect(status().isForbidden());

        // A new code cancels the previous one.
        String first = requestCode("9840012345");
        String second = requestCode("9840012345");
        if (!first.equals(second)) {
            mvc.perform(verify("9840012345", first)).andExpect(status().isUnauthorized());
        }
        mvc.perform(verify("9840012345", second)).andExpect(status().isOk());
    }

    @Test
    void studentsRegisterThemselvesAndLandInThePortal() throws Exception {
        String admin = JsonPath.read(mvc.perform(verify("9000000000", requestCode("9000000000")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");

        String body = "{\"fullName\":\"Self Registered\",\"phone\":\"9777700001\",\"category\":\"OBC\","
                + "\"homeState\":\"Tamil Nadu\",\"neetScore\":540,\"parentName\":\"Parent\",\"parentPhone\":\"9777700003\"}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.studentId").value(Matchers.matchesPattern("STU-[0-9]{4}")));
        // The same number cannot register twice; required details are checked; a staff number is refused.
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"No Category\",\"phone\":\"9777700004\",\"homeState\":\"Tamil Nadu\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Admin Number\",\"phone\":\"9000000000\",\"category\":\"GEN\",\"homeState\":\"Kerala\"}"))
                .andExpect(status().isConflict());

        // The same login page signs the student in, as a portal user who sees only their own record.
        MvcResult login = mvc.perform(verify("9777700001", requestCode("9777700001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("PORTAL"))
                .andExpect(jsonPath("$.displayName").value("Self Registered"))
                .andExpect(jsonPath("$.user").value(Matchers.nullValue()))
                .andExpect(cookie().httpOnly("crm_portal_refresh", true))
                .andReturn();
        String student = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
        MvcResult me = mvc.perform(auth(get("/api/portal/me"), student)).andExpect(status().isOk())
                .andExpect(jsonPath("$.students.length()").value(1)).andReturn();
        int studentId = JsonPath.read(me.getResponse().getContentAsString(), "$.students[0].id");
        mvc.perform(auth(get("/api/portal/students/" + studentId), student)).andExpect(status().isOk())
                .andExpect(jsonPath("$.student.fullName").value("Self Registered"));
        mvc.perform(auth(get("/api/students/" + studentId), student)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/users"), student)).andExpect(status().isForbidden());

        // The admin sees the new student and the lead it created, and was notified.
        mvc.perform(auth(get("/api/students/" + studentId), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("OBC"))
                .andExpect(jsonPath("$.leadId").isNumber());
        mvc.perform(auth(get("/api/leads?q=9777700001"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].source").value("SELF_REGISTERED"));
        mvc.perform(auth(get("/api/notifications"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'STUDENT_REGISTERED')]").isNotEmpty());
        mvc.perform(auth(get("/api/students/" + studentId + "/portal-access"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].selfRegistered").value(true));

        // Switching the login off stops the student signing in.
        List<Integer> accounts = JsonPath.read(mvc.perform(auth(get("/api/students/" + studentId + "/portal-access"), admin))
                .andReturn().getResponse().getContentAsString(), "$[*].accountId");
        mvc.perform(auth(post("/api/students/" + studentId + "/portal-access/" + accounts.get(0) + "/disable"), admin))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"9777700001\"}"))
                .andExpect(jsonPath("$.codeOnScreen").value(Matchers.nullValue()));
        mvc.perform(auth(get("/api/portal/me"), student)).andExpect(status().isForbidden());
    }

    private String requestCode(String phone) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shownOnScreen").value(true))
                .andExpect(jsonPath("$.codeOnScreen").value(Matchers.matchesPattern("[0-9]{6}")))
                .andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.codeOnScreen");
    }

    private static MockHttpServletRequestBuilder verify(String phone, String code) {
        return post("/api/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}");
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
