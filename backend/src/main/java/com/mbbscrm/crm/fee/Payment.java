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

/** A payment received, with a unique receipt number. Never deleted; mistakes are voided with a reason. */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private FeePlan plan;

    private Long installmentId;
    private String receiptNo;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    private PaymentMethod method;
    private String reference;
    private LocalDate paidOn;
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by")
    private AppUser receivedBy;
    private Instant createdAt;
    private boolean voided;
    private String voidReason;
    private Long voidedBy;

    protected Payment() {
    }

    public Payment(FeePlan plan, Long installmentId, String receiptNo, BigDecimal amount, PaymentMethod method,
                   String reference, LocalDate paidOn, String notes, AppUser receivedBy) {
        this.plan = plan;
        this.installmentId = installmentId;
        this.receiptNo = receiptNo;
        this.amount = amount;
        this.method = method;
        this.reference = reference;
        this.paidOn = paidOn;
        this.notes = notes;
        this.receivedBy = receivedBy;
        this.createdAt = Instant.now();
    }

    public void voidPayment(String reason, Long by) {
        this.voided = true;
        this.voidReason = reason;
        this.voidedBy = by;
    }

    public Long getId() { return id; }
    public FeePlan getPlan() { return plan; }
    public Long getInstallmentId() { return installmentId; }
    public String getReceiptNo() { return receiptNo; }
    public BigDecimal getAmount() { return amount; }
    public PaymentMethod getMethod() { return method; }
    public String getReference() { return reference; }
    public LocalDate getPaidOn() { return paidOn; }
    public String getNotes() { return notes; }
    public AppUser getReceivedBy() { return receivedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isVoided() { return voided; }
    public String getVoidReason() { return voidReason; }
}
