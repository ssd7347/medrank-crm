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

/** The two ways of talking to a language model: Anthropic's API, and the OpenAI-compatible API of open models. */
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

    // ------------------------------------------------------------------ Anthropic

    static final class Anthropic implements LlmClient {

        private final AiProperties props;
        private final ObjectMapper mapper;

        Anthropic(AiProperties props, ObjectMapper mapper) {
            this.props = props;
            this.mapper = mapper;
        }

        @Override
        public String describe() {
            return "Claude (" + props.anthropicModel() + ")";
        }

        @Override
        @SuppressWarnings("unchecked")
        public Reply chat(String system, List<Message> messages, List<ToolSpec> tools) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", props.anthropicModel());
            body.put("max_tokens", props.maxReplyTokens());
            body.put("system", system);
            body.put("tools", tools.stream().map(t -> Map.of("name", t.name(), "description", t.description(),
                    "input_schema", t.inputSchema())).toList());
            body.put("messages", toAnthropic(messages));
            Map<String, Object> res = post(mapper, props.anthropicUrl() + "/v1/messages",
                    Map.of("x-api-key", props.anthropicApiKey(), "anthropic-version", "2023-06-01"), body,
                    props.timeoutSeconds());
            StringBuilder text = new StringBuilder();
            List<ToolCall> calls = new ArrayList<>();
            if (res.get("content") instanceof List<?> blocks) {
                for (Object o : blocks) {
                    Map<String, Object> block = (Map<String, Object>) o;
                    if ("text".equals(block.get("type"))) {
                        text.append(block.get("text"));
                    } else if ("tool_use".equals(block.get("type"))) {
                        calls.add(new ToolCall(String.valueOf(block.get("id")), String.valueOf(block.get("name")),
                                block.get("input") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of()));
                    }
                }
            }
            return new Reply(text.toString().trim(), calls);
        }

        /** Anthropic wants tool results inside a user message, all results of one turn together. */
        private static List<Map<String, Object>> toAnthropic(List<Message> messages) {
            List<Map<String, Object>> out = new ArrayList<>();
            List<Map<String, Object>> pendingResults = new ArrayList<>();
            for (Message m : messages) {
                if ("tool".equals(m.role())) {
                    pendingResults.add(Map.of("type", "tool_result", "tool_use_id", m.toolCallId(), "content", m.text()));
                    continue;
                }
                if (!pendingResults.isEmpty()) {
                    out.add(Map.of("role", "user", "content", List.copyOf(pendingResults)));
                    pendingResults.clear();
                }
                if ("assistant".equals(m.role())) {
                    List<Map<String, Object>> content = new ArrayList<>();
                    if (m.text() != null && !m.text().isBlank()) {
                        content.add(Map.of("type", "text", "text", m.text()));
                    }
                    for (ToolCall c : m.toolCalls()) {
                        content.add(Map.of("type", "tool_use", "id", c.id(), "name", c.name(), "input", c.arguments()));
                    }
                    out.add(Map.of("role", "assistant", "content", content));
                } else {
                    out.add(Map.of("role", "user", "content", m.text()));
                }
            }
            if (!pendingResults.isEmpty()) {
                out.add(Map.of("role", "user", "content", List.copyOf(pendingResults)));
            }
            return out;
        }
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
        @SuppressWarnings("unchecked")
        public Reply chat(String system, List<Message> messages, List<ToolSpec> tools) {
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
            body.put("max_tokens", props.maxReplyTokens());
            body.put("temperature", 0.3);
            body.put("stream", false);
            body.put("tools", tools.stream().map(t -> Map.of("type", "function", "function",
                    Map.of("name", t.name(), "description", t.description(), "parameters", t.inputSchema()))).toList());
            Map<String, Object> res = post(mapper, props.localUrl() + "/v1/chat/completions", Map.of(), body,
                    props.timeoutSeconds());
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
