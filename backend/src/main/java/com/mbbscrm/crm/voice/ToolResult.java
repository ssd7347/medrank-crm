package com.mbbscrm.crm.voice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a tool hands back to the agent (spec 18.8.1). A business problem is still a normal reply with
 * {@code ok=false} and a code the agent knows how to react to; internals are never leaked.
 */
public record ToolResult(boolean ok, Map<String, Object> data, String speakHint, String errorCode,
                         String errorMessage) {

    public static final String VERIFICATION_REQUIRED = "VERIFICATION_REQUIRED";
    public static final String NOT_AVAILABLE = "NOT_AVAILABLE";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String INTERNAL = "INTERNAL";
    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    public static final String UNKNOWN_CALL = "UNKNOWN_CALL";
    public static final String CALL_ENDED = "CALL_ENDED";
    public static final String UNKNOWN_TOOL = "UNKNOWN_TOOL";

    public static ToolResult ok(Map<String, Object> data, String speakHint) {
        return new ToolResult(true, data, speakHint, null, null);
    }

    public static ToolResult error(String code, String message) {
        return new ToolResult(false, null, null, code, message);
    }

    /** "OK" or the error code: what the audit trail records. */
    public String status() {
        return ok ? "OK" : errorCode;
    }

    /** The JSON body exactly as the specification defines it. */
    public Map<String, Object> envelope() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("data", data);
        out.put("speak_hint", speakHint);
        if (ok) {
            out.put("error", null);
        } else {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", errorCode);
            error.put("message", errorMessage);
            out.put("error", error);
        }
        return out;
    }
}
