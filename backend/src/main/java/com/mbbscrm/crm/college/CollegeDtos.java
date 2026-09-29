package com.mbbscrm.crm.college;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.CollegeType;
import com.mbbscrm.crm.common.CounsellingRound;
import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.Quota;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payloads carried by change requests (validated at submit time, applied on approval) and read-side responses.
 */
public final class CollegeDtos {

    private CollegeDtos() {
    }

    // ---- change-request payloads ----

    public record CollegePayload(
            @NotBlank @Size(max = 200) String name,
            @Pattern(regexp = "^[A-Za-z0-9-]{0,30}$", message = "letters, digits and '-' only") String code,
            @NotNull CollegeType collegeType,
            @NotBlank @Size(max = 40) String state,
            @Size(max = 80) String city,
            @Size(max = 200) String affiliatedUniversity,
            boolean nmcRecognized,
            @Min(1800) @Max(2100) Integer establishedYear,
            @Size(max = 200) @Pattern(regexp = "^(https?://\\S+)?$", message = "must start with http:// or https://")
            String website) {
    }

    public record SeatMatrixPayload(
            @NotNull Long collegeId, @NotNull Course course, @NotNull Quota quota, @NotNull Category category,
            boolean pwd, @NotNull CounsellingRound counsellingRound,
            @NotNull @Min(2013) @Max(2100) Integer academicYear,
            @NotNull @Min(0) @Max(5000) Integer seats) {
    }

    public record FeePayload(
            @NotNull Long collegeId, @NotNull Course course, @NotNull Quota quota,
            @NotNull @Min(2013) @Max(2100) Integer academicYear,
            @NotNull @DecimalMin("0") @DecimalMax("100000000") BigDecimal annualTuition,
            @DecimalMin("0") @DecimalMax("100000000") BigDecimal otherFees,
            @Size(max = 500) String notes) {
    }

    public record CutoffPayload(
            @NotNull Long collegeId, @NotNull Course course, @NotNull Quota quota, @NotNull Category category,
            boolean pwd, @NotNull CounsellingRound counsellingRound,
            @NotNull @Min(2013) @Max(2100) Integer academicYear,
            @NotNull @Min(1) @Max(5_000_000) Integer closingRank) {
    }

    public record BulkSeatMatrixPayload(@NotEmpty @Size(max = 5000) List<@Valid SeatMatrixPayload> rows) {
    }

    public record BulkCutoffPayload(@NotEmpty @Size(max = 5000) List<@Valid CutoffPayload> rows) {
    }

    // ---- read side ----

    public record CollegeResponse(Long id, String name, String code, CollegeType collegeType, String state,
                                  String city, String affiliatedUniversity, boolean nmcRecognized,
                                  Integer establishedYear, String website, Instant updatedAt) {
        public static CollegeResponse of(College c) {
            return new CollegeResponse(c.getId(), c.getName(), c.getCode(), c.getCollegeType(), c.getState(),
                    c.getCity(), c.getAffiliatedUniversity(), c.isNmcRecognized(), c.getEstablishedYear(),
                    c.getWebsite(), c.getUpdatedAt());
        }
    }

    public record SeatMatrixResponse(Long id, Long collegeId, Course course, Quota quota, Category category,
                                     boolean pwd, CounsellingRound counsellingRound, int academicYear, int seats) {
        public static SeatMatrixResponse of(SeatMatrixEntry e) {
            return new SeatMatrixResponse(e.getId(), e.getCollegeId(), e.getCourse(), e.getQuota(), e.getCategory(),
                    e.isPwd(), e.getCounsellingRound(), e.getAcademicYear(), e.getSeats());
        }
    }

    public record FeeResponse(Long id, Long collegeId, Course course, Quota quota, int academicYear,
                              BigDecimal annualTuition, BigDecimal otherFees, String notes) {
        public static FeeResponse of(CollegeFee f) {
            return new FeeResponse(f.getId(), f.getCollegeId(), f.getCourse(), f.getQuota(), f.getAcademicYear(),
                    f.getAnnualTuition(), f.getOtherFees(), f.getNotes());
        }
    }

    public record CutoffResponse(Long id, Long collegeId, Course course, Quota quota, Category category,
                                 boolean pwd, CounsellingRound counsellingRound, int academicYear, int closingRank) {
        public static CutoffResponse of(CutoffRecord r) {
            return new CutoffResponse(r.getId(), r.getCollegeId(), r.getCourse(), r.getQuota(), r.getCategory(),
                    r.isPwd(), r.getCounsellingRound(), r.getAcademicYear(), r.getClosingRank());
        }
    }

    public record CollegeDetail(CollegeResponse college, List<SeatMatrixResponse> seatMatrix,
                                List<FeeResponse> fees, List<CutoffResponse> cutoffs) {
    }
}
