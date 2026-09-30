package com.mbbscrm.crm.referral;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.mbbscrm.crm.branch.Branch;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * A district-level associate (sub-agent) who refers students: territory, agreement terms and commission rate
 * (spec 4.1, 4.11, 4.22).
 */
@Entity
@Table(name = "referral_associate")
public class ReferralAssociate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fullName;
    private String phone;
    private String email;
    private String district;
    private String state;
    /** Free text: the towns, schools or coaching centres this associate covers. */
    private String territory;
    private BigDecimal commissionRate = BigDecimal.ZERO;
    private LocalDate agreementStart;
    private LocalDate agreementEnd;
    private String agreementTerms;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    private boolean active = true;
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getTerritory() { return territory; }
    public void setTerritory(String territory) { this.territory = territory; }
    public BigDecimal getCommissionRate() { return commissionRate; }
    public void setCommissionRate(BigDecimal commissionRate) { this.commissionRate = commissionRate; }
    public LocalDate getAgreementStart() { return agreementStart; }
    public void setAgreementStart(LocalDate agreementStart) { this.agreementStart = agreementStart; }
    public LocalDate getAgreementEnd() { return agreementEnd; }
    public void setAgreementEnd(LocalDate agreementEnd) { this.agreementEnd = agreementEnd; }
    public String getAgreementTerms() { return agreementTerms; }
    public void setAgreementTerms(String agreementTerms) { this.agreementTerms = agreementTerms; }
    public Branch getBranch() { return branch; }
    public void setBranch(Branch branch) { this.branch = branch; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
