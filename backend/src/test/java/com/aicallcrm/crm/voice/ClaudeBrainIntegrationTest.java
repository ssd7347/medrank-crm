package com.aicallcrm.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
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

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

/**
 * Claude as the brain, through the official SDK, against a stand-in for Anthropic's Messages API: the request
 * carries the prompt-caching breakpoints, the model id is the published one, tools arrive in a fixed order, and
 * Claude's own reply (with its tool call) is sent back unchanged so the cached prefix holds.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClaudeBrainIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Map<String, Object>> REQUESTS = new CopyOnWriteArrayList<>();
    private static final HttpServer API = start();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.voice.ai.anthropic-api-key", () -> "test-key");
        r.add("app.voice.ai.anthropic-url", () -> "http://127.0.0.1:" + API.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        API.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Test
    @SuppressWarnings("unchecked")
    void claudeAnswersWithCachingAndTheSdk() throws Exception {
        String token = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@test.local\",\"password\":\"AdminPass123!\"}")).andReturn()
                .getResponse().getContentAsString(), "$.accessToken");
        String callId = JsonPath.read(mvc.perform(post("/api/voice/test-calls").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scriptName\":\"Incoming calls\",\"brain\":\"CLAUDE\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.brain").value("CLAUDE"))
                .andReturn().getResponse().getContentAsString(), "$.callId");

        mvc.perform(post("/api/voice/test-calls/" + callId + "/say").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"When are you open?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added[*].tool").value(Matchers.hasItem("search_faq")))
                .andExpect(jsonPath("$.added[-1].text").value("We are open from nine to six."));

        assertThat(REQUESTS).hasSize(2);
        for (Map<String, Object> req : REQUESTS) {
            assertThat(req.get("model")).isEqualTo("claude-haiku-4-5");
            // Explicit breakpoint on the script (caches tools + system) and automatic caching of the conversation.
            List<Map<String, Object>> system = (List<Map<String, Object>>) req.get("system");
            assertThat(system.get(0).get("cache_control")).isEqualTo(Map.of("type", "ephemeral"));
            assertThat(req.get("cache_control")).isEqualTo(Map.of("type", "ephemeral"));
            // Claude Haiku 4.5 takes no effort setting.
            assertThat(req).doesNotContainKey("output_config");
            List<String> toolNames = ((List<Map<String, Object>>) req.get("tools")).stream()
                    .map(t -> String.valueOf(t.get("name"))).toList();
            assertThat(toolNames).isSorted().contains("verify_identity", "opt_out");
        }
        // Second request: Claude's tool call replayed as it came, then the result in one user message.
        List<Map<String, Object>> messages = (List<Map<String, Object>>) REQUESTS.get(1).get("messages");
        Map<String, Object> replayed = messages.get(messages.size() - 2);
        assertThat(replayed.get("role")).isEqualTo("assistant");
        assertThat(String.valueOf(replayed.get("content"))).contains("tool_use").contains("toolu_1");
        Map<String, Object> results = messages.get(messages.size() - 1);
        assertThat(String.valueOf(results.get("content"))).contains("tool_result").contains("toolu_1");
    }

    // ------------------------------------------------------------------ stand-in Messages API

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/messages", ClaudeBrainIntegrationTest::messages);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void messages(HttpExchange ex) throws IOException {
        Map<String, Object> body = JSON.readValue(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.class);
        REQUESTS.add(body);
        String content = REQUESTS.size() == 1
                ? "[{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"search_faq\",\"input\":{\"query\":\"opening hours\"}}]"
                : "[{\"type\":\"text\",\"text\":\"We are open from nine to six.\"}]";
        String json = "{\"id\":\"msg_" + REQUESTS.size() + "\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-4-5\","
                + "\"content\":" + content + ",\"stop_reason\":\"" + (REQUESTS.size() == 1 ? "tool_use" : "end_turn") + "\","
                + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":20,\"output_tokens\":8,"
                + "\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}";
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("content-type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
