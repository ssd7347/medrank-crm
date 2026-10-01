package com.mbbscrm.crm.voice;

import java.time.Instant;
import java.time.LocalTime;

import com.mbbscrm.crm.voice.Voice.CampaignStatus;
import com.mbbscrm.crm.voice.Voice.Purpose;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A batch of outbound calls for one purpose, with its calling window and retry rules (spec 18.10). */
@Entity
@Table(name = "voice_campaign")
public class VoiceCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    @Enumerated(EnumType.STRING)
    private Purpose purpose;
    @Enumerated(EnumType.STRING)
    private CampaignStatus status = CampaignStatus.DRAFT;
    private LocalTime windowStart;
    private LocalTime windowEnd;
    private int maxConcurrent;
    private int maxAttempts;
    private int retryGapMin;
    private int lookaheadDays;
    private Long branchId;
    private Long createdBy;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    protected VoiceCampaign() {
    }

    public VoiceCampaign(Purpose purpose, Long createdBy) {
        this.purpose = purpose;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Purpose getPurpose() { return purpose; }
    public CampaignStatus getStatus() { return status; }
    public void setStatus(CampaignStatus status) { this.status = status; }
    public LocalTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalTime windowStart) { this.windowStart = windowStart; }
    public LocalTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalTime windowEnd) { this.windowEnd = windowEnd; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getRetryGapMin() { return retryGapMin; }
    public void setRetryGapMin(int retryGapMin) { this.retryGapMin = retryGapMin; }
    public int getLookaheadDays() { return lookaheadDays; }
    public void setLookaheadDays(int lookaheadDays) { this.lookaheadDays = lookaheadDays; }
    public Long getBranchId() { return branchId; }
    public void setBranchId(Long branchId) { this.branchId = branchId; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
