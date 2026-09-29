package com.mbbscrm.crm.refund;

import java.math.BigDecimal;
import java.time.Instant;

import com.mbbscrm.crm.common.CounsellingRound;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * What a student loses by giving up an allotted seat (spec 4.27), taken from the authority's or college's
 * published notification. Changed only through an approved change request.
 */
@Entity
@Table(name = "refund_rule")
public class RefundRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long authorityId;
    private Long collegeId;
    @Enumerated(EnumType.STRING)
    private CounsellingRound roundType;
    private int academicYear;
    /** Tier upper bound in days since allotment; null means "any time". */
    private Integer maxDaysAfterAllotment;
    private boolean depositForfeited;
    private BigDecimal depositAmount;
    private BigDecimal tuitionRefundPercent;
    private boolean barredFromLaterRounds;
    private String source;
    private String notes;
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getAuthorityId() { return authorityId; }
    public void setAuthorityId(Long authorityId) { this.authorityId = authorityId; }
    public Long getCollegeId() { return collegeId; }
    public void setCollegeId(Long collegeId) { this.collegeId = collegeId; }
    public CounsellingRound getRoundType() { return roundType; }
    public void setRoundType(CounsellingRound roundType) { this.roundType = roundType; }
    public int getAcademicYear() { return academicYear; }
    public void setAcademicYear(int academicYear) { this.academicYear = academicYear; }
    public Integer getMaxDaysAfterAllotment() { return maxDaysAfterAllotment; }
    public void setMaxDaysAfterAllotment(Integer v) { this.maxDaysAfterAllotment = v; }
    public boolean isDepositForfeited() { return depositForfeited; }
    public void setDepositForfeited(boolean depositForfeited) { this.depositForfeited = depositForfeited; }
    public BigDecimal getDepositAmount() { return depositAmount; }
    public void setDepositAmount(BigDecimal depositAmount) { this.depositAmount = depositAmount; }
    public BigDecimal getTuitionRefundPercent() { return tuitionRefundPercent; }
    public void setTuitionRefundPercent(BigDecimal v) { this.tuitionRefundPercent = v; }
    public boolean isBarredFromLaterRounds() { return barredFromLaterRounds; }
    public void setBarredFromLaterRounds(boolean v) { this.barredFromLaterRounds = v; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getUpdatedAt() { return updatedAt; }
}
