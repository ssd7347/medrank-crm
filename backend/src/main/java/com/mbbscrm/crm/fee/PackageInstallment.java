package com.mbbscrm.crm.fee;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "package_installment")
public class PackageInstallment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_id")
    private ServicePackage servicePackage;

    private int seq;
    private String label;
    private BigDecimal amount;
    private int dueOffsetDays;

    protected PackageInstallment() {
    }

    public PackageInstallment(ServicePackage p, int seq, String label, BigDecimal amount, int dueOffsetDays) {
        this.servicePackage = p;
        this.seq = seq;
        this.label = label;
        this.amount = amount;
        this.dueOffsetDays = dueOffsetDays;
    }

    public int getSeq() { return seq; }
    public String getLabel() { return label; }
    public BigDecimal getAmount() { return amount; }
    public int getDueOffsetDays() { return dueOffsetDays; }
}
