package com.mbbscrm.crm.fee;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

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

/** A refund of consultancy fees: requested, approved/rejected by an admin, then paid out by accounts. */
@Entity
@Table(name = "fee_refund")
public class FeeRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private FeePlan plan;

    private BigDecimal amount;
    private String reason;
    @Enumerated(EnumType.STRING)
    private RefundStatus status = RefundStatus.REQUESTED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    private AppUser requestedBy;
    private Instant requestedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private AppUser decidedBy;
    private Instant decidedAt;
    private String decisionNote;
    private LocalDate paidOn;
    private String paidReference;

    protected FeeRefund() {
    }

    public FeeRefund(FeePlan plan, BigDecimal amount, String reason, AppUser requestedBy) {
        this.plan = plan;
        this.amount = amount;
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.requestedAt = Instant.now();
    }

    public void decide(boolean approve, AppUser by, String note) {
        this.status = approve ? RefundStatus.APPROVED : RefundStatus.REJECTED;
        this.decidedBy = by;
        this.decidedAt = Instant.now();
        this.decisionNote = note;
    }

    public void markPaid(LocalDate on, String reference) {
        this.status = RefundStatus.PAID;
        this.paidOn = on;
        this.paidReference = reference;
    }

    public Long getId() { return id; }
    public FeePlan getPlan() { return plan; }
    public BigDecimal getAmount() { return amount; }
    public String getReason() { return reason; }
    public RefundStatus getStatus() { return status; }
    public AppUser getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public AppUser getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
    public LocalDate getPaidOn() { return paidOn; }
    public String getPaidReference() { return paidReference; }
}
