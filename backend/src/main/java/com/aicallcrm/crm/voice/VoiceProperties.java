package com.aicallcrm.crm.voice;

import java.math.BigDecimal;
import java.time.LocalTime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code app.voice.*}. With no provider configured the platform is
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
        /** AI calls are placed only from windowStart to windowEnd, Indian time: 9 AM to 6 PM unless set otherwise. */
        @DefaultValue("18:00") LocalTime windowEnd,
        @DefaultValue("2") int maxPerNumberPerDay,
        @DefaultValue("120") int minGapMinutes,
        @DefaultValue("24") int dndTtlHours,
        /** Days transcripts and recordings are kept; never fewer than {@link #MIN_RETENTION_DAYS}. */
        @DefaultValue("365") int retentionDays,
        @DefaultValue("claude-sonnet-5-5") String defaultModel,
        @DefaultValue("claude-sonnet-5-5") String richModel,
        /** Staff desk number for a live transfer. Blank: every handoff becomes a callback task. */
        @DefaultValue("") String deskTransferNumber,
        @DefaultValue("09:30") LocalTime deskOpen,
        @DefaultValue("18:30") LocalTime deskClose,
        /** Rough all-in cost of one connected minute, used only for the cost estimate. */
        @DefaultValue("15") BigDecimal costPerMinuteInr,
        @DefaultValue("60000") BigDecimal monthlyCapInr,
        /** Folder on this server for call recordings (one audio file per call). Back it up with the database. */
        @DefaultValue("data/recordings") String recordingsDir) {

    /** Recordings must be kept at least this long (decided 17 Nov 2026). */
    public static final int MIN_RETENTION_DAYS = 120;

    public VoiceProperties {
        if (retentionDays < MIN_RETENTION_DAYS) {
            throw new IllegalArgumentException("app.voice.retention-days (VOICE_RETENTION_DAYS) is " + retentionDays
                    + ": transcripts and call recordings must be kept at least " + MIN_RETENTION_DAYS + " days");
        }
    }

    public boolean signingConfigured() {
        return hmacSecret != null && hmacSecret.length() >= 32;
    }
}
