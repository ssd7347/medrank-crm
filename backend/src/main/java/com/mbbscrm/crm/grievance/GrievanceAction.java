package com.mbbscrm.crm.grievance;

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

/** Append-only trail entry. There is deliberately no way to edit or delete one. */
@Entity
@Table(name = "grievance_action")
public class GrievanceAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long grievanceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private AppUser actor;

    @Enumerated(EnumType.STRING)
    private GrievanceActionType actionType;
    private String details;
    private Instant createdAt;

    protected GrievanceAction() {
    }

    public GrievanceAction(Long grievanceId, AppUser actor, GrievanceActionType type, String details) {
        this.grievanceId = grievanceId;
        this.actor = actor;
        this.actionType = type;
        this.details = details == null || details.length() <= 4000 ? details : details.substring(0, 4000);
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public AppUser getActor() { return actor; }
    public GrievanceActionType getActionType() { return actionType; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
