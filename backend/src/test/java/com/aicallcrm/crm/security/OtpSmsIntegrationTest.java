package com.aicallcrm.crm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

/**
 * Signing in as on the live site: the code is not shown on the login page, it goes by SMS (a fake MSG91 on this
 * computer), and the code in that SMS signs the staff member in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OtpSmsIntegrationTest {

    static final List<Map<?, ?>> SENT = new CopyOnWriteArrayList<>();
    static final HttpServer FAKE;

    static {
        try {
            FAKE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        ObjectMapper mapper = new ObjectMapper();
        FAKE.createContext("/api/v5/flow", ex -> {
            if ("test-msg91-key".equals(ex.getRequestHeaders().getFirst("authkey"))) {
                SENT.add(mapper.readValue(ex.getRequestBody().readAllBytes(), Map.class));
            }
            byte[] out = "{\"type\":\"success\",\"message\":\"req-1\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        FAKE.start();
    }

    @DynamicPropertySource
    static void sms(DynamicPropertyRegistry r) {
        r.add("app.otp.show-on-screen", () -> "false");
        r.add("app.otp.sms.provider", () -> "msg91");
        r.add("app.otp.sms.auth-key", () -> "test-msg91-key");
        r.add("app.otp.sms.template-id", () -> "tmpl-sign-in");
        r.add("app.otp.sms.api-base", () -> "http://127.0.0.1:" + FAKE.getAddress().getPort() + "/api/v5");
    }

    @AfterAll
    static void stop() {
        FAKE.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void theCodeGoesBySmsAndSignsTheStaffMemberIn() throws Exception {
        SENT.clear();
        // A number nobody has: the same reply, and no SMS.
        mvc.perform(request("9777700003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shownOnScreen").value(false))
                .andExpect(jsonPath("$.codeOnScreen").value(Matchers.nullValue()));

        mvc.perform(request("+91 90000 00000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shownOnScreen").value(false))
                .andExpect(jsonPath("$.codeOnScreen").value(Matchers.nullValue()));
        // Sent in the background, after the reply.
        for (int i = 0; i < 100 && SENT.isEmpty(); i++) {
            Thread.sleep(50);
        }
        Thread.sleep(200);
        assertThat(SENT).hasSize(1);
        Map<?, ?> recipient = (Map<?, ?>) ((List<?>) SENT.get(0).get("recipients")).get(0);
        assertThat(recipient.get("mobiles")).isEqualTo("919000000000");
        String code = (String) recipient.get("OTP");
        assertThat(code).matches("[0-9]{6}");
        assertThat(SENT.get(0).get("template_id")).isEqualTo("tmpl-sign-in");

        String token = JsonPath.read(mvc.perform(verify("9000000000", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("SUPER_ADMIN"))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");

        // Connected services shows SMS as connected.
        mvc.perform(get("/api/integrations").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='LOGIN_CODES')].state").value(Matchers.contains("CONNECTED")));
    }

    private static MockHttpServletRequestBuilder request(String phone) {
        return post("/api/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}");
    }

    private static MockHttpServletRequestBuilder verify(String phone, String code) {
        return post("/api/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}");
    }
}
