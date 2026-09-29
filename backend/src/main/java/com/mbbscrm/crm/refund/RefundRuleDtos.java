package com.mbbscrm.crm.refund;

import java.math.BigDecimal;
import java.time.Instant;

import com.mbbscrm.crm.common.CounsellingRound;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class RefundRuleDtos {

    private RefundRuleDtos() {
    }

    /** Change-request payload. At least an authority or a college must be given. */
    public record RefundRulePayload(
            Long authorityId, Long collegeId, CounsellingRound roundType,
            @NotNull @Min(2013) @Max(2100) Integer academicYear,
            @Min(0) @Max(3650) Integer maxDaysAfterAllotment,
            boolean depositForfeited,
            @DecimalMin("0") @DecimalMax("100000000") BigDecimal depositAmount,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal tuitionRefundPercent,
            boolean barredFromLaterRounds,
            @NotBlank @Size(max = 300) String source,
            @Size(max = 1000) String notes) {
    }

    public record RefundRuleView(Long id, Long authorityId, Long collegeId, CounsellingRound roundType, int academicYear,
                                 Integer maxDaysAfterAllotment, boolean depositForfeited, BigDecimal depositAmount,
                                 BigDecimal tuitionRefundPercent, boolean barredFromLaterRounds, String source,
                                 String notes, Instant updatedAt) {
        public static RefundRuleView of(RefundRule r) {
            return new RefundRuleView(r.getId(), r.getAuthorityId(), r.getCollegeId(), r.getRoundType(),
                    r.getAcademicYear(), r.getMaxDaysAfterAllotment(), r.isDepositForfeited(), r.getDepositAmount(),
                    r.getTuitionRefundPercent(), r.isBarredFromLaterRounds(), r.getSource(), r.getNotes(),
                    r.getUpdatedAt());
        }
    }

    public static void fill(RefundRule r, RefundRulePayload p) {
        r.setAuthorityId(p.authorityId());
        r.setCollegeId(p.collegeId());
        r.setRoundType(p.roundType());
        r.setAcademicYear(p.academicYear());
        r.setMaxDaysAfterAllotment(p.maxDaysAfterAllotment());
        r.setDepositForfeited(p.depositForfeited());
        r.setDepositAmount(p.depositAmount());
        r.setTuitionRefundPercent(p.tuitionRefundPercent());
        r.setBarredFromLaterRounds(p.barredFromLaterRounds());
        r.setSource(p.source().trim());
        r.setNotes(p.notes() == null || p.notes().isBlank() ? null : p.notes().trim());
    }
}
