package com.mbbscrm.crm.grievance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.mbbscrm.crm.helpdesk.Channel;
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
import jakarta.persistence.Table;

/** A formal complaint with legal or financial exposure (spec 4.28). Its history lives in GrievanceAction. */
@Entity
@Table(name = "grievance")
public class Grievance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String referenceNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    private Long ticketId;
    private String complainantName;
    private String complainantPhone;
    @Enumerated(EnumType.STRING)
    private GrievanceCategory category;
    private String description;
    private BigDecimal amountInDispute;
    @Enumerated(EnumType.STRING)
    private Channel receivedVia;
    private Instant receivedAt;
    @Enumerated(EnumType.STRING)
    private GrievanceStatus status = GrievanceStatus.OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_officer_id")
    private AppUser assignedOfficer;

    private LocalDate targetResolutionDate;
    private String resolution;
    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;
    private Instant createdAt;

    public boolean isOpen() {
        return status != GrievanceStatus.RESOLVED && status != GrievanceStatus.CLOSED;
    }

    public Long getId() { return id; }
    public String getReferenceNo() { return referenceNo; }
    public void setReferenceNo(String referenceNo) { this.referenceNo = referenceNo; }
    public Student getStudent() { return student; }
    public void setStudent(Student student) { this.student = student; }
    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public String getComplainantName() { return complainantName; }
    public void setComplainantName(String v) { this.complainantName = v; }
    public String getComplainantPhone() { return complainantPhone; }
    public void setComplainantPhone(String v) { this.complainantPhone = v; }
    public GrievanceCategory getCategory() { return category; }
    public void setCategory(GrievanceCategory category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmountInDispute() { return amountInDispute; }
    public void setAmountInDispute(BigDecimal v) { this.amountInDispute = v; }
    public Channel getReceivedVia() { return receivedVia; }
    public void setReceivedVia(Channel receivedVia) { this.receivedVia = receivedVia; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public GrievanceStatus getStatus() { return status; }
    public void setStatus(GrievanceStatus status) { this.status = status; }
    public AppUser getAssignedOfficer() { return assignedOfficer; }
    public void setAssignedOfficer(AppUser v) { this.assignedOfficer = v; }
    public LocalDate getTargetResolutionDate() { return targetResolutionDate; }
    public void setTargetResolutionDate(LocalDate v) { this.targetResolutionDate = v; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public AppUser getCreatedBy() { return createdBy; }
    public void setCreatedBy(AppUser createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
