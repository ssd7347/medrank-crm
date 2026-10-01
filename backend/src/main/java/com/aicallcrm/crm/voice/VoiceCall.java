package com.aicallcrm.crm.voice;

import java.time.Instant;
import java.util.UUID;

import com.aicallcrm.crm.voice.Voice.CallStatus;
import com.aicallcrm.crm.voice.Voice.Direction;
import com.aicallcrm.crm.voice.Voice.HandoffReason;
import com.aicallcrm.crm.voice.Voice.Outcome;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * One AI call. The id is issued here and is the only thing the voice platform quotes back, so
 * the contact is always resolved on the server. {@code verificationLevel} can only be raised by the
 * verify_identity tool.
 */
@Entity
@Table(name = "voice_call")
public class VoiceCall implements Persistable<UUID> {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    private Direction direction;
    private String scriptName;
    private Long contactId;
    private String phone;
    private Long campaignId;
    private Long scriptId;
    private String provider;
    private String providerCallId;
    @Enumerated(EnumType.STRING)
    private CallStatus status;
    private boolean testCall;
    private boolean recordingAllowed;
    private int verificationLevel;
    private int verifyFailures;
    @Enumerated(EnumType.STRING)
    private Outcome outcome;
    private String outcomeNote;
    @Enumerated(EnumType.STRING)
    private HandoffReason handoffReason;
    private Instant startedAt;
    private Instant endedAt;
    private Integer durationSec;
    private String disconnectReason;
    private String recordingRef;
    private String transcript;
    private String summary;
    private boolean flaggedWrong;
    private String flagNote;
    private Long flaggedBy;
    private Instant createdAt;

    /** The id is assigned here, so Spring Data must be told whether the row exists yet (insert, not merge). */
    @Transient
    private boolean fresh = true;

    @PostLoad
    @PostPersist
    void stored() {
        fresh = false;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    protected VoiceCall() {
    }

    public VoiceCall(Direction direction, String scriptName, Long contactId, String phone, String provider) {
        this.id = UUID.randomUUID();
        this.direction = direction;
        this.scriptName = scriptName;
        this.contactId = contactId;
        this.phone = phone;
        this.provider = provider;
        this.status = CallStatus.QUEUED;
        this.createdAt = Instant.now();
    }

    public void verified() {
        this.verificationLevel = 1;
    }

    public int verificationFailed() {
        return ++verifyFailures;
    }

    public void flag(Long userId, String note) {
        this.flaggedWrong = true;
        this.flaggedBy = userId;
        this.flagNote = note;
    }

    /** Drops the conversation itself once it is older than the retention period; the outcome stays. */
    public void purgeContent() {
        this.transcript = null;
        this.recordingRef = null;
    }

    public UUID getId() { return id; }
    public Direction getDirection() { return direction; }
    public String getScriptName() { return scriptName; }
    public Long getContactId() { return contactId; }
    public void setContactId(Long contactId) { this.contactId = contactId; }
    public String getPhone() { return phone; }
    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }
    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }
    public String getProvider() { return provider; }
    public String getProviderCallId() { return providerCallId; }
    public void setProviderCallId(String providerCallId) { this.providerCallId = providerCallId; }
    public CallStatus getStatus() { return status; }
    public void setStatus(CallStatus status) { this.status = status; }
    public boolean isTestCall() { return testCall; }
    public void setTestCall(boolean testCall) { this.testCall = testCall; }
    public boolean isRecordingAllowed() { return recordingAllowed; }
    public void setRecordingAllowed(boolean recordingAllowed) { this.recordingAllowed = recordingAllowed; }
    public int getVerificationLevel() { return verificationLevel; }
    public int getVerifyFailures() { return verifyFailures; }
    public Outcome getOutcome() { return outcome; }
    public void setOutcome(Outcome outcome) { this.outcome = outcome; }
    public String getOutcomeNote() { return outcomeNote; }
    public void setOutcomeNote(String outcomeNote) { this.outcomeNote = outcomeNote; }
    public HandoffReason getHandoffReason() { return handoffReason; }
    public void setHandoffReason(HandoffReason handoffReason) { this.handoffReason = handoffReason; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public Integer getDurationSec() { return durationSec; }
    public void setDurationSec(Integer durationSec) { this.durationSec = durationSec; }
    public String getDisconnectReason() { return disconnectReason; }
    public void setDisconnectReason(String disconnectReason) { this.disconnectReason = disconnectReason; }
    public String getRecordingRef() { return recordingRef; }
    public void setRecordingRef(String recordingRef) { this.recordingRef = recordingRef; }
    public String getTranscript() { return transcript; }
    public void setTranscript(String transcript) { this.transcript = transcript; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public boolean isFlaggedWrong() { return flaggedWrong; }
    public String getFlagNote() { return flagNote; }
    public Instant getCreatedAt() { return createdAt; }
}
