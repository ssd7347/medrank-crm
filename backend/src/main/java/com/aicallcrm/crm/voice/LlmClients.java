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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aicallcrm.crm.voice.LlmClient.Reply;
import com.aicallcrm.crm.voice.LlmClient.ToolCall;
import com.aicallcrm.crm.voice.LlmClient.ToolSpec;

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
            Reply r = parse(res);
            return r.toolCalls().isEmpty() ? recoverWrittenCalls(r.text(), tools, mapper) : r;
        }

        /**
         * The tools travel inside the system prompt, written exactly as our model saw them in training, and are not
         * sent as {@code tools}: Ollama's own tool support mangles nested arguments (verify_identity's
         * {@code {"full_name": ..., "answers": {...}}} came back as {@code {"full_name": "Name, answers: {"}} although
         * the model wrote it correctly). The model's {@code <tool_call>} text is read by {@link #recoverWrittenCalls}.
         */
        private Map<String, Object> body(String system, List<Message> messages, List<ToolSpec> tools, int maxTokens) {
            List<Map<String, Object>> msgs = new ArrayList<>();
            msgs.add(Map.of("role", "system", "content", tools.isEmpty() ? system : system + "\n\n" + toolsInText(tools)));
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

    /**
     * The tool list as the Qwen3 chat template writes it, and so as the training text has it
     * ({@code training/callkit.py} tools_in_system): one tool per line, keys sorted, Python's JSON spacing.
     */
    static String toolsInText(List<ToolSpec> tools) {
        StringBuilder s = new StringBuilder("# Tools\n\nYou may call one or more functions to assist with the user query.\n\n"
                + "You are provided with function signatures within <tools></tools> XML tags:\n<tools>");
        for (ToolSpec t : tools) {
            s.append('\n').append(pythonJson(Map.of("type", "function", "function",
                    Map.of("name", t.name(), "description", t.description(), "parameters", t.inputSchema()))));
        }
        return s.append("\n</tools>\n\nFor each function call, return a json object with function name and arguments within "
                + "<tool_call></tool_call> XML tags:\n<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>")
                .toString();
    }

    /** JSON as Python's {@code json.dumps(x, ensure_ascii=False)} writes it, with the keys sorted. */
    static String pythonJson(Object x) {
        if (x instanceof Map<?, ?> m) {
            StringBuilder s = new StringBuilder("{");
            new java.util.TreeMap<>(m).forEach((k, v) -> s.append(s.length() > 1 ? ", " : "")
                    .append(pythonJson(String.valueOf(k))).append(": ").append(pythonJson(v)));
            return s.append('}').toString();
        }
        if (x instanceof Iterable<?> list) {
            StringBuilder s = new StringBuilder("[");
            for (Object v : list) {
                s.append(s.length() > 1 ? ", " : "").append(pythonJson(v));
            }
            return s.append(']').toString();
        }
        if (x instanceof Object[] arr) {
            return pythonJson(List.of(arr));
        }
        if (x instanceof CharSequence str) {
            StringBuilder s = new StringBuilder("\"");
            str.chars().forEach(c -> {
                switch (c) {
                    case '"' -> s.append("\\\"");
                    case '\\' -> s.append("\\\\");
                    case '\n' -> s.append("\\n");
                    case '\r' -> s.append("\\r");
                    case '\t' -> s.append("\\t");
                    case '\b' -> s.append("\\b");
                    case '\f' -> s.append("\\f");
                    default -> s.append(c < 0x20 ? String.format("\\u%04x", c) : String.valueOf((char) c));
                }
            });
            return s.append('"').toString();
        }
        if (x == null) {
            return "null";
        }
        if (x instanceof Boolean || x instanceof Integer || x instanceof Long || x instanceof Short) {
            return String.valueOf(x);
        }
        if (x instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && !Double.isInfinite(d) ? (long) d + ".0" : String.valueOf(d);
        }
        return pythonJson(String.valueOf(x));
    }

    /** Qwen's own format: every {@code <tool_call>{"name": ..., "arguments": {...}}</tool_call>} in a reply. */
    private static final Pattern TOOL_CALL_TAG = Pattern.compile("(?s)<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>");
    /** {@code <tool_call>{"name": ..., "arguments": {...}}</tool_call>}, or a bare JSON object of that shape. */
    private static final Pattern WRITTEN_JSON = Pattern.compile("(?s)(?:<tool_call>\\s*)?(\\{\\s*\"name\"\\s*:.*\\})\\s*(?:</tool_call>)?");
    /** {@code log_call_outcome({"outcome_code": "WILL_ACT"})}, sometimes with the underscores lost. */
    private static final Pattern WRITTEN_FUNCTION = Pattern.compile("(?s)\\b([A-Za-z_]+)\\s*\\(\\s*(\\{.*?\\})\\s*\\)");

    /**
     * Small open models sometimes write a tool call as text instead of making it. Read aloud, that would be
     * gibberish to the caller, and the call would never be recorded. A call to one of the offered tools is
     * recovered and made; the rest of the text is kept. Anything unreadable is left as it is.
     */
    @SuppressWarnings("unchecked")
    static Reply recoverWrittenCalls(String text, List<ToolSpec> tools, ObjectMapper mapper) {
        if (text == null || text.indexOf('{') < 0) {
            return new Reply(text == null ? "" : text, List.of());
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (ToolSpec t : tools) {
            names.put(t.name(), t.name());
            names.put(squash(t.name()), t.name());
        }
        List<ToolCall> calls = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        Matcher tag = TOOL_CALL_TAG.matcher(text);
        while (tag.find()) {
            Map<String, Object> obj = readObject(tag.group(1), mapper);
            String tool = obj == null || obj.get("name") == null ? null : names.get(String.valueOf(obj.get("name")));
            Object a = obj == null ? null : obj.getOrDefault("arguments", Map.of());
            if (a instanceof String json) {
                a = readObject(json, mapper);
            }
            if (tool != null && a instanceof Map<?, ?> args) {
                calls.add(new ToolCall("call_" + UUID.randomUUID(), tool, (Map<String, Object>) args));
                spans.add(new int[] {tag.start(), tag.end()});
            }
        }
        Matcher f = WRITTEN_FUNCTION.matcher(text);
        boolean tagged = !calls.isEmpty();
        while (!tagged && f.find()) {
            String tool = names.getOrDefault(f.group(1), names.get(squash(f.group(1))));
            Map<String, Object> args = readObject(f.group(2), mapper);
            if (tool != null && args != null) {
                calls.add(new ToolCall("call_" + UUID.randomUUID(), tool, args));
                spans.add(new int[] {f.start(), f.end()});
            }
        }
        if (calls.isEmpty()) {
            Matcher j = WRITTEN_JSON.matcher(text);
            if (j.find()) {
                Map<String, Object> obj = readObject(j.group(1), mapper);
                String tool = obj == null || obj.get("name") == null ? null : names.get(String.valueOf(obj.get("name")));
                if (tool != null && obj.getOrDefault("arguments", Map.of()) instanceof Map<?, ?> a) {
                    calls.add(new ToolCall("call_" + UUID.randomUUID(), tool, (Map<String, Object>) a));
                    spans.add(new int[] {j.start(), j.end()});
                }
            }
        }
        if (calls.isEmpty()) {
            return new Reply(text, List.of());
        }
        StringBuilder rest = new StringBuilder(text);
        for (int i = spans.size() - 1; i >= 0; i--) {
            rest.delete(spans.get(i)[0], spans.get(i)[1]);
        }
        return new Reply(rest.toString().replaceAll("\\s{2,}", " ").trim(), calls);
    }

    private static String squash(String name) {
        return name.replace("_", "").toLowerCase(java.util.Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readObject(String json, ObjectMapper mapper) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
