package com.aicallcrm.crm.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code app.voice.plivo.*}, used when {@code app.voice.platform=plivo}. Each customer's copy of the
 * CRM has its own Plivo account (its own KYC, numbers and DLT registration); see docs/voice-agent.md.
 */
@ConfigurationProperties(prefix = "app.voice.plivo")
public record PlivoProperties(
        @DefaultValue("") String authId,
        /** Also checks Plivo's signature on every callback. Blank: the Plivo callbacks refuse everything. */
        @DefaultValue("") String authToken,
        /** The rented Indian number calls come from, E.164 (+91...). */
        @DefaultValue("") String fromNumber,
        /** This CRM's public HTTPS address, exactly as Plivo reaches it (callback URLs and their signatures). */
        @DefaultValue("") String publicUrl,
        /** Our speech engine's call stream, e.g. wss://voice.example.in/call (voice-engine/). */
        @DefaultValue("") String engineUrl,
        @DefaultValue("https://api.plivo.com/v1") String apiBase,
        @DefaultValue("30") int ringTimeoutSec) {

    public boolean configured() {
        return !authId.isBlank() && !authToken.isBlank() && !fromNumber.isBlank() && !publicUrl.isBlank()
                && !engineUrl.isBlank();
    }

    /** {@code publicUrl} without a trailing slash, followed by {@code path}. */
    String url(String path) {
        return (publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl) + path;
    }
}
