package com.mbbscrm.crm.loan;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** An education-loan lender the firm refers students to (spec 4.26). Rates are reference-only. */
@Entity
@Table(name = "loan_partner")
public class LoanPartner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String interestInfo;
    private BigDecimal maxAmount;
    private String eligibility;
    private String contactName;
    private String contactPhone;
    private boolean active = true;
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getInterestInfo() { return interestInfo; }
    public void setInterestInfo(String interestInfo) { this.interestInfo = interestInfo; }
    public BigDecimal getMaxAmount() { return maxAmount; }
    public void setMaxAmount(BigDecimal maxAmount) { this.maxAmount = maxAmount; }
    public String getEligibility() { return eligibility; }
    public void setEligibility(String eligibility) { this.eligibility = eligibility; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
