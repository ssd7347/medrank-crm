package com.mbbscrm.crm.approval;

import java.time.Instant;

import com.mbbscrm.crm.user.AppUser;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A proposed change to college / seat-matrix / fee / cutoff data. Master-data tables only ever hold approved
 * data; this table is the version history and audit trail for them (spec 4.3, section 5).
 */
@Entity
@Table(name = "data_change_request")
public class DataChangeRequest {

    public enum EntityType { COLLEGE, SEAT_MATRIX, FEE, CUTOFF }

    public enum Action { CREATE, UPDATE, DELETE, BULK_UPSERT }

    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private EntityType entityType;
    private Long entityId;
    @Enumerated(EnumType.STRING)
    private Action action;
    private String summary;
    private String payload;
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    private AppUser requestedBy;
    private Instant requestedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private AppUser reviewedBy;
    private Instant reviewedAt;
    private String reviewNote;

    protected DataChangeRequest() {
    }

    public DataChangeRequest(EntityType entityType, Long entityId, Action action, String summary, String payload,
                             AppUser requestedBy) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.summary = summary;
        this.payload = payload;
        this.requestedBy = requestedBy;
        this.requestedAt = Instant.now();
    }

    void review(Status outcome, AppUser reviewer, String note) {
        this.status = outcome;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
        this.reviewNote = note;
    }

    void setEntityId(Long entityId) { this.entityId = entityId; }

    public Long getId() { return id; }
    public EntityType getEntityType() { return entityType; }
    public Long getEntityId() { return entityId; }
    public Action getAction() { return action; }
    public String getSummary() { return summary; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
    public AppUser getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public AppUser getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getReviewNote() { return reviewNote; }
}
