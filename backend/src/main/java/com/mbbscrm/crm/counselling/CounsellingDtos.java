package com.mbbscrm.crm.counselling;

import java.time.Instant;
import java.util.List;

import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.CollegeType;
import com.mbbscrm.crm.common.CounsellingRound;
import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.Quota;
import com.mbbscrm.crm.user.UserDtos.UserRef;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CounsellingDtos {

    private CounsellingDtos() {
    }

    // ------------------------------------------------------------------ calendar

    public record AuthorityRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{2,30}$", message = "letters, digits and '-' only") String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull AuthorityType authorityType,
            @Size(max = 40) String state,
            @Size(max = 200) @Pattern(regexp = "^(https?://\\S+)?$", message = "must start with http(s)://")
            String website,
            boolean active) {
    }

    public record AuthorityResponse(Long id, String code, String name, AuthorityType authorityType, String state,
                                    String website, boolean active) {
        public static AuthorityResponse of(CounsellingAuthority a) {
            return new AuthorityResponse(a.getId(), a.getCode(), a.getName(), a.getAuthorityType(), a.getState(),
                    a.getWebsite(), a.isActive());
        }
    }

    public record AuthorityRef(Long id, String code, String name, AuthorityType authorityType, String state) {
        public static AuthorityRef of(CounsellingAuthority a) {
            return new AuthorityRef(a.getId(), a.getCode(), a.getName(), a.getAuthorityType(), a.getState());
        }
    }

    public record RoundRequest(
            @NotNull Long authorityId,
            @NotNull @Min(2013) @Max(2100) Integer academicYear,
            @NotNull CounsellingRound roundType,
            Instant registrationStart, Instant registrationEnd,
            Instant choiceFillingStart, Instant choiceFillingEnd,
            Instant resultAt,
            Instant reportingStart, Instant reportingEnd,
            @Size(max = 1000) String notes) {
    }

    public record RoundResponse(Long id, AuthorityRef authority, int academicYear, CounsellingRound roundType,
                                String label, Instant registrationStart, Instant registrationEnd,
                                Instant choiceFillingStart, Instant choiceFillingEnd, Instant resultAt,
                                Instant reportingStart, Instant reportingEnd, String notes, String phase) {
        public static RoundResponse of(CounsellingRoundEntity r) {
            return new RoundResponse(r.getId(), AuthorityRef.of(r.getAuthority()), r.getAcademicYear(),
                    r.getRoundType(), r.label(), r.getRegistrationStart(), r.getRegistrationEnd(),
                    r.getChoiceFillingStart(), r.getChoiceFillingEnd(), r.getResultAt(), r.getReportingStart(),
                    r.getReportingEnd(), r.getNotes(), RoundPhase.of(r, Instant.now()));
        }
    }

    /** One upcoming deadline across all rounds, for the calendar / countdown views. */
    public record Deadline(Long roundId, String roundLabel, String kind, Instant at) {
    }

    // ------------------------------------------------------------------ student tracks

    public record TrackRequest(@NotNull Long authorityId, @NotNull @Min(2013) @Max(2100) Integer academicYear,
                               @Size(max = 40) String registrationNo, @NotNull CounsellingStatus status) {
    }

    public record TrackUpdateRequest(@Size(max = 40) String registrationNo, @NotNull CounsellingStatus status) {
    }

    public record CollegeRef(Long id, String name, String code, String state, CollegeType collegeType) {
        public static CollegeRef of(com.mbbscrm.crm.college.College c) {
            return c == null ? null : new CollegeRef(c.getId(), c.getName(), c.getCode(), c.getState(),
                    c.getCollegeType());
        }
    }

    public record ChoiceListSummary(Long id, Long roundId, String roundLabel, ChoiceListStatus status, int items,
                                    Instant lockedAt) {
    }

    public record AllotmentResponse(Long id, Long trackId, Long roundId, String roundLabel,
                                    CounsellingRound roundType, CollegeRef college, Course course, Quota quota,
                                    Category category, UserRef recordedBy, Instant recordedAt, Decision decision,
                                    Instant decisionDeadline, Instant decidedAt, UserRef decidedBy,
                                    String decisionNote) {
        public static AllotmentResponse of(AllotmentResult a) {
            return new AllotmentResponse(a.getId(), a.getStudentCounselling().getId(), a.getRound().getId(),
                    a.getRound().label(), a.getRound().getRoundType(), CollegeRef.of(a.getCollege()), a.getCourse(),
                    a.getQuota(), a.getCategory(), UserRef.of(a.getRecordedBy()), a.getRecordedAt(), a.getDecision(),
                    a.getDecisionDeadline(), a.getDecidedAt(), UserRef.of(a.getDecidedBy()), a.getDecisionNote());
        }
    }

    public record TrackResponse(Long id, Long studentId, AuthorityRef authority, int academicYear,
                                String registrationNo, CounsellingStatus status, List<RoundResponse> rounds,
                                List<ChoiceListSummary> choiceLists, List<AllotmentResponse> allotments) {
    }

    // ------------------------------------------------------------------ choice lists

    public record ChoiceItemRequest(@NotNull Long collegeId, @NotNull Course course, @NotNull Quota quota,
                                    @Size(max = 300) String note) {
    }

    public record ChoiceListUpdateRequest(@NotNull @Size(max = 500) List<@Valid ChoiceItemRequest> items) {
    }

    public record LockRequest(@NotBlank @Size(max = 120) String confirmedByName,
                              @NotNull ConfirmationMethod confirmationMethod) {
    }

    public record UnlockRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record ChoiceItemResponse(Long id, int position, CollegeRef college, Course course, Quota quota,
                                     String note) {
        static ChoiceItemResponse of(ChoiceListItem i) {
            return new ChoiceItemResponse(i.getId(), i.getPosition(), CollegeRef.of(i.getCollege()), i.getCourse(),
                    i.getQuota(), i.getNote());
        }
    }

    public record ChoiceListResponse(Long id, Long trackId, Long studentId, String studentName, AuthorityRef authority,
                                     RoundResponse round, ChoiceListStatus status, Instant lockedAt,
                                     UserRef lockedBy, String confirmedByName,
                                     ConfirmationMethod confirmationMethod, List<ChoiceItemResponse> items,
                                     Instant updatedAt) {
    }

    // ------------------------------------------------------------------ allotments

    /** {@code collegeId} null records "no allotment in this round". */
    public record AllotmentRequest(@NotNull Long roundId, Long collegeId, Course course, Quota quota,
                                   Category category) {
    }

    public record DecisionRequest(@NotNull Decision decision, @Size(max = 1000) String note) {
    }

    /** Shown before confirming a decision, so nobody gives up a seat unaware of the consequences. */
    public record DecisionPreview(Decision decision, List<String> consequences, boolean pastDeadline,
                                  boolean refundRuleFound) {
    }

    public record BulkAllotmentResult(int recorded, int noAllotment, List<BulkError> errors) {
    }

    public record BulkError(int row, String message) {
    }

    // ------------------------------------------------------------------ round desk

    public record DeskRow(Long allotmentId, Long studentId, String studentName, String studentPhone,
                          UserRef counsellor, String roundLabel, CollegeRef college, Quota quota,
                          Instant decisionDeadline) {
        public static DeskRow of(AllotmentResult a) {
            var s = a.getStudentCounselling().getStudent();
            return new DeskRow(a.getId(), s.getId(), s.getFullName(), s.getPhone(),
                    UserRef.of(s.getAssignedCounsellor()), a.getRound().label(), CollegeRef.of(a.getCollege()),
                    a.getQuota(), a.getDecisionDeadline());
        }
    }

    public record RoundDesk(List<Deadline> upcomingDeadlines, List<DeskRow> decisionsPending,
                            long unacknowledgedEscalations) {
    }
}
