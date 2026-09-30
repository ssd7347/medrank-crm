package com.mbbscrm.crm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.jayway.jsonpath.JsonPath;

/** Staff sign-in by mobile number and one-time code (shown on screen in this temporary mode). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OtpLoginTest {

    @Autowired
    MockMvc mvc;

    @Test
    void signInWithMobileNumberAndCode() throws Exception {
        // The first admin got their number from ADMIN_PHONE. Spaces and +91 are accepted.
        String code = requestCode("+91 90000 00000");
        mvc.perform(verify("9000000000", "000000".equals(code) ? "111111" : "000000"))
                .andExpect(status().isUnauthorized());
        MvcResult ok = mvc.perform(verify("9000000000", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("SUPER_ADMIN"))
                .andExpect(cookie().httpOnly("crm_refresh", true))
                .andReturn();
        String admin = JsonPath.read(ok.getResponse().getContentAsString(), "$.accessToken");
        // A code works once.
        mvc.perform(verify("9000000000", code)).andExpect(status().isUnauthorized());

        // A number that is not a staff member gets the same reply but no code, and cannot sign in.
        mvc.perform(post("/api/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"9111111111\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codeOnScreen").value(Matchers.nullValue()));
        mvc.perform(verify("9111111111", "123456")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"12345\"}"))
                .andExpect(status().isBadRequest());

        // New staff are identified by their mobile number; it must be unique and 10 digits.
        mvc.perform(post("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Otp Counsellor\",\"email\":\"otp.counsellor@otp.test\",\"role\":\"COUNSELLOR\","
                                + "\"phone\":\"98400 12345\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value("9840012345"));
        mvc.perform(post("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Same Phone\",\"email\":\"same.phone@otp.test\",\"role\":\"COUNSELLOR\","
                                + "\"phone\":\"9840012345\"}"))
                .andExpect(status().isConflict());
        String counsellorCode = requestCode("9840012345");
        String counsellor = JsonPath.read(mvc.perform(verify("9840012345", counsellorCode)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + counsellor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Otp Counsellor"));

        // A new code cancels the previous one.
        String first = requestCode("9840012345");
        String second = requestCode("9840012345");
        if (!first.equals(second)) {
            mvc.perform(verify("9840012345", first)).andExpect(status().isUnauthorized());
        }
        mvc.perform(verify("9840012345", second)).andExpect(status().isOk());
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

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder verify(String phone, String code) {
        return post("/api/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}");
    }
}
