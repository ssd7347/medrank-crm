package com.aicallcrm.crm;

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
 * The number decides who you are: the admin or a staff member the admin created. Nobody registers themselves.
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
                        .content("{\"fullName\":\"Otp Agent\",\"email\":\"otp.agent@otp.test\",\"role\":\"AGENT\","
                                + "\"phone\":\"98400 12345\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value("9840012345"))
                .andReturn();
        int counsellorId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mvc.perform(auth(post("/api/users"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Same Phone\",\"email\":\"same.phone@otp.test\",\"role\":\"AGENT\","
                                + "\"phone\":\"9840012345\"}"))
                .andExpect(status().isConflict());
        String counsellor = JsonPath.read(mvc.perform(verify("9840012345", requestCode("9840012345")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("STAFF"))
                .andExpect(jsonPath("$.user.role").value("AGENT"))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
        mvc.perform(auth(get("/api/auth/me"), counsellor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Otp Agent"));

        // There is only one admin: no second one can be created, nobody can be promoted, and staff cannot
        // create accounts at all.
        mvc.perform(auth(post("/api/users"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Second Admin\",\"email\":\"second.admin@otp.test\",\"role\":\"SUPER_ADMIN\","
                                + "\"phone\":\"9840012399\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(put("/api/users/" + counsellorId), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Otp Agent\",\"phone\":\"9840012345\",\"role\":\"SUPER_ADMIN\",\"active\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(post("/api/users"), counsellor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"By Staff\",\"email\":\"by.staff@otp.test\",\"role\":\"MANAGER\","
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
    void nobodyCanRegisterThemselves() throws Exception {
        // Accounts are created only by the admin: there is no public sign-up.
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Someone\",\"phone\":\"9777700001\"}"))
                .andExpect(status().isUnauthorized());
        // An unknown number gets the same reply but no code, and cannot sign in.
        mvc.perform(post("/api/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"9777700002\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codeOnScreen").value(Matchers.nullValue()));
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
