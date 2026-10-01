package com.aicallcrm.crm.voice;

import java.time.Instant;

import com.aicallcrm.crm.voice.Voice.ScriptKind;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One version of a call script: what the agent says first and the rules it follows. Scripts are grouped by
 * name ("Payment reminder"); versions are never edited, and at most one approved version per name is active.
 */
@Entity
@Table(name = "voice_script")
public class VoiceScript {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    @Enumerated(EnumType.STRING)
    private ScriptKind kind;
    private int version;
    private String model;
    private String systemPrompt;
    private String openingLine;
    private Long approvedBy;
    private Instant approvedAt;
    private boolean active;
    private Long createdBy;
    private Instant createdAt;

    protected VoiceScript() {
    }

    public VoiceScript(String name, ScriptKind kind, int version, String model, String systemPrompt,
                       String openingLine, Long createdBy) {
        this.name = name;
        this.kind = kind;
        this.version = version;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.openingLine = openingLine;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void approve(Long userId) {
        this.approvedBy = userId;
        this.approvedAt = Instant.now();
        this.active = true;
    }

    public void retire() {
        this.active = false;
    }

    public boolean usable() {
        return active && approvedAt != null;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public ScriptKind getKind() { return kind; }
    public int getVersion() { return version; }
    public String getModel() { return model; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getOpeningLine() { return openingLine; }
    public Long getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
