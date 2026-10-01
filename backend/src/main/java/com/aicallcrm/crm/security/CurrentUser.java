package com.aicallcrm.crm.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.common.Role;

/** The authenticated staff member for the current request, read from the access token. */
public record CurrentUser(Long id, Role role, Long branchId) {

    public static CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw ApiException.forbidden("Not authenticated");
        }
        Object branch = jwt.getClaim(TokenService.BRANCH_CLAIM);
        return new CurrentUser(Long.valueOf(jwt.getSubject()),
                Role.valueOf(jwt.getClaimAsString(TokenService.ROLE_CLAIM)),
                branch instanceof Number n ? n.longValue() : null);
    }

    /** Admins and managers see every contact (within their branch, if they have one); agents see their own. */
    public boolean seesAllContacts() {
        return role == Role.SUPER_ADMIN || role == Role.MANAGER;
    }

    public boolean isAdmin() {
        return role == Role.SUPER_ADMIN;
    }

    /** The branch this person is confined to, or null when they may see every branch. */
    public Long branchScope() {
        return isAdmin() ? null : branchId;
    }

    /** True when a record of the given branch is outside this person's branch. */
    public boolean outsideBranch(com.aicallcrm.crm.branch.Branch recordBranch) {
        Long scope = branchScope();
        return scope != null && (recordBranch == null || !scope.equals(recordBranch.getId()));
    }
}
