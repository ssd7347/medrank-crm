package com.mbbscrm.crm.lead;

import java.time.Instant;
import java.util.List;

import com.mbbscrm.crm.common.ActivityType;
import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.DomicileStatus;
import com.mbbscrm.crm.common.Language;
import com.mbbscrm.crm.common.LeadSource;
import com.mbbscrm.crm.common.LeadStatus;
import com.mbbscrm.crm.referral.ReferralAssociate;
import com.mbbscrm.crm.user.UserDtos.UserRef;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class LeadDtos {

    private LeadDtos() {
    }

    public record LeadRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = "^[0-9+ -]{10,20}$", message = "invalid phone") String phone,
            @Pattern(regexp = "^([0-9+ -]{10,20})?$", message = "invalid phone") String altPhone,
            @Email @Size(max = 160) String email,
            @Pattern(regexp = "^[A-Za-z0-9]{0,20}$", message = "letters and digits only") String neetRollNo,
            @Min(-180) @Max(720) Integer neetScore,
            @Min(1) Integer neetAir,
            Category category,
            @Size(max = 40) String homeState,
            DomicileStatus domicileStatus,
            @NotNull LeadSource source,
            Long referralAssociateId,
            Language languagePreference,
            @Size(max = 2000) String notes,
            Long assignedCounsellorId,
            /** Set after the user has seen the duplicate warning and confirmed (e.g. siblings sharing a phone). */
            boolean allowDuplicatePhone) {
    }

    public record AssociateRef(Long id, String fullName) {
        static AssociateRef of(ReferralAssociate a) {
            return a == null ? null : new AssociateRef(a.getId(), a.getFullName());
        }
    }

    public record LeadListItem(Long id, String fullName, String phone, Integer neetScore, Integer neetAir,
                               Category category, String homeState, LeadSource source, LeadStatus status,
                               UserRef assignedCounsellor, Long studentId, Instant createdAt) {
        static LeadListItem of(Lead l) {
            return new LeadListItem(l.getId(), l.getFullName(), l.getPhone(), l.getNeetScore(), l.getNeetAir(),
                    l.getCategory(), l.getHomeState(), l.getSource(), l.getStatus(),
                    UserRef.of(l.getAssignedCounsellor()), l.getStudent() == null ? null : l.getStudent().getId(),
                    l.getCreatedAt());
        }
    }

    public record LeadResponse(Long id, String fullName, String phone, String altPhone, String email,
                               String neetRollNo, Integer neetScore, Integer neetAir, Category category,
                               String homeState, DomicileStatus domicileStatus, LeadSource source,
                               AssociateRef referralAssociate, LeadStatus status, UserRef assignedCounsellor,
                               Language languagePreference, String notes, Long studentId, Instant createdAt,
                               Instant updatedAt) {
        static LeadResponse of(Lead l) {
            return new LeadResponse(l.getId(), l.getFullName(), l.getPhone(), l.getAltPhone(), l.getEmail(),
                    l.getNeetRollNo(), l.getNeetScore(), l.getNeetAir(), l.getCategory(), l.getHomeState(),
                    l.getDomicileStatus(), l.getSource(), AssociateRef.of(l.getReferralAssociate()), l.getStatus(),
                    UserRef.of(l.getAssignedCounsellor()), l.getLanguagePreference(), l.getNotes(),
                    l.getStudent() == null ? null : l.getStudent().getId(), l.getCreatedAt(), l.getUpdatedAt());
        }
    }

    public record DuplicateRef(Long id, String fullName, String phone, String neetRollNo, LeadStatus status) {
        static DuplicateRef of(Lead l) {
            return new DuplicateRef(l.getId(), l.getFullName(), l.getPhone(), l.getNeetRollNo(), l.getStatus());
        }
    }

    public record StatusChangeRequest(@NotNull LeadStatus status, @Size(max = 2000) String note) {
    }

    /** {@code userId} null unassigns the lead. */
    public record AssignRequest(Long userId) {
    }

    public record ActivityRequest(@NotNull ActivityType type, @Size(max = 120) String outcome,
                                  @Size(max = 2000) String notes) {
    }

    public record ActivityResponse(Long id, ActivityType type, String outcome, String notes, UserRef createdBy,
                                   Instant createdAt) {
        static ActivityResponse of(LeadActivity a) {
            return new ActivityResponse(a.getId(), a.getType(), a.getOutcome(), a.getNotes(),
                    UserRef.of(a.getCreatedBy()), a.getCreatedAt());
        }
    }

    public record FollowUpRequest(@NotNull @Future Instant dueAt, @NotBlank @Size(max = 200) String purpose,
                                  Long assignedToId) {
    }

    public record FollowUpResponse(Long id, Long leadId, String leadName, String leadPhone, UserRef assignedTo,
                                   Instant dueAt, String purpose, Instant completedAt, boolean overdue) {
        static FollowUpResponse of(FollowUp f) {
            return new FollowUpResponse(f.getId(), f.getLead().getId(), f.getLead().getFullName(),
                    f.getLead().getPhone(), UserRef.of(f.getAssignedTo()), f.getDueAt(), f.getPurpose(),
                    f.getCompletedAt(), f.getCompletedAt() == null && f.getDueAt().isBefore(Instant.now()));
        }
    }

    public record ImportError(int row, String message) {
    }

    public record ImportResult(int imported, int skippedDuplicates, List<ImportError> errors) {
    }
}
