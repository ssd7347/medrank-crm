package com.mbbscrm.crm.lead;

import java.time.Instant;

import com.mbbscrm.crm.user.AppUser;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A scheduled follow-up on a lead, owned by one staff member. */
@Entity
@Table(name = "follow_up")
public class FollowUp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id")
    private Lead lead;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private AppUser assignedTo;

    private Instant dueAt;
    private String purpose;
    private Instant completedAt;
    private Long completedBy;
    private Long createdBy;
    private Instant createdAt;

    protected FollowUp() {
    }

    public FollowUp(Lead lead, AppUser assignedTo, Instant dueAt, String purpose, Long createdBy) {
        this.lead = lead;
        this.assignedTo = assignedTo;
        this.dueAt = dueAt;
        this.purpose = purpose;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void complete(Long userId) {
        this.completedAt = Instant.now();
        this.completedBy = userId;
    }

    public Long getId() { return id; }
    public Lead getLead() { return lead; }
    public AppUser getAssignedTo() { return assignedTo; }
    public Instant getDueAt() { return dueAt; }
    public String getPurpose() { return purpose; }
    public Instant getCompletedAt() { return completedAt; }
}
