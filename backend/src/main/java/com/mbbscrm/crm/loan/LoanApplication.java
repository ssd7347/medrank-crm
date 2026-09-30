package com.mbbscrm.crm.loan;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

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

/** One student's loan application with one lender, tracked against the date the money is needed by. */
@Entity
@Table(name = "loan_application")
public class LoanApplication {

    public enum Status {
        DRAFT, SUBMITTED, DOCS_PENDING, SANCTIONED, DISBURSED, REJECTED, WITHDRAWN;

        /** Still waiting on the lender, so a nearing deadline is a risk. */
        public boolean inProgress() {
            return this == DRAFT || this == SUBMITTED || this == DOCS_PENDING;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_id")
    private LoanPartner partner;

    private BigDecimal amountRequested;
    private BigDecimal amountSanctioned;
    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT;
    private LocalDate neededBy;
    private LocalDate appliedOn;
    private LocalDate decidedOn;
    private String referenceNo;
    private String coApplicant;
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handled_by")
    private AppUser handledBy;

    private Long createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Student getStudent() { return student; }
    public void setStudent(Student student) { this.student = student; }
    public LoanPartner getPartner() { return partner; }
    public void setPartner(LoanPartner partner) { this.partner = partner; }
    public BigDecimal getAmountRequested() { return amountRequested; }
    public void setAmountRequested(BigDecimal amountRequested) { this.amountRequested = amountRequested; }
    public BigDecimal getAmountSanctioned() { return amountSanctioned; }
    public void setAmountSanctioned(BigDecimal amountSanctioned) { this.amountSanctioned = amountSanctioned; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public LocalDate getNeededBy() { return neededBy; }
    public void setNeededBy(LocalDate neededBy) { this.neededBy = neededBy; }
    public LocalDate getAppliedOn() { return appliedOn; }
    public void setAppliedOn(LocalDate appliedOn) { this.appliedOn = appliedOn; }
    public LocalDate getDecidedOn() { return decidedOn; }
    public void setDecidedOn(LocalDate decidedOn) { this.decidedOn = decidedOn; }
    public String getReferenceNo() { return referenceNo; }
    public void setReferenceNo(String referenceNo) { this.referenceNo = referenceNo; }
    public String getCoApplicant() { return coApplicant; }
    public void setCoApplicant(String coApplicant) { this.coApplicant = coApplicant; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public AppUser getHandledBy() { return handledBy; }
    public void setHandledBy(AppUser handledBy) { this.handledBy = handledBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
