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

    /**
     * {@code role}: user, assistant or tool. Assistant messages may carry tool calls; tool messages answer one.
     * {@code raw}: the provider's own copy of an assistant reply (e.g. with thinking blocks), sent back unchanged.
     */
    record Message(String role, String text, List<ToolCall> toolCalls, String toolCallId, Object raw) {

        static Message user(String text) {
            return new Message("user", text, List.of(), null, null);
        }

        static Message assistant(String text, List<ToolCall> calls) {
            return assistant(text, calls, null);
        }

        static Message assistant(String text, List<ToolCall> calls, Object raw) {
            return new Message("assistant", text, calls == null ? List.of() : calls, null, raw);
        }

        static Message toolResult(String toolCallId, String json) {
            return new Message("tool", json, List.of(), toolCallId, null);
        }
    }

    record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
    }

    /** {@code text} may be empty when the model only asked for tools. {@code raw}: see {@link Message#raw()}. */
    record Reply(String text, List<ToolCall> toolCalls, Object raw) {

        Reply(String text, List<ToolCall> toolCalls) {
            this(text, toolCalls, null);
        }
    }

    Reply chat(String system, List<Message> messages, List<ToolSpec> tools);

    /**
     * Lets the model read the script and tools ahead of the first reply, so that reply comes quickly. Open-model
     * servers keep what they have read; hosted APIs need nothing.
     */
    default void warmUp(String system, List<Message> messages, List<ToolSpec> tools) {
    }

    /** For screens and logs: "Claude (claude-haiku-4-5)" or "Local model (qwen2.5:3b)". */
    String describe();
}
