package com.aicallcrm.crm.voice;

import java.time.Instant;
import java.time.LocalTime;

import com.aicallcrm.crm.voice.Voice.CampaignStatus;
import com.aicallcrm.crm.voice.Voice.FieldOp;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A batch of outbound calls using one script, to the contacts that match its filter, within calling hours and
 * with retry rules. The filter is checked again just before each call, so a contact who has since paid (and
 * no longer matches "amount_due is filled in") is not called.
 */
@Entity
@Table(name = "voice_campaign")
public class VoiceCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String scriptName;
    @Enumerated(EnumType.STRING)
    private CampaignStatus status = CampaignStatus.DRAFT;
    /** Comma-separated contact statuses; empty means any status except do-not-call. */
    private String filterStatuses;
    private Long filterImportId;
    private String filterFieldKey;
    @Enumerated(EnumType.STRING)
    private FieldOp filterFieldOp;
    private String filterFieldValue;
    private LocalTime windowStart;
    private LocalTime windowEnd;
    private int maxConcurrent;
    private int maxAttempts;
    private int retryGapMin;
    private Long branchId;
    private Long createdBy;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    protected VoiceCampaign() {
    }

    public VoiceCampaign(Long createdBy) {
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getScriptName() { return scriptName; }
    public void setScriptName(String scriptName) { this.scriptName = scriptName; }
    public CampaignStatus getStatus() { return status; }
    public void setStatus(CampaignStatus status) { this.status = status; }
    public String getFilterStatuses() { return filterStatuses; }
    public void setFilterStatuses(String filterStatuses) { this.filterStatuses = filterStatuses; }
    public Long getFilterImportId() { return filterImportId; }
    public void setFilterImportId(Long filterImportId) { this.filterImportId = filterImportId; }
    public String getFilterFieldKey() { return filterFieldKey; }
    public void setFilterFieldKey(String filterFieldKey) { this.filterFieldKey = filterFieldKey; }
    public FieldOp getFilterFieldOp() { return filterFieldOp; }
    public void setFilterFieldOp(FieldOp filterFieldOp) { this.filterFieldOp = filterFieldOp; }
    public String getFilterFieldValue() { return filterFieldValue; }
    public void setFilterFieldValue(String filterFieldValue) { this.filterFieldValue = filterFieldValue; }
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
    public Long getBranchId() { return branchId; }
    public void setBranchId(Long branchId) { this.branchId = branchId; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
