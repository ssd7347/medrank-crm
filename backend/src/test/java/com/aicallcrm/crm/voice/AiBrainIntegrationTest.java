package com.aicallcrm.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

/**
 * Our own model as the brain, end to end: a stand-in OpenAI-compatible server (what Ollama or vLLM speak)
 * plays the model. It asks for a tool, gets the CRM's answer, and replies; when it breaks, a person takes over.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiBrainIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Every request the stand-in model received, for checking what the CRM sent it. */
    private static final List<Map<String, Object>> REQUESTS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger SEQ = new AtomicInteger(31000);
    private static final HttpServer MODEL = startModel();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.voice.ai.local-url", () -> "http://127.0.0.1:" + MODEL.getAddress().getPort());
        r.add("app.voice.ai.local-model", () -> "test-model");
        r.add("app.voice.ai.speech-url", () -> "http://127.0.0.1:9");
    }

    @AfterAll
    static void stop() {
        MODEL.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void theModelUsesTheCrmToolsAndSpeaksPlainSentences() throws Exception {
        String admin = admin();
        long contact = contact(admin);
        mvc.perform(auth(get("/api/voice/ai/status"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.brains[?(@.kind == 'LOCAL')].available").value(Matchers.hasItem(true)))
                .andExpect(jsonPath("$.brains[?(@.kind == 'CLAUDE')].available").value(Matchers.hasItem(false)))
                .andExpect(jsonPath("$.speech.available").value(false));

        String started = mvc.perform(auth(post("/api/voice/test-calls"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactId\":" + contact + ",\"scriptName\":\"Enquiry follow-up\",\"brain\":\"LOCAL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.brain").value("LOCAL"))
                .andExpect(jsonPath("$.added[0].tool").value("get_call_context"))
                .andReturn().getResponse().getContentAsString();
        String callId = JsonPath.read(started, "$.callId");

        mvc.perform(auth(post("/api/voice/test-calls/" + callId + "/say"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"What are your opening hours?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added[*].tool").value(Matchers.hasItem("search_faq")))
                // Markdown from the model is cleaned before it is spoken.
                .andExpect(jsonPath("$.added[-1].role").value("agent"))
                .andExpect(jsonPath("$.added[-1].text").value("Let me help: our team can call you back."));

        // The script is the system prompt, the tools are offered, and the tool's answer went back to the model.
        Map<String, Object> last = REQUESTS.get(REQUESTS.size() - 1);
        List<Map<String, Object>> messages = cast(last.get("messages"));
        assertThat(String.valueOf(messages.get(0).get("content"))).contains("LIVE PHONE CALL").contains("CALL CONTEXT");
        assertThat(messages.stream().anyMatch(m -> "tool".equals(m.get("role")))).isTrue();
        assertThat(String.valueOf(last.get("tools"))).contains("verify_identity").contains("opt_out");
    }

    @Test
    void whenTheModelFailsAPersonTakesOver() throws Exception {
        String admin = admin();
        long contact = contact(admin);
        String callId = JsonPath.read(mvc.perform(auth(post("/api/voice/test-calls"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactId\":" + contact + ",\"scriptName\":\"Enquiry follow-up\",\"brain\":\"LOCAL\"}"))
                .andReturn().getResponse().getContentAsString(), "$.callId");
        mvc.perform(auth(post("/api/voice/test-calls/" + callId + "/say"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"please break now\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.over").value(true))
                .andExpect(jsonPath("$.added[*].tool").value(Matchers.hasItems("ai_model", "request_human_handoff")));
    }

    @Test
    void claudeIsRefusedWithoutAKey() throws Exception {
        String admin = admin();
        mvc.perform(auth(post("/api/voice/test-calls"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scriptName\":\"Incoming calls\",\"brain\":\"CLAUDE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("Anthropic API key")));
    }

    @Test
    void spokenTextLosesMarkdown() {
        assertThat(LlmBrain.spoken("**Sure!**\n- Monday to Friday\n- 9 to 6")).isEqualTo("Sure! Monday to Friday 9 to 6");
        assertThat(LlmBrain.spoken(null)).isEmpty();
    }

    // ------------------------------------------------------------------ the stand-in model server

    private static HttpServer startModel() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/models", ex -> reply(ex, 200, "{\"data\":[{\"id\":\"test-model\"}]}"));
            server.createContext("/v1/chat/completions", AiBrainIntegrationTest::chat);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** First turn: ask for the FAQ tool. After the tool answers: reply (with markdown, to be cleaned). */
    private static void chat(HttpExchange ex) throws IOException {
        Map<String, Object> body = JSON.readValue(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.class);
        REQUESTS.add(body);
        List<Map<String, Object>> messages = cast(body.get("messages"));
        Map<String, Object> lastMsg = messages.get(messages.size() - 1);
        if (String.valueOf(lastMsg.get("content")).contains("break")) {
            reply(ex, 500, "{\"error\":\"model crashed\"}");
            return;
        }
        if ("tool".equals(lastMsg.get("role"))) {
            reply(ex, 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"**Let me help:** our team can call you back.\"}}]}");
            return;
        }
        reply(ex, 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"t1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"search_faq\",\"arguments\":\"{\\\"query\\\":\\\"opening hours\\\"}\"}}]}}]}");
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("content-type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object o) {
        return (List<Map<String, Object>>) o;
    }

    // ------------------------------------------------------------------ helpers

    private long contact(String token) throws Exception {
        String phone = "9" + String.format("%09d", SEQ.incrementAndGet());
        MvcResult r = mvc.perform(auth(post("/api/contacts"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Brain Test\",\"phone\":\"" + phone + "\"}")).andExpect(status().isCreated()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
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
