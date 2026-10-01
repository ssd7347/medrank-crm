package com.mbbscrm.crm.voice;

import java.time.Instant;

import com.mbbscrm.crm.voice.Voice.ConsentSource;
import com.mbbscrm.crm.voice.Voice.PersonType;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A record that a family agreed (or refused) to be called by the AI agent and to be recorded
 * (spec 18.14.2). Rows are never edited: a change of mind is a new row, and an opt-out stamps
 * {@code revokedAt} on every earlier row for that number.
 */
@Entity
@Table(name = "contact_consent")
public class ContactConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private PersonType personType;
    private Long personId;
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

    public ContactConsent(PersonType personType, Long personId, String phone, boolean aiCallConsent,
                          boolean recordingConsent, ConsentSource source, String evidenceRef, Long capturedBy) {
        this.personType = personType;
        this.personId = personId;
        this.phone = phone;
        this.aiCallConsent = aiCallConsent;
        this.recordingConsent = recordingConsent;
        this.source = source;
        this.evidenceRef = evidenceRef;
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
    public PersonType getPersonType() { return personType; }
    public Long getPersonId() { return personId; }
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
