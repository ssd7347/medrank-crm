package com.mbbscrm.crm.lead;

import java.time.Instant;

import com.mbbscrm.crm.common.ActivityType;
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

/** One entry in a lead's timeline: a call, a note, a status change, and so on. */
@Entity
@Table(name = "lead_activity")
public class LeadActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long leadId;

    @Enumerated(EnumType.STRING)
    private ActivityType type;

    private String outcome;
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;

    private Instant createdAt;

    protected LeadActivity() {
    }

    public LeadActivity(Long leadId, ActivityType type, String outcome, String notes, AppUser createdBy) {
        this.leadId = leadId;
        this.type = type;
        this.outcome = outcome;
        this.notes = notes;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getLeadId() { return leadId; }
    public ActivityType getType() { return type; }
    public String getOutcome() { return outcome; }
    public String getNotes() { return notes; }
    public AppUser getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
