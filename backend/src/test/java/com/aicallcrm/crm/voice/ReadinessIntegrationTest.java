package com.aicallcrm.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * "Ready for a real call?" end to end, with one stand-in server on this machine playing Plivo's API, the public
 * gateway, the speech engine and the model server. The check only reads: Plivo gets one GET, the public address gets
 * an unsigned GET and POST, nothing else is called and no call is placed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReadinessIntegrationTest {

    private static final String AUTH_ID = "MAREADY0001";
    private static final String TOKEN = "readiness-test-token-0123456789";
    private static final String PUBLIC = "https://gateway.test.example";

    static final AtomicInteger PLIVO_STATUS = new AtomicInteger(200);
    static final AtomicInteger ME_STATUS = new AtomicInteger(404);
    static final AtomicInteger HOOK_STATUS = new AtomicInteger(401);
    static final AtomicBoolean WARMED = new AtomicBoolean(true);
    /** "METHOD path" of everything the stand-in received, and the Authorization header Plivo got. */
    static final List<String> SEEN = new CopyOnWriteArrayList<>();
    static final List<String> PLIVO_AUTH = new CopyOnWriteArrayList<>();
    static final HttpServer FAKE;

    static {
        try {
            FAKE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        FAKE.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            SEEN.add(ex.getRequestMethod() + " " + path);
            if (path.startsWith("/v1/Account/")) {
                PLIVO_AUTH.add(ex.getRequestHeaders().getFirst("Authorization"));
                reply(ex, PLIVO_STATUS.get(), "{\"api_id\":\"r1\",\"account_type\":\"standard\"}");
            } else if (path.equals("/api/auth/me")) {
                reply(ex, ME_STATUS.get(), "{}");
            } else if (path.equals("/api/voice/hooks/plivo/hangup")) {
                reply(ex, HOOK_STATUS.get(), "{}");
            } else if (path.equals("/health")) {
                reply(ex, 200, "{\"ok\":true,\"stt_model\":\"small.en\",\"voice\":\"en_IN-test\",\"warmed\":"
                        + WARMED.get() + ",\"warm_up_error\":null}");
            } else if (path.equals("/v1/models")) {
                reply(ex, 200, "{\"object\":\"list\",\"data\":[{\"id\":\"ready-model\"}]}");
            } else {
                reply(ex, 404, "{}");
            }
        });
        FAKE.start();
    }

    private static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static String local() {
        return "http://127.0.0.1:" + FAKE.getAddress().getPort();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.voice.platform", () -> "plivo");
        r.add("app.voice.plivo.auth-id", () -> AUTH_ID);
        r.add("app.voice.plivo.auth-token", () -> TOKEN);
        r.add("app.voice.plivo.from-number", () -> "+918000000001");
        r.add("app.voice.plivo.public-url", () -> PUBLIC + "/");
        r.add("app.voice.plivo.engine-url", () -> "wss://gateway.test.example/call");
        // A stand-in for Plivo's API: the check must call this out as rehearsal-only.
        r.add("app.voice.plivo.api-base", () -> local() + "/v1");
        r.add("app.voice.desk-transfer-number", () -> "+914400000000");
        r.add("app.voice.ai.brain", () -> "LOCAL");
        r.add("app.voice.ai.local-url", ReadinessIntegrationTest::local);
        r.add("app.voice.ai.local-model", () -> "ready-model");
        r.add("app.voice.ai.speech-url", ReadinessIntegrationTest::local);
    }

    /** The public address is https; here it is answered by the stand-in, through the real probe. */
    @TestConfiguration
    static class LocalGateway {
        @Bean
        @Primary
        ReadinessService.Probe localProbe() {
            return new ReadinessService.Probe() {
                @Override
                Integer status(String method, String url) {
                    assertThat(url).startsWith(PUBLIC + "/");
                    return super.status(method, local() + url.substring(PUBLIC.length()));
                }
            };
        }
    }

    @AfterAll
    static void stop() {
        FAKE.stop(0);
    }

    @AfterEach
    void reset() {
        PLIVO_STATUS.set(200);
        ME_STATUS.set(404);
        HOOK_STATUS.set(401);
        WARMED.set(true);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void readsEverythingAndChangesNothing() throws Exception {
        String admin = admin();
        SEEN.clear();
        PLIVO_AUTH.clear();
        Map<String, Map<String, Object>> checks = checks(admin);

        for (String key : List.of("LINE", "PLIVO_SETTINGS", "PLIVO_KEYS", "PUBLIC", "REACH", "CALLBACKS", "SECRET",
                "ENGINE", "BRAIN", "HOURS", "PAUSE", "DESK")) {
            assertThat(checks.get(key).get("state")).as(key + ": " + checks.get(key).get("detail")).isEqualTo("OK");
        }
        // The stand-in Plivo address is caught: on the day PLIVO_API_BASE must be gone.
        assertThat(checks.get("PLIVO_API").get("state")).isEqualTo("FAIL");
        assertThat((String) checks.get("BRAIN").get("detail")).contains("ready-model");
        assertThat((String) checks.get("ENGINE").get("detail")).contains("small.en");

        // Exactly these requests, all reads or refused: no call placed, nothing changed at Plivo.
        assertThat(SEEN).containsExactlyInAnyOrder("GET /v1/Account/" + AUTH_ID + "/", "GET /api/auth/me",
                "POST /api/voice/hooks/plivo/hangup", "GET /health", "GET /v1/models");
        assertThat(PLIVO_AUTH).containsExactly("Basic " + Base64.getEncoder()
                .encodeToString((AUTH_ID + ":" + TOKEN).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void whatTheStandInsAnswerShowsUp() throws Exception {
        String admin = admin();
        PLIVO_STATUS.set(401);
        ME_STATUS.set(401);
        HOOK_STATUS.set(502);
        WARMED.set(false);
        Map<String, Map<String, Object>> checks = checks(admin);
        assertThat(checks.get("PLIVO_KEYS").get("state")).isEqualTo("FAIL");
        assertThat((String) checks.get("PLIVO_KEYS").get("detail")).contains("Plivo answered 401");
        // The CRM itself is public while codes are shown on screen (the test settings): refused.
        assertThat(checks.get("REACH").get("state")).isEqualTo("FAIL");
        assertThat((String) checks.get("CALLBACKS").get("detail")).contains("got 502");
        assertThat((String) checks.get("ENGINE").get("detail")).contains("still loading");
    }

    @Test
    void theCrmRefusesAnUnsignedPlivoCallbackAsTheCheckExpects() throws Exception {
        // The CALLBACKS rule counts a 401 as "reached this CRM": that is what this CRM answers an unsigned callback.
        mvc.perform(post("/api/voice/hooks/plivo/hangup")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheAdminMayRunIt() throws Exception {
        String admin = admin();
        mvc.perform(get("/api/voice/readiness")).andExpect(status().isUnauthorized());
        mvc.perform(auth(post("/api/users"), admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Ready Manager","email":"ready.manager@ready.test","role":"MANAGER","password":"StaffPass123!"}"""))
                .andExpect(status().isCreated());
        String manager = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ready.manager@ready.test\",\"password\":\"StaffPass123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
        SEEN.clear();
        mvc.perform(auth(get("/api/voice/readiness"), manager)).andExpect(status().isForbidden());
        assertThat(SEEN).isEmpty();
    }

    private Map<String, Map<String, Object>> checks(String admin) throws Exception {
        String body = mvc.perform(auth(get("/api/voice/readiness"), admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> list = JsonPath.read(body, "$.checks");
        boolean ready = JsonPath.read(body, "$.ready");
        assertThat(ready).isEqualTo(list.stream().noneMatch(c -> "FAIL".equals(c.get("state"))));
        assertThat((String) JsonPath.read(body, "$.checkedAt")).isNotBlank();
        Map<String, Map<String, Object>> byKey = new java.util.LinkedHashMap<>();
        list.forEach(c -> byKey.put((String) c.get("key"), c));
        return byKey;
    }

    private String admin() throws Exception {
        return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@test.local\",\"password\":\"AdminPass123!\"}"))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
