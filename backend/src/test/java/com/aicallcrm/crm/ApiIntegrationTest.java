package com.aicallcrm.crm;

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
        String email = createUser(admin, "MANAGER");
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
        String agent = login(createUser(admin, "AGENT"), PW);
        mvc.perform(auth(get("/api/users"), agent)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/users/assignable"), agent)).andExpect(status().isOk());
    }

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
