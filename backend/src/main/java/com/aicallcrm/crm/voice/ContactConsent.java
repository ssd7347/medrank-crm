package com.aicallcrm.crm.voice;

import java.time.Instant;

import com.aicallcrm.crm.voice.Voice.ConsentSource;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A record that a contact agreed (or refused) to be called by the AI agent and to be recorded. Rows are never
 * edited: a change of mind is a new row, and an opt-out stamps {@code revokedAt} on every earlier row.
 */
@Entity
@Table(name = "contact_consent")
public class ContactConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long contactId;
    private String phone;
    private boolean aiCallConsent;
    private boolean recordingConsent;
    @Enumerated(EnumType.STRING)
    private ConsentSource source;
    private String evidenceRef;
    private Long capturedBy;
    private Instant capturedAt;
    private Instant revokedAt;
    private String revokeNote;

    protected ContactConsent() {
    }

    public ContactConsent(Long contactId, String phone, boolean aiCallConsent, boolean recordingConsent,
                          ConsentSource source, String evidenceRef, Long capturedBy) {
        this.contactId = contactId;
        this.phone = phone;
        this.aiCallConsent = aiCallConsent;
        this.recordingConsent = recordingConsent;
        this.source = source;
        this.evidenceRef = evidenceRef == null || evidenceRef.length() <= 500 ? evidenceRef : evidenceRef.substring(0, 500);
        this.capturedBy = capturedBy;
        this.capturedAt = Instant.now();
    }

    public void revoke(String note) {
        if (revokedAt == null) {
            revokedAt = Instant.now();
            revokeNote = note;
        }
    }

    public boolean allowsAiCalls() {
        return aiCallConsent && revokedAt == null;
    }

    public boolean allowsRecording() {
        return allowsAiCalls() && recordingConsent;
    }

    public Long getId() { return id; }
    public Long getContactId() { return contactId; }
    public String getPhone() { return phone; }
    public boolean isAiCallConsent() { return aiCallConsent; }
    public boolean isRecordingConsent() { return recordingConsent; }
    public ConsentSource getSource() { return source; }
    public String getEvidenceRef() { return evidenceRef; }
    public Long getCapturedBy() { return capturedBy; }
    public Instant getCapturedAt() { return capturedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevokeNote() { return revokeNote; }
}
