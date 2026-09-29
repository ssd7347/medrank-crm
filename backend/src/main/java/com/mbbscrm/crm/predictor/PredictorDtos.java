package com.mbbscrm.crm.predictor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.CollegeType;
import com.mbbscrm.crm.common.CounsellingRound;
import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.Quota;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class PredictorDtos {

    private PredictorDtos() {
    }

    public enum Band { HIGH, MODERATE, LOW }

    /**
     * Either give a {@code studentId} (category, PwD, home state, domicile and eligible quotas come from the
     * profile) or fill the fields by hand, e.g. for a lead on the phone.
     */
    public record PredictRequest(
            Long studentId,
            @Min(1) @Max(5_000_000) Integer rank,
            Category category,
            Boolean pwd,
            @Size(max = 40) String homeState,
            Boolean domiciled,
            Course course,
            Set<Quota> quotas,
            Set<String> states,
            Set<CollegeType> collegeTypes,
            @DecimalMin("0") BigDecimal maxAnnualFee) {
    }

    public record ClosingRank(int year, CounsellingRound round, int closingRank) {
    }

    public record Prediction(Long collegeId, String collegeName, String collegeCode, String state, String city,
                             CollegeType collegeType, Quota quota, Course course, Band band, int latestYear,
                             int lastClosingRank, List<ClosingRank> history, BigDecimal annualTuition,
                             Integer feeYear) {
    }

    public record PredictResponse(int rank, Category category, boolean pwd, Course course, Set<Quota> quotas,
                                  List<Integer> dataYears, List<Prediction> results, boolean truncated,
                                  String disclaimer) {
    }

    public record ShortlistRequest(@NotNull Long collegeId, @NotNull Course course, @NotNull Quota quota, Band band,
                                   @Size(max = 300) String note) {
    }

    public record ShortlistItem(Long id, Long collegeId, String collegeName, String state, CollegeType collegeType,
                                Course course, Quota quota, Band band, String note, Instant createdAt) {
    }
}
