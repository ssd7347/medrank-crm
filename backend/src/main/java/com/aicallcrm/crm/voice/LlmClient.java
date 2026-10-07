package com.aicallcrm.crm.voice;

import java.util.List;
import java.util.Map;

/**
 * One chat turn with a language model that can call tools. Two implementations: Anthropic's Messages API and
 * the OpenAI-compatible chat API that open-model servers (Ollama, vLLM, llama.cpp) speak.
 */
public interface LlmClient {

    record ToolCall(String id, String name, Map<String, Object> arguments) {
    }

    /** {@code role}: user, assistant or tool. Assistant messages may carry tool calls; tool messages answer one. */
    record Message(String role, String text, List<ToolCall> toolCalls, String toolCallId) {

        static Message user(String text) {
            return new Message("user", text, List.of(), null);
        }

        static Message assistant(String text, List<ToolCall> calls) {
            return new Message("assistant", text, calls == null ? List.of() : calls, null);
        }

        static Message toolResult(String toolCallId, String json) {
            return new Message("tool", json, List.of(), toolCallId);
        }
    }

    record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
    }

    /** {@code text} may be empty when the model only asked for tools. */
    record Reply(String text, List<ToolCall> toolCalls) {
    }

    Reply chat(String system, List<Message> messages, List<ToolSpec> tools);

    /** For screens and logs: "Claude (claude-haiku-4-5…)" or "Local model (qwen2.5:3b)". */
    String describe();
}
