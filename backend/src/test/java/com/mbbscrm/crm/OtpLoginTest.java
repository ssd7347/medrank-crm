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

    @Test
    void registrationWaitsForAdminApproval() throws Exception {
        String admin = JsonPath.read(mvc.perform(verify("9000000000", requestCode("9000000000"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
        String body = "{\"fullName\":\"New Joiner\",\"phone\":\"9777700001\",\"email\":\"new.joiner@otp.test\","
                + "\"role\":\"COUNSELLOR\"}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.staffId").value(Matchers.matchesPattern("STF-[0-9]{4}")))
                .andExpect(jsonPath("$.pendingApproval").value(true));
        // The same number cannot register twice, details must be valid, and nobody can make themselves admin.
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"X\",\"phone\":\"123\",\"email\":\"x@otp.test\",\"role\":\"TELECALLER\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"X\",\"phone\":\"9777700009\",\"email\":\"x@otp.test\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Sneaky\",\"phone\":\"9777700009\",\"email\":\"sneaky@otp.test\","
                                + "\"role\":\"SUPER_ADMIN\"}"))
                .andExpect(status().isBadRequest());

        // Not usable yet: no code is issued for it.
        mvc.perform(post("/api/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"9777700001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codeOnScreen").value(Matchers.nullValue()));

        MvcResult list = mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.phone == '9777700001')].pendingApproval").value(true))
                .andExpect(jsonPath("$[?(@.phone == '9777700001')].active").value(false))
                .andExpect(jsonPath("$[?(@.phone == '9777700001')].length()").value(Matchers.hasSize(1)))
                .andReturn();
        int id = ((java.util.List<Integer>) JsonPath.read(list.getResponse().getContentAsString(),
                "$[?(@.phone == '9777700001')].id")).get(0);

        // The admin approves it and chooses the role; then the person can sign in.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"New Joiner\",\"phone\":\"9777700001\",\"role\":\"COUNSELLOR\",\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingApproval").value(false));
        mvc.perform(verify("9777700001", requestCode("9777700001"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("COUNSELLOR"));
        // An approved account can no longer be removed as a "registration".
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/users/" + id + "/registration")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andExpect(status().isConflict());

        // A rejected registration disappears.
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Stranger\",\"phone\":\"9777700002\",\"email\":\"stranger@otp.test\","
                                + "\"role\":\"TELECALLER\"}"))
                .andExpect(status().isCreated());
        MvcResult again = mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andReturn();
        int stranger = ((java.util.List<Integer>) JsonPath.read(again.getResponse().getContentAsString(),
                "$[?(@.phone == '9777700002')].id")).get(0);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/users/" + stranger + "/registration")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(jsonPath("$[?(@.phone == '9777700002')]").isEmpty());
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
