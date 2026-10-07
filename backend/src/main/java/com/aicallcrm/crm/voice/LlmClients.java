package com.aicallcrm.crm.voice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

/** Talking to open models over the OpenAI-compatible API (Ollama, vLLM, llama.cpp). Claude: see {@link ClaudeLlm}. */
final class LlmClients {

    private LlmClients() {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Posts JSON and returns the parsed JSON body, or throws with the status and the start of the error. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> post(ObjectMapper mapper, String url, Map<String, String> headers, Object body,
                                    int timeoutSeconds) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        headers.forEach(b::header);
        HttpResponse<String> res;
        try {
            res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("The model server could not be reached (" + e.getClass().getSimpleName() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the model", e);
        }
        if (res.statusCode() / 100 != 2) {
            String err = res.body() == null ? "" : res.body();
            throw new IllegalStateException("The model server answered " + res.statusCode() + ": "
                    + (err.length() > 300 ? err.substring(0, 300) : err));
        }
        return mapper.readValue(res.body(), Map.class);
    }

    // ------------------------------------------------------------------ OpenAI-compatible (Ollama, vLLM...)

    static final class OpenAiCompatible implements LlmClient {

        private final AiProperties props;
        private final ObjectMapper mapper;

        OpenAiCompatible(AiProperties props, ObjectMapper mapper) {
            this.props = props;
            this.mapper = mapper;
        }

        @Override
        public String describe() {
            return "Local model (" + props.localModel() + ")";
        }

        @Override
        public void warmUp(String system, List<Message> messages, List<ToolSpec> tools) {
            post(mapper, props.localUrl() + "/v1/chat/completions", Map.of(), body(system, messages, tools, 1),
                    props.timeoutSeconds() * 2);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Reply chat(String system, List<Message> messages, List<ToolSpec> tools) {
            Map<String, Object> res = post(mapper, props.localUrl() + "/v1/chat/completions", Map.of(),
                    body(system, messages, tools, props.maxReplyTokens()), props.timeoutSeconds());
            return parse(res);
        }

        private Map<String, Object> body(String system, List<Message> messages, List<ToolSpec> tools, int maxTokens) {
            List<Map<String, Object>> msgs = new ArrayList<>();
            msgs.add(Map.of("role", "system", "content", system));
            for (Message m : messages) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("role", m.role());
                x.put("content", m.text() == null ? "" : m.text());
                if ("assistant".equals(m.role()) && !m.toolCalls().isEmpty()) {
                    x.put("tool_calls", m.toolCalls().stream().map(c -> Map.of("id", c.id(), "type", "function",
                            "function", Map.of("name", c.name(), "arguments", mapper.writeValueAsString(c.arguments()))))
                            .toList());
                }
                if ("tool".equals(m.role())) {
                    x.put("tool_call_id", m.toolCallId());
                }
                msgs.add(x);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", props.localModel());
            body.put("messages", msgs);
            body.put("max_tokens", maxTokens);
            body.put("temperature", 0.3);
            body.put("stream", false);
            body.put("tools", tools.stream().map(t -> Map.of("type", "function", "function",
                    Map.of("name", t.name(), "description", t.description(), "parameters", t.inputSchema()))).toList());
            // Keep the model loaded between turns of a call.
            body.put("keep_alive", "30m");
            return body;
        }

        @SuppressWarnings("unchecked")
        private Reply parse(Map<String, Object> res) {
            List<?> choices = res.get("choices") instanceof List<?> l ? l : List.of();
            if (choices.isEmpty()) {
                return new Reply("", List.of());
            }
            Map<String, Object> message = (Map<String, Object>) ((Map<String, Object>) choices.get(0)).get("message");
            String text = message == null || message.get("content") == null ? "" : String.valueOf(message.get("content"));
            List<ToolCall> calls = new ArrayList<>();
            if (message != null && message.get("tool_calls") instanceof List<?> tcs) {
                for (Object o : tcs) {
                    Map<String, Object> tc = (Map<String, Object>) o;
                    Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                    Object rawArgs = fn.get("arguments");
                    Map<String, Object> args = rawArgs instanceof Map<?, ?> m ? (Map<String, Object>) m
                            : rawArgs == null || String.valueOf(rawArgs).isBlank() ? Map.of()
                            : mapper.readValue(String.valueOf(rawArgs), Map.class);
                    String id = tc.get("id") == null ? "call_" + UUID.randomUUID() : String.valueOf(tc.get("id"));
                    calls.add(new ToolCall(id, String.valueOf(fn.get("name")), args));
                }
            }
            return new Reply(text.trim(), calls);
        }
    }
}
