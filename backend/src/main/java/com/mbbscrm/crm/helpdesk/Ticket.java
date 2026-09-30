package com.mbbscrm.crm.helpdesk;

import java.time.Duration;
import java.time.Instant;

import com.mbbscrm.crm.lead.Lead;
import com.mbbscrm.crm.student.Student;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** A routine query or complaint (spec 4.14). Serious disputes go to the grievance register instead. */
@Entity
@Table(name = "ticket")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id")
    private Lead lead;

    private String raisedByName;
    @Enumerated(EnumType.STRING)
    private Channel raisedVia;
    private String subject;
    private String description;
    @Enumerated(EnumType.STRING)
    private TicketCategory category;
    @Enumerated(EnumType.STRING)
    private TicketPriority priority;
    private boolean deadlineLinked;
    @Enumerated(EnumType.STRING)
    private TicketStatus status = TicketStatus.OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private AppUser assignedTo;

    private Instant dueAt;
    private String resolution;
    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;
    private Instant createdAt;
    private Instant updatedAt;
    /** Set when the family raised the ticket themselves through the portal. */
    private Long portalAccountId;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Response deadline: urgent 4 h, high 24 h, normal 48 h, low 72 h. Anything touching an open counselling
     * deadline gets at most 4 h (spec 4.14: tighter SLA).
     */
    public static Duration slaFor(TicketPriority priority, boolean deadlineLinked) {
        Duration base = switch (priority) {
            case URGENT -> Duration.ofHours(4);
            case HIGH -> Duration.ofHours(24);
            case NORMAL -> Duration.ofHours(48);
            case LOW -> Duration.ofHours(72);
        };
        return deadlineLinked && base.compareTo(Duration.ofHours(4)) > 0 ? Duration.ofHours(4) : base;
    }

    public boolean isOpen() {
        return status != TicketStatus.RESOLVED && status != TicketStatus.CLOSED;
    }

    public Long getId() { return id; }
    public Student getStudent() { return student; }
    public void setStudent(Student student) { this.student = student; }
    public Long getPortalAccountId() { return portalAccountId; }
    public void setPortalAccountId(Long portalAccountId) { this.portalAccountId = portalAccountId; }
    public Lead getLead() { return lead; }
    public void setLead(Lead lead) { this.lead = lead; }
    public String getRaisedByName() { return raisedByName; }
    public void setRaisedByName(String raisedByName) { this.raisedByName = raisedByName; }
    public Channel getRaisedVia() { return raisedVia; }
    public void setRaisedVia(Channel raisedVia) { this.raisedVia = raisedVia; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public TicketCategory getCategory() { return category; }
    public void setCategory(TicketCategory category) { this.category = category; }
    public TicketPriority getPriority() { return priority; }
    public void setPriority(TicketPriority priority) { this.priority = priority; }
    public boolean isDeadlineLinked() { return deadlineLinked; }
    public void setDeadlineLinked(boolean deadlineLinked) { this.deadlineLinked = deadlineLinked; }
    public TicketStatus getStatus() { return status; }
    public void setStatus(TicketStatus status) { this.status = status; }
    public AppUser getAssignedTo() { return assignedTo; }
    public void setAssignedTo(AppUser assignedTo) { this.assignedTo = assignedTo; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public AppUser getCreatedBy() { return createdBy; }
    public void setCreatedBy(AppUser createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
