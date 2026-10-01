package com.mbbscrm.crm.voice;

import java.math.BigDecimal;
import java.time.LocalTime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code app.voice.*} (spec 18.18.1). With no provider configured the platform is
 * "simulated": every part of the agent works except that no phone actually rings.
 */
@ConfigurationProperties(prefix = "app.voice")
public record VoiceProperties(
        /** Master switch. False: no calls are placed and incoming calls get the fallback. */
        @DefaultValue("true") boolean enabled,
        /** Which {@link VoicePlatformClient} to use: simulated, or the name of a connected adapter. */
        @DefaultValue("simulated") String platform,
        /** Shared secret for signing tool and webhook requests. Blank: those endpoints refuse everything. */
        @DefaultValue("") String hmacSecret,
        @DefaultValue("2500") int toolTimeoutMs,
        @DefaultValue("09:00") LocalTime windowStart,
        @DefaultValue("20:00") LocalTime windowEnd,
        @DefaultValue("2") int maxPerNumberPerDay,
        @DefaultValue("120") int minGapMinutes,
        @DefaultValue("24") int dndTtlHours,
        @DefaultValue("365") int retentionDays,
        @DefaultValue("claude-haiku-4-5-20251001") String defaultModel,
        @DefaultValue("claude-sonnet-5-5") String richModel,
        /** Counsellor desk number for a live transfer. Blank: every handoff becomes a callback task. */
        @DefaultValue("") String deskTransferNumber,
        @DefaultValue("09:30") LocalTime deskOpen,
        @DefaultValue("18:30") LocalTime deskClose,
        /** Rough all-in cost of one connected minute, used only for the cost estimate. */
        @DefaultValue("15") BigDecimal costPerMinuteInr,
        @DefaultValue("60000") BigDecimal monthlyCapInr) {

    public boolean signingConfigured() {
        return hmacSecret != null && hmacSecret.length() >= 32;
    }
}
