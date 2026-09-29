package com.mbbscrm.crm.fee;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.mbbscrm.crm.lead.Lead;
import com.mbbscrm.crm.referral.ReferralAssociate;
import com.mbbscrm.crm.student.Student;

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

/** Commission owed to a referral associate for a confirmed admission (spec 4.8, 4.22). */
@Entity
@Table(name = "commission_entry")
public class CommissionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "associate_id")
    private ReferralAssociate associate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id")
    private Lead lead;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    private BigDecimal basisAmount;
    private BigDecimal rate;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    private CommissionStatus status = CommissionStatus.PENDING;
    private Instant createdAt;
    private LocalDate paidOn;
    private String paidReference;

    protected CommissionEntry() {
    }

    public CommissionEntry(ReferralAssociate associate, Lead lead, Student student, BigDecimal basis, BigDecimal rate,
                           BigDecimal amount) {
        this.associate = associate;
        this.lead = lead;
        this.student = student;
        this.basisAmount = basis;
        this.rate = rate;
        this.amount = amount;
        this.createdAt = Instant.now();
    }

    public void setStatus(CommissionStatus status) { this.status = status; }

    public void markPaid(LocalDate on, String reference) {
        this.status = CommissionStatus.PAID;
        this.paidOn = on;
        this.paidReference = reference;
    }

    public Long getId() { return id; }
    public ReferralAssociate getAssociate() { return associate; }
    public Lead getLead() { return lead; }
    public Student getStudent() { return student; }
    public BigDecimal getBasisAmount() { return basisAmount; }
    public BigDecimal getRate() { return rate; }
    public BigDecimal getAmount() { return amount; }
    public CommissionStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public LocalDate getPaidOn() { return paidOn; }
    public String getPaidReference() { return paidReference; }
}
