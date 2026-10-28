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
        // The tools are written into the system prompt, not sent for Ollama to handle (it mangles nested arguments).
        assertThat(last).doesNotContainKey("tools");
        assertThat(String.valueOf(messages.get(0).get("content"))).contains("# Tools").contains("\"name\": \"verify_identity\"")
                .contains("\"name\": \"opt_out\"")
                // Its answer is already in the prompt, so the model is not offered it.
                .doesNotContain("\"name\": \"get_call_context\"");
    }

    @Test
    void toolsAreWrittenLikeTheTrainingText() {
        // Python's json.dumps spacing, keys sorted, non-ASCII kept.
        assertThat(LlmClients.pythonJson(Map.of("b", List.of(1, true), "a", "Rs. 500 \"due\" — é\n")))
                .isEqualTo("{\"a\": \"Rs. 500 \\\"due\\\" — é\\n\", \"b\": [1, true]}");
        String block = LlmClients.toolsInText(List.of(new LlmClient.ToolSpec("opt_out", "Stops the calls.",
                Map.of("type", "object", "properties", Map.of()))));
        assertThat(block).startsWith("# Tools\n\nYou may call one or more functions").endsWith("</tool_call>").contains(
                "<tools>\n{\"function\": {\"description\": \"Stops the calls.\", \"name\": \"opt_out\", "
                        + "\"parameters\": {\"properties\": {}, \"type\": \"object\"}}, \"type\": \"function\"}\n</tools>");
    }

    @Test
    void writtenToolCallsKeepNestedAnswers() {
        List<LlmClient.ToolSpec> tools = List.of(
                new LlmClient.ToolSpec("verify_identity", "Checks the caller.", Map.of("type", "object")),
                new LlmClient.ToolSpec("log_call_outcome", "Records how the call ended.", Map.of("type", "object")));
        LlmClient.Reply r = LlmClients.recoverWrittenCalls("<tool_call>\n{\"name\": \"verify_identity\", \"arguments\": "
                + "{\"full_name\": \"Sanjay Chandran\", \"answers\": {\"loan_account_number\": \"407122\"}}}\n</tool_call>\n"
                + "<tool_call>\n{\"name\": \"log_call_outcome\", \"arguments\": {\"outcome_code\": \"WILL_ACT\"}}\n</tool_call>",
                tools, JSON);
        assertThat(r.toolCalls()).extracting(LlmClient.ToolCall::name).containsExactly("verify_identity", "log_call_outcome");
        assertThat(r.toolCalls().get(0).arguments()).containsEntry("full_name", "Sanjay Chandran")
                .containsEntry("answers", Map.of("loan_account_number", "407122"));
        assertThat(r.text()).isEmpty();
    }

    @Test
    void aGoodbyeWithoutAnOutcomeGoesToAPersonForReview() throws Exception {
        String admin = admin();
        long contact = contact(admin);
        String callId = JsonPath.read(mvc.perform(auth(post("/api/voice/test-calls"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactId\":" + contact + ",\"scriptName\":\"Enquiry follow-up\",\"brain\":\"LOCAL\"}"))
                .andReturn().getResponse().getContentAsString(), "$.callId");
        mvc.perform(auth(post("/api/voice/test-calls/" + callId + "/say"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"okay, just say goodbye\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.over").value(true))
                .andExpect(jsonPath("$.added[*].tool").value(Matchers.hasItem("request_human_handoff")))
                // Five sentences from the model: the caller hears three, ending with the last one.
                .andExpect(jsonPath("$.added[?(@.role == 'agent')].text").value(Matchers.hasItem(
                        "Thanks for your time. Dr. Rao will be glad to hear it. Goodbye.")));
    }

    @Test
    void aGoodbyeAfterABookedCallBackKeepsItsOutcome() throws Exception {
        String admin = admin();
        long contact = contact(admin);
        String callId = JsonPath.read(mvc.perform(auth(post("/api/voice/test-calls"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactId\":" + contact + ",\"scriptName\":\"Enquiry follow-up\",\"brain\":\"LOCAL\"}"))
                .andReturn().getResponse().getContentAsString(), "$.callId");
        // The stand-in model books the call-back, then says goodbye without log_call_outcome.
        mvc.perform(auth(post("/api/voice/test-calls/" + callId + "/say"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"I am driving, call me later\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.over").value(true))
                .andExpect(jsonPath("$.added[*].tool").value(Matchers.hasItem("schedule_callback")))
                // No review hand-over, which would have overwritten the outcome with HANDED_OFF.
                .andExpect(jsonPath("$.added[*].tool").value(Matchers.not(Matchers.hasItem("request_human_handoff"))))
                .andExpect(jsonPath("$.outcome").value("CALLBACK_REQUESTED"));
    }

    @Test
    void aBookedCallBackOrTheIdentityLockOutSettlesTheCall() {
        assertThat(LlmBrain.settlesTheCall("schedule_callback", ToolResult.ok(Map.of("callback_id", 7), null))).isTrue();
        assertThat(LlmBrain.settlesTheCall("verify_identity",
                ToolResult.ok(Map.of("verified", false, "attempts_left", 0, "handoff_mode", "CALLBACK_TASK"), null))).isTrue();
        // A wrong answer with tries left, a passed check, a failed tool or any other tool settles nothing.
        assertThat(LlmBrain.settlesTheCall("verify_identity", ToolResult.ok(Map.of("verified", false, "attempts_left", 2), null))).isFalse();
        assertThat(LlmBrain.settlesTheCall("verify_identity", ToolResult.ok(Map.of("verified", true, "attempts_left", 3), null))).isFalse();
        assertThat(LlmBrain.settlesTheCall("schedule_callback", ToolResult.error(ToolResult.NOT_AVAILABLE, "down"))).isFalse();
        assertThat(LlmBrain.settlesTheCall("search_faq", ToolResult.ok(Map.of("answers", List.of()), null))).isFalse();
    }

    @Test
    void aMadeUpDateIsNeverSpoken() throws Exception {
        String admin = admin();
        long contact = contact(admin);
        String callId = JsonPath.read(mvc.perform(auth(post("/api/voice/test-calls"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactId\":" + contact + ",\"scriptName\":\"Enquiry follow-up\",\"brain\":\"LOCAL\"}"))
                .andReturn().getResponse().getContentAsString(), "$.callId");
        mvc.perform(auth(post("/api/voice/test-calls/" + callId + "/say"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Okay, when is my visit?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.over").value(false))
                // The invented "Thursday, 20th April" is dropped; the caller hears only the second, honest answer.
                .andExpect(jsonPath("$.added[?(@.role == 'agent')].text").value(Matchers.contains(
                        "I'm sorry, I can't check that right now. Shall I ask our team to call you?")));
    }

    @Test
    void aDistressedCallerAlwaysGetsTheUrgentHandOver() throws Exception {
        String admin = admin();
        long contact = contact(admin);
        String callId = JsonPath.read(mvc.perform(auth(post("/api/voice/test-calls"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactId\":" + contact + ",\"scriptName\":\"Enquiry follow-up\",\"brain\":\"LOCAL\"}"))
                .andReturn().getResponse().getContentAsString(), "$.callId");
        // The stand-in model books an ordinary call-back; the caller just said they lost their job.
        mvc.perform(auth(post("/api/voice/test-calls/" + callId + "/say"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"I lost my job last month, please book me a call later\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added[*].tool").value(Matchers.hasItem("request_human_handoff")))
                .andExpect(jsonPath("$.added[*].tool").value(Matchers.not(Matchers.hasItem("schedule_callback"))))
                .andExpect(jsonPath("$.over").value(true));
    }

    @Test
    void callerSignalsDecideTheHandOver() {
        LlmClient.ToolCall callback = new LlmClient.ToolCall("c1", "schedule_callback", Map.of("reason", "Wants a call later."));
        LlmClient.ToolCall handoff = new LlmClient.ToolCall("c2", "request_human_handoff", Map.of("reason_code", "COMPLAINT_OR_DISPUTE"));
        LlmClient.ToolCall verifyFailed = new LlmClient.ToolCall("c3", "request_human_handoff", Map.of("reason_code", "VERIFICATION_FAILED"));
        LlmClient.ToolCall lookup = new LlmClient.ToolCall("c4", "get_contact_details", Map.of());
        String distress = " Yes. I lost my job last month and I don't know how I'll manage.";
        String dispute = " That amount is wrong, I already paid it.";
        // Distress: a call-back or another hand-over becomes the DISTRESS hand-over, same id.
        assertThat(CallerSignals.upgrade(callback, distress)).satisfies(c -> {
            assertThat(c.name()).isEqualTo("request_human_handoff");
            assertThat(c.id()).isEqualTo("c1");
            assertThat(c.arguments()).containsEntry("reason_code", "DISTRESS");
            assertThat(String.valueOf(c.arguments().get("summary"))).contains("Wants a call later.");
        });
        assertThat(CallerSignals.upgrade(handoff, distress).arguments()).containsEntry("reason_code", "DISTRESS");
        // Dispute: a call-back becomes COMPLAINT_OR_DISPUTE; a hand-over already for it is left as it is.
        assertThat(CallerSignals.upgrade(callback, dispute).arguments()).containsEntry("reason_code", "COMPLAINT_OR_DISPUTE");
        assertThat(CallerSignals.upgrade(handoff, dispute)).isSameAs(handoff);
        // Never changed: an unverified caller's hand-over, other tools, or a call with no such words.
        assertThat(CallerSignals.upgrade(verifyFailed, distress)).isSameAs(verifyFailed);
        assertThat(CallerSignals.upgrade(lookup, distress)).isSameAs(lookup);
        assertThat(CallerSignals.upgrade(callback, " I'm in a meeting, call me at 5.")).isSameAs(callback);
        // Invented reasons models use are understood; a wrong person is not a hand-over.
        assertThat(CallerSignals.reason("DISPUTE")).isEqualTo(Voice.HandoffReason.COMPLAINT_OR_DISPUTE);
        assertThat(CallerSignals.reason("human")).isEqualTo(Voice.HandoffReason.CALLER_REQUEST);
        assertThat(CallerSignals.reason("caller request")).isEqualTo(Voice.HandoffReason.CALLER_REQUEST);
        assertThat(CallerSignals.reason("WRONG_PERSON")).isNull();
    }

    @Test
    void factsNobodyGaveAreCaught() {
        String heard = "Short sentences (under 20 words). Your EMI amount is 5,000 rupees, due Friday 3 October 2026.";
        assertThat(FactGuard.unheard("Your visit is on Thursday, 20th April.", heard)).containsExactly("thursday", "april");
        assertThat(FactGuard.unheard("Your EMI of 5,000 rupees is due on Friday 3 October 2026.", heard)).isEmpty();
        // The same list as the training kit's checker gives (sim.unheard): a number and a length of time.
        assertThat(FactGuard.unheard("You owe 7,500 rupees within 2 days.", heard)).containsExactly("7", "500", "2", "2 days");
        // "may" as an ordinary word is not a month.
        assertThat(FactGuard.unheard("May I have your full name?", heard)).isEmpty();
        assertThat(FactGuard.unheard("It is due on 12 May.", heard)).containsExactly("12", "may");
    }

    @Test
    void anInventedHandOverReasonIsAskedAgain() throws Exception {
        String admin = admin();
        long contact = contact(admin);
        String callId = JsonPath.read(mvc.perform(auth(post("/api/voice/test-calls"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactId\":" + contact + ",\"scriptName\":\"Enquiry follow-up\",\"brain\":\"LOCAL\"}"))
                .andReturn().getResponse().getContentAsString(), "$.callId");
        mvc.perform(auth(post("/api/voice/test-calls/" + callId + "/say"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Please connect me to a person.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added[?(@.tool == 'request_human_handoff')].status").value(Matchers.contains("INVALID_ARGUMENT", "OK")))
                .andExpect(jsonPath("$.over").value(true));
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
    void aToolCallWrittenAsTextIsMadeNotSpoken() {
        List<LlmClient.ToolSpec> tools = List.of(
                new LlmClient.ToolSpec("log_call_outcome", "Records how the call ended.", Map.of("type", "object")),
                new LlmClient.ToolSpec("opt_out", "Stops the calls.", Map.of("type", "object")));
        // As small models write it, underscores and all lost.
        LlmClient.Reply r = LlmClients.recoverWrittenCalls(
                "Thank you. logcalloutcome({\"outcome_code\": \"CALLBACK_REQUESTED\", \"note\": \"5 PM\"})", tools, JSON);
        assertThat(r.toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("log_call_outcome");
            assertThat(c.arguments()).containsEntry("outcome_code", "CALLBACK_REQUESTED");
        });
        assertThat(r.text()).isEqualTo("Thank you.");
        // Qwen's own tool-call format, left in the text.
        r = LlmClients.recoverWrittenCalls("<tool_call>\n{\"name\": \"opt_out\", \"arguments\": {\"scope\": \"AI_CALLS\"}}\n</tool_call>", tools, JSON);
        assertThat(r.toolCalls()).singleElement().satisfies(c -> assertThat(c.name()).isEqualTo("opt_out"));
        assertThat(r.text()).isEmpty();
        // Ordinary speech, or a call to a tool that does not exist, is left alone.
        assertThat(LlmClients.recoverWrittenCalls("We are open from 9 to 6.", tools, JSON).toolCalls()).isEmpty();
        assertThat(LlmClients.recoverWrittenCalls("delete_everything({\"all\": true})", tools, JSON).toolCalls()).isEmpty();
    }

    @Test
    void longRepliesAreCutToThreeSentences() {
        assertThat(LlmBrain.brief("One. Two. Three.")).isEqualTo("One. Two. Three.");
        assertThat(LlmBrain.brief("One. Two! Three. Four. When can you pay?")).isEqualTo("One. Two! When can you pay?");
        // "Dr." does not end a sentence.
        assertThat(LlmBrain.brief("Your doctor is Dr. Meera Rao. It is at 4 pm. Bring your card.")).isEqualTo(
                "Your doctor is Dr. Meera Rao. It is at 4 pm. Bring your card.");
    }

    @Test
    void spokenTextLosesMarkdown() {
        assertThat(LlmBrain.spoken("**Sure!**\n- Monday to Friday\n- 9 to 6")).isEqualTo("Sure! Monday to Friday 9 to 6");
        assertThat(LlmBrain.spoken(null)).isEmpty();
        // Hidden reasoning is never read aloud, finished or cut off.
        assertThat(LlmBrain.spoken("<think>\nThe caller is busy.\n</think>\n\nNo problem. When should we call?"))
                .isEqualTo("No problem. When should we call?");
        assertThat(LlmBrain.spoken("Sure. <think>maybe I should")).isEqualTo("Sure.");
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
        if (String.valueOf(lastMsg.get("content")).contains("say goodbye")) {
            reply(ex, 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Thanks for your time. "
                    + "Dr. Rao will be glad to hear it. We will be in touch. Have a lovely day. Goodbye.\"}}]}");
            return;
        }
        String lastCaller = messages.stream().filter(m -> "user".equals(m.get("role"))).reduce((a, b) -> b)
                .map(m -> String.valueOf(m.get("content"))).orElse("");
        if (lastCaller.contains("lost my job")) {
            // Books an ordinary call-back for a distressed caller; the CRM should make it the urgent hand-over.
            answer(ex, "tool".equals(lastMsg.get("role")) ? "I'm so sorry. Someone from our team will call you. Take care, goodbye."
                    : "<tool_call>\n{\"name\": \"schedule_callback\", \"arguments\": {\"preferred_time_text\": \"later\", "
                    + "\"reason\": \"Wants a call later.\"}}\n</tool_call>");
            return;
        }
        if (lastCaller.contains("when is my visit")) {
            // Invents a date, unless the CRM's private note (FactGuard) told it not to guess.
            answer(ex, String.valueOf(messages.get(0).get("content")).contains("PRIVATE NOTE")
                    ? "I'm sorry, I can't check that right now. Shall I ask our team to call you?"
                    : "Your visit is on Thursday, 20th April.");
            return;
        }
        if (lastCaller.contains("connect me")) {
            // Invents a hand-over reason first; uses a valid one once the CRM refuses it.
            String last = String.valueOf(lastMsg.get("content"));
            // "WRONG_PERSON" is never a hand-over reason ("DISPUTE" would now be understood: CallerSignals).
            answer(ex, !"tool".equals(lastMsg.get("role")) ? handoff("WRONG_PERSON")
                    : last.contains("INVALID_ARGUMENT") ? handoff("CALLER_REQUEST") : "Of course. Please stay on the line.");
            return;
        }
        if (lastCaller.contains("call me later")) {
            // Books the call-back, then forgets log_call_outcome and says goodbye.
            String content = "tool".equals(lastMsg.get("role")) ? "Of course. Our team will call you back. Thank you, goodbye."
                    : "<tool_call>\n{\"name\": \"schedule_callback\", \"arguments\": {\"preferred_time_text\": \"later\", "
                    + "\"reason\": \"Busy; asked for a call later.\"}}\n</tool_call>";
            reply(ex, 200, JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant",
                    "content", content))))));
            return;
        }
        if ("tool".equals(lastMsg.get("role"))) {
            reply(ex, 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"**Let me help:** our team can call you back.\"}}]}");
            return;
        }
        // As Ollama returns it when no tools are passed: the model's own <tool_call> text.
        reply(ex, 200, JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant",
                "content", "<tool_call>\n{\"name\": \"search_faq\", \"arguments\": {\"query\": \"opening hours\"}}\n</tool_call>"))))));
    }

    private static String handoff(String reason) {
        return "<tool_call>\n{\"name\": \"request_human_handoff\", \"arguments\": {\"reason_code\": \"" + reason
                + "\", \"summary\": \"Asked for a person.\"}}\n</tool_call>";
    }

    private static void answer(HttpExchange ex, String content) throws IOException {
        reply(ex, 200, JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant",
                "content", content))))));
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
