package com.mbbscrm.crm.voice;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.mbbscrm.crm.assistant.RateLimiter;
import com.mbbscrm.crm.voice.VoiceTools.Tool;

/**
 * Runs one tool for one call (spec 18.7.1 and 18.8). The caller supplies only the call id: whose call it
 * is, and how far the caller has been verified, are read from our own record of the call. The verification
 * level is enforced here, in code, whatever the script tells the agent. Every run is written to the audit
 * trail with its result and how long it took.
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);
    static final int MAX_TOOL_CALLS_PER_CALL = 60;

    private final VoiceCallRepository calls;
    private final VoiceToolAuditRepository audits;
    private final VoiceTools tools;
    private final RateLimiter limiter;
    private final TransactionTemplate tx;

    public ToolExecutor(VoiceCallRepository calls, VoiceToolAuditRepository audits, VoiceTools tools,
                        RateLimiter limiter, TransactionTemplate tx) {
        this.calls = calls;
        this.audits = audits;
        this.tools = tools;
        this.limiter = limiter;
        this.tx = tx;
    }

    public ToolResult execute(UUID callId, String toolName, Map<String, Object> args) {
        long t0 = System.nanoTime();
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        ToolResult result;
        try {
            result = tx.execute(status -> run(callId, toolName, safeArgs));
        } catch (RuntimeException e) {
            log.warn("Voice tool {} failed for call {}: {}", toolName, callId, e.toString());
            result = ToolResult.error(ToolResult.INTERNAL, "Something went wrong. Apologise and offer a callback.");
        }
        int latencyMs = (int) ((System.nanoTime() - t0) / 1_000_000);
        ToolResult done = result;
        if (done != null && !ToolResult.UNKNOWN_CALL.equals(done.errorCode())) {
            try {
                tx.executeWithoutResult(status -> audits.save(new VoiceToolAudit(callId,
                        toolName.length() > 40 ? toolName.substring(0, 40) : toolName,
                        DndService.sha256(new TreeMap<>(safeArgs).toString()), done.status(), latencyMs)));
            } catch (RuntimeException e) {
                log.warn("Could not write the tool audit for call {}: {}", callId, e.toString());
            }
        }
        return done;
    }

    private ToolResult run(UUID callId, String toolName, Map<String, Object> args) {
        VoiceCall call = callId == null ? null : calls.findById(callId).orElse(null);
        if (call == null) {
            return ToolResult.error(ToolResult.UNKNOWN_CALL, "This call is not known.");
        }
        if (!call.getStatus().live()) {
            return ToolResult.error(ToolResult.CALL_ENDED, "This call has already ended.");
        }
        if (!limiter.allow("voice-tools:" + callId, MAX_TOOL_CALLS_PER_CALL, Duration.ofMinutes(15))) {
            return ToolResult.error(ToolResult.RATE_LIMITED, "Too many lookups in this call. Wrap up and offer a callback.");
        }
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return ToolResult.error(ToolResult.UNKNOWN_TOOL, "There is no such tool.");
        }
        if (tool.minLevel() > call.getVerificationLevel()) {
            return ToolResult.error(ToolResult.VERIFICATION_REQUIRED, "Ask the caller to verify identity first.");
        }
        return tool.handler().handle(call, args);
    }
}
