package com.mbbscrm.crm.voice;

import java.time.Instant;

import com.mbbscrm.crm.common.Language;
import com.mbbscrm.crm.voice.Voice.Purpose;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One version of what the agent is told to do for a call purpose in one language (spec 18.11). Versions
 * are never edited; at most one approved version per purpose and language is active.
 */
@Entity
@Table(name = "voice_script_template")
public class VoiceScript {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private Purpose purpose;
    @Enumerated(EnumType.STRING)
    private Language language;
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

    public VoiceScript(Purpose purpose, Language language, int version, String model, String systemPrompt,
                       String openingLine, Long createdBy) {
        this.purpose = purpose;
        this.language = language;
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
    public Purpose getPurpose() { return purpose; }
    public Language getLanguage() { return language; }
    public int getVersion() { return version; }
    public String getModel() { return model; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getOpeningLine() { return openingLine; }
    public Long getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
