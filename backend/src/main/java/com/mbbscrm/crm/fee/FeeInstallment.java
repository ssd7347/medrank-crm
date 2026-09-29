package com.mbbscrm.crm.fee;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "fee_installment")
public class FeeInstallment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private FeePlan plan;

    private int seq;
    private String label;
    private BigDecimal amount;
    private LocalDate dueDate;

    protected FeeInstallment() {
    }

    public FeeInstallment(FeePlan plan, int seq, String label, BigDecimal amount, LocalDate dueDate) {
        this.plan = plan;
        this.seq = seq;
        this.label = label;
        this.amount = amount;
        this.dueDate = dueDate;
    }

    public Long getId() { return id; }
    public FeePlan getPlan() { return plan; }
    public int getSeq() { return seq; }
    public String getLabel() { return label; }
    public BigDecimal getAmount() { return amount; }
    public LocalDate getDueDate() { return dueDate; }
}
