package com.mbbscrm.crm.user;

import java.time.Instant;

import com.mbbscrm.crm.branch.BranchRef;
import com.mbbscrm.crm.common.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserDtos {

    private UserDtos() {
    }

    public record UserResponse(Long id, String fullName, String email, String phone, Role role,
                               boolean active, BranchRef branch, Instant createdAt) {
        public static UserResponse of(AppUser u) {
            return new UserResponse(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole(),
                    u.isActive(), BranchRef.of(u.getBranch()), u.getCreatedAt());
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
            @NotBlank @Size(min = 10, max = 72, message = "must be 10-72 characters") String password,
            Long branchId) {
    }

    public record UpdateUserRequest(
            @NotBlank @Size(max = 120) String fullName,
            @Pattern(regexp = "^[0-9+ -]{0,20}$", message = "invalid phone") String phone,
            @NotNull Role role,
            boolean active,
            Long branchId) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 10, max = 72, message = "must be 10-72 characters") String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 10, max = 72, message = "must be 10-72 characters") String newPassword) {
    }
}
