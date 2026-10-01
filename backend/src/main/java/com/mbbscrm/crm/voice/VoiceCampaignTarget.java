package com.mbbscrm.crm.voice;

import java.time.Instant;
import java.util.UUID;

import com.mbbscrm.crm.voice.Voice.PersonType;
import com.mbbscrm.crm.voice.Voice.SkipReason;
import com.mbbscrm.crm.voice.Voice.TargetStatus;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** One phone number a campaign intends to call, and how that is going. */
@Entity
@Table(name = "voice_campaign_target")
public class VoiceCampaignTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long campaignId;
    private Long studentId;
    private Long leadId;
    private String phone;
    @Enumerated(EnumType.STRING)
    private PersonType recipient;
    @Enumerated(EnumType.STRING)
    private TargetStatus status = TargetStatus.PENDING;
    @Enumerated(EnumType.STRING)
    private SkipReason skipReason;
    private int attempts;
    private Instant nextAttemptAt;
    private UUID lastCallId;
    private Instant updatedAt;

    protected VoiceCampaignTarget() {
    }

    public VoiceCampaignTarget(Long campaignId, Long studentId, Long leadId, String phone, PersonType recipient) {
        this.campaignId = campaignId;
        this.studentId = studentId;
        this.leadId = leadId;
        this.phone = phone;
        this.recipient = recipient;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void skip(SkipReason reason) {
        this.status = TargetStatus.SKIPPED;
        this.skipReason = reason;
        this.nextAttemptAt = null;
    }

    public void finish(TargetStatus status) {
        this.status = status;
        this.nextAttemptAt = null;
    }

    /** A connected call or a genuine no-answer counts; a platform failure does not (spec 18.10.2). */
    public void countAttempt() {
        attempts++;
    }

    public Long getId() { return id; }
    public Long getCampaignId() { return campaignId; }
    public Long getStudentId() { return studentId; }
    public Long getLeadId() { return leadId; }
    public String getPhone() { return phone; }
    public PersonType getRecipient() { return recipient; }
    public TargetStatus getStatus() { return status; }
    public SkipReason getSkipReason() { return skipReason; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public UUID getLastCallId() { return lastCallId; }
    public void setLastCallId(UUID lastCallId) { this.lastCallId = lastCallId; }
    public Instant getUpdatedAt() { return updatedAt; }
}
