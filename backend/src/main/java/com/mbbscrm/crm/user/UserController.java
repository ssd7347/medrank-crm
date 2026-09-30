package com.mbbscrm.crm.user;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.branch.Branch;
import com.mbbscrm.crm.branch.BranchRepository;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.security.RefreshTokenService;
import com.mbbscrm.crm.user.UserDtos.CreateUserRequest;
import com.mbbscrm.crm.user.UserDtos.ResetPasswordRequest;
import com.mbbscrm.crm.user.UserDtos.UpdateUserRequest;
import com.mbbscrm.crm.user.UserDtos.UserRef;
import com.mbbscrm.crm.user.UserDtos.UserResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/users")
public class UserController {

    /** Roles that can own leads and students. */
    static final EnumSet<Role> ASSIGNABLE = EnumSet.of(Role.SUPER_ADMIN, Role.COUNSELLOR, Role.TELECALLER);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;
    private final BranchRepository branches;

    private static final String ONE_ADMIN = "There is only one admin account. Staff can be given any other role.";

    public UserController(AppUserRepository users, PasswordEncoder passwordEncoder,
                          RefreshTokenService refreshTokens, AuditService audit, BranchRepository branches) {
        this.branches = branches;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
    }

    /** Staff who can be assigned leads/students. Visible to all staff for dropdowns. */
    @GetMapping("/assignable")
    public List<UserRef> assignable() {
        return users.findByActiveTrueAndRoleInOrderByFullName(ASSIGNABLE).stream().map(UserRef::of).toList();
    }

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<UserResponse> list() {
        return users.findAllByOrderByFullName().stream().map(UserResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public UserResponse create(@Valid @RequestBody CreateUserRequest req) {
        if (req.role() == Role.SUPER_ADMIN) {
            throw ApiException.badRequest(ONE_ADMIN);
        }
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("A user with this email already exists");
        }
        AppUser u = new AppUser();
        u.setFullName(req.fullName().trim());
        u.setEmail(email);
        u.setPhone(phone(req.phone(), null));
        u.setRole(req.role());
        String password = req.password() == null || req.password().isBlank()
                ? java.util.UUID.randomUUID().toString() : req.password();
        if (password.length() < 10) {
            throw ApiException.badRequest("The password must be at least 10 characters");
        }
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setBranch(branch(req.branchId()));
        users.save(u);
        audit.record(CurrentUser.get().id(), "USER_CREATED", "USER", u.getId(), "role=" + u.getRole());
        return UserResponse.of(u);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest req) {
        AppUser u = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        CurrentUser me = CurrentUser.get();
        if (u.getId().equals(me.id()) && (!req.active() || req.role() != Role.SUPER_ADMIN)) {
            throw ApiException.badRequest("You cannot deactivate or demote your own account");
        }
        // There is exactly one admin: nobody else can be promoted, and the admin stays the admin.
        if ((req.role() == Role.SUPER_ADMIN) != (u.getRole() == Role.SUPER_ADMIN)) {
            throw ApiException.badRequest(ONE_ADMIN);
        }
        String before = "role=" + u.getRole() + ", active=" + u.isActive();
        u.setFullName(req.fullName().trim());
        u.setPhone(phone(req.phone(), u.getId()));
        u.setRole(req.role());
        u.setActive(req.active());
        u.setBranch(branch(req.branchId()));
        if (!u.isActive()) {
            refreshTokens.revokeAllForUser(u.getId());
        }
        audit.record(me.id(), "USER_UPDATED", "USER", u.getId(),
                before + " -> role=" + u.getRole() + ", active=" + u.isActive());
        return UserResponse.of(u);
    }

    @PostMapping("/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public void resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest req) {
        AppUser u = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        refreshTokens.revokeAllForUser(u.getId());
        audit.record(CurrentUser.get().id(), "PASSWORD_RESET", "USER", u.getId(), null);
    }

    private Branch branch(Long id) {
        return id == null ? null : branches.findById(id).orElseThrow(() -> ApiException.badRequest("Branch not found"));
    }

    /** Staff sign in with this number, so it is stored in one form and must belong to one person. */
    private String phone(String raw, Long selfId) {
        String phone = com.mbbscrm.crm.common.Phones.normalize(blankToNull(raw));
        if (phone == null) {
            return null;
        }
        if (!phone.matches("[0-9]{10}")) {
            throw ApiException.badRequest("Enter a 10-digit mobile number");
        }
        users.findByPhone(phone).filter(other -> !other.getId().equals(selfId)).ifPresent(other -> {
            throw ApiException.conflict("Another staff member already uses this mobile number");
        });
        return phone;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
