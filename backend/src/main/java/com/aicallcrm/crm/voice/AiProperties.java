package com.aicallcrm.crm.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code app.voice.ai.*}: which "brain" holds the conversation and where our own speech engine
 * runs. The brain can be the rule-based stand-in, an open model on our own machine (any OpenAI-compatible
 * server such as Ollama or vLLM), or Claude through Anthropic's API.
 */
@ConfigurationProperties(prefix = "app.voice.ai")
public record AiProperties(
        /** Default brain for new conversations: SIMULATED, LOCAL or CLAUDE. */
        @DefaultValue("SIMULATED") String brain,
        /** Anthropic API key. Blank: the Claude brain is unavailable. */
        @DefaultValue("") String anthropicApiKey,
        /** Claude model: claude-sonnet-5-5 (default: smarter, caches every turn) or claude-haiku-4-5 (fastest). */
        @DefaultValue("claude-sonnet-5-5") String anthropicModel,
        @DefaultValue("https://api.anthropic.com") String anthropicUrl,
        /** Base URL of an OpenAI-compatible server with an open model (Ollama by default). */
        @DefaultValue("http://localhost:11434") String localUrl,
        @DefaultValue("qwen2.5:3b") String localModel,
        /** Our speech engine (speech-to-text and text-to-speech), see voice-engine/. */
        @DefaultValue("http://localhost:8090") String speechUrl,
        /** Shared token sent to the speech engine. Blank on a laptop. */
        @DefaultValue("") String speechToken,
        /** How long one model reply may take. Open models on a CPU are slow. */
        @DefaultValue("60") int timeoutSeconds,
        /** Upper limit on the length of one spoken reply. */
        @DefaultValue("300") int maxReplyTokens,
        /** Claude thinks briefly before it answers, so its allowance covers that thinking plus the reply. */
        @DefaultValue("2048") int claudeMaxTokens) {

    public boolean claudeConfigured() {
        return anthropicApiKey != null && !anthropicApiKey.isBlank();
    }
}
