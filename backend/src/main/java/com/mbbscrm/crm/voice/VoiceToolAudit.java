package com.mbbscrm.crm.voice;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One tool the agent used during a call: which, how it went and how long it took (spec 18.7.1). */
@Entity
@Table(name = "voice_tool_audit")
public class VoiceToolAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID callId;
    private String tool;
    private String argsHash;
    private String status;
    private Integer latencyMs;
    private Instant createdAt;

    protected VoiceToolAudit() {
    }

    public VoiceToolAudit(UUID callId, String tool, String argsHash, String status, int latencyMs) {
        this.callId = callId;
        this.tool = tool;
        this.argsHash = argsHash;
        this.status = status;
        this.latencyMs = latencyMs;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getCallId() { return callId; }
    public String getTool() { return tool; }
    public String getStatus() { return status; }
    public Integer getLatencyMs() { return latencyMs; }
    public Instant getCreatedAt() { return createdAt; }
}
