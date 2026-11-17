package com.aicallcrm.crm.user;

import java.time.Instant;

import com.aicallcrm.crm.branch.BranchRef;
import com.aicallcrm.crm.common.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserDtos {

    private UserDtos() {
    }

    /** {@code canHearRecordings}: may play AI call recordings (always true for the admin). */
    public record UserResponse(Long id, String fullName, String email, String phone, Role role,
                               boolean active, BranchRef branch, Instant createdAt, boolean canHearRecordings) {
        public static UserResponse of(AppUser u) {
            return new UserResponse(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole(),
                    u.isActive(), BranchRef.of(u.getBranch()), u.getCreatedAt(),
                    u.getRole() == Role.SUPER_ADMIN || u.isCanHearRecordings());
        }
    }

    /** Minimal user reference for dropdowns and "assigned to" labels. */
    public record UserRef(Long id, String fullName, Role role) {
        public static UserRef of(AppUser u) {
            return u == null ? null : new UserRef(u.getId(), u.getFullName(), u.getRole());
        }
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 160) String email,
            @Pattern(regexp = "^[0-9+ -]{0,20}$", message = "invalid phone") String phone,
            @NotNull Role role,
            /** No longer used to sign in (that is by mobile number and code); a random one is set if empty. */
            @Size(max = 72) String password,
            Long branchId,
            /** May play AI call recordings. Left out: no. */
            Boolean canHearRecordings) {
    }

    public record UpdateUserRequest(
            @NotBlank @Size(max = 120) String fullName,
            @Pattern(regexp = "^[0-9+ -]{0,20}$", message = "invalid phone") String phone,
            @NotNull Role role,
            boolean active,
            Long branchId,
            /** May play AI call recordings. Left out: unchanged. */
            Boolean canHearRecordings) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 10, max = 72, message = "must be 10-72 characters") String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 10, max = 72, message = "must be 10-72 characters") String newPassword) {
    }
}
