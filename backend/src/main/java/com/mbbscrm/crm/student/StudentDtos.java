package com.mbbscrm.crm.student;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.DomicileStatus;
import com.mbbscrm.crm.common.Gender;
import com.mbbscrm.crm.common.Language;
import com.mbbscrm.crm.common.Nationality;
import com.mbbscrm.crm.student.EligibilityService.Eligibility;
import com.mbbscrm.crm.user.UserDtos.UserRef;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class StudentDtos {

    private StudentDtos() {
    }

    public record StudentRequest(
            @NotBlank @Size(max = 120) String fullName,
            @Past LocalDate dateOfBirth,
            Gender gender,
            @NotBlank @Pattern(regexp = "^[0-9+ -]{10,20}$", message = "invalid phone") String phone,
            @Email @Size(max = 160) String email,
            @Size(max = 120) String parentName,
            @Pattern(regexp = "^([0-9+ -]{10,20})?$", message = "invalid phone") String parentPhone,
            @NotNull Category category,
            boolean pwd,
            @NotBlank @Size(max = 40) String homeState,
            @NotNull DomicileStatus domicileStatus,
            @NotNull Nationality nationality,
            boolean nriSponsored,
            @Min(2013) @Max(2100) Integer neetYear,
            @Pattern(regexp = "^[A-Za-z0-9]{0,20}$", message = "letters and digits only") String neetRollNo,
            boolean neetQualified,
            @Min(-180) @Max(720) Integer neetScore,
            @DecimalMin("0") @DecimalMax("100") BigDecimal neetPercentile,
            @Min(1) Integer neetAir,
            @Min(1) Integer categoryRank,
            LocalDate categoryCertValidUntil,
            @NotNull Language languagePreference,
            @Pattern(regexp = "^[A-Za-z0-9]{0,20}$", message = "letters and digits only") String apaarId,
            Long assignedCounsellorId) {
    }

    public record StudentListItem(Long id, String fullName, String phone, Category category, String homeState,
                                  Integer neetScore, Integer neetAir, UserRef assignedCounsellor,
                                  Instant createdAt) {
        public static StudentListItem of(Student s) {
            return new StudentListItem(s.getId(), s.getFullName(), s.getPhone(), s.getCategory(), s.getHomeState(),
                    s.getNeetScore(), s.getNeetAir(), UserRef.of(s.getAssignedCounsellor()), s.getCreatedAt());
        }
    }

    public record StudentResponse(
            Long id, String apaarId, String fullName, LocalDate dateOfBirth, Gender gender, String phone,
            String email, String parentName, String parentPhone, Category category, boolean pwd, String homeState,
            DomicileStatus domicileStatus, Nationality nationality, boolean nriSponsored, Integer neetYear,
            String neetRollNo, boolean neetQualified, Integer neetScore, BigDecimal neetPercentile, Integer neetAir,
            Integer categoryRank, LocalDate categoryCertValidUntil, Language languagePreference,
            UserRef assignedCounsellor, Long leadId, Eligibility eligibility, Instant createdAt, Instant updatedAt) {

        public static StudentResponse of(Student s, Long leadId, Eligibility eligibility) {
            return new StudentResponse(s.getId(), s.getApaarId(), s.getFullName(), s.getDateOfBirth(), s.getGender(),
                    s.getPhone(), s.getEmail(), s.getParentName(), s.getParentPhone(), s.getCategory(), s.isPwd(),
                    s.getHomeState(), s.getDomicileStatus(), s.getNationality(), s.isNriSponsored(), s.getNeetYear(),
                    s.getNeetRollNo(), s.isNeetQualified(), s.getNeetScore(), s.getNeetPercentile(), s.getNeetAir(),
                    s.getCategoryRank(), s.getCategoryCertValidUntil(), s.getLanguagePreference(),
                    UserRef.of(s.getAssignedCounsellor()), leadId, eligibility, s.getCreatedAt(), s.getUpdatedAt());
        }
    }
}
