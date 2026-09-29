package com.mbbscrm.crm.counselling;

import java.time.Instant;

import com.mbbscrm.crm.common.CounsellingRound;

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

/**
 * One round of one authority's counselling in one year, with its deadline windows. Named *Entity to avoid
 * clashing with the {@link CounsellingRound} enum (Round 1, Round 2, Mop-up...).
 */
@Entity(name = "CounsellingRoundEntity")
@Table(name = "counselling_round")
public class CounsellingRoundEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "authority_id")
    private CounsellingAuthority authority;

    private int academicYear;
    @Enumerated(EnumType.STRING)
    private CounsellingRound roundType;
    private Instant registrationStart;
    private Instant registrationEnd;
    private Instant choiceFillingStart;
    private Instant choiceFillingEnd;
    private Instant resultAt;
    private Instant reportingStart;
    private Instant reportingEnd;
    private String notes;
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public CounsellingAuthority getAuthority() { return authority; }
    public void setAuthority(CounsellingAuthority authority) { this.authority = authority; }
    public int getAcademicYear() { return academicYear; }
    public void setAcademicYear(int academicYear) { this.academicYear = academicYear; }
    public CounsellingRound getRoundType() { return roundType; }
    public void setRoundType(CounsellingRound roundType) { this.roundType = roundType; }
    public Instant getRegistrationStart() { return registrationStart; }
    public void setRegistrationStart(Instant v) { this.registrationStart = v; }
    public Instant getRegistrationEnd() { return registrationEnd; }
    public void setRegistrationEnd(Instant v) { this.registrationEnd = v; }
    public Instant getChoiceFillingStart() { return choiceFillingStart; }
    public void setChoiceFillingStart(Instant v) { this.choiceFillingStart = v; }
    public Instant getChoiceFillingEnd() { return choiceFillingEnd; }
    public void setChoiceFillingEnd(Instant v) { this.choiceFillingEnd = v; }
    public Instant getResultAt() { return resultAt; }
    public void setResultAt(Instant v) { this.resultAt = v; }
    public Instant getReportingStart() { return reportingStart; }
    public void setReportingStart(Instant v) { this.reportingStart = v; }
    public Instant getReportingEnd() { return reportingEnd; }
    public void setReportingEnd(Instant v) { this.reportingEnd = v; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** "MCC 2026 Round 1" — used in alert texts. */
    public String label() {
        String round = switch (roundType) {
            case ROUND_1 -> "Round 1";
            case ROUND_2 -> "Round 2";
            case ROUND_3 -> "Round 3";
            case MOP_UP -> "Mop-up round";
            case STRAY_VACANCY -> "Stray vacancy round";
        };
        return authority.getCode() + " " + academicYear + " " + round;
    }
}
