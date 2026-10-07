package com.aicallcrm.crm.voice;

import java.util.List;
import java.util.UUID;

import com.aicallcrm.crm.voice.VoiceCallService.Line;

/**
 * Whatever holds the conversation on a call: it hears what the caller said and decides what the agent says,
 * using the CRM's tools through {@link ToolExecutor} (so the verification rule, the audit trail and the
 * hand-overs are the same for every brain).
 */
public interface ConversationBrain {

    /** The kinds of brain the CRM can use. */
    enum Kind {
        /** Rule-based stand-in: free, instant, keyword matching only. */
        SIMULATED,
        /** An open model on our own machine (Ollama, vLLM or any OpenAI-compatible server). */
        LOCAL,
        /** Claude, through Anthropic's API. */
        CLAUDE
    }

    /** First thing on a call: read the context, then say the script's opening line. */
    List<Line> open(UUID callId, String openingLine);

    /** One turn: the caller said {@code said}; returns the tool calls made and what the agent replies. */
    List<Line> respond(UUID callId, String said);

    /** True once the agent has wrapped the call up. */
    boolean over(UUID callId);

    void forget(UUID callId);
}
