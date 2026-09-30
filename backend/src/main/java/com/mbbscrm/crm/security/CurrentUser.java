package com.mbbscrm.crm.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Role;

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

    public boolean isAdmin() {
        return role == Role.SUPER_ADMIN;
    }

    /** The branch this person is confined to, or null when they may see every branch (spec 4.15). */
    public Long branchScope() {
        return isAdmin() ? null : branchId;
    }

    /** True when a record of the given branch is outside this person's branch. */
    public boolean outsideBranch(com.mbbscrm.crm.branch.Branch recordBranch) {
        Long scope = branchScope();
        return scope != null && (recordBranch == null || !scope.equals(recordBranch.getId()));
    }
}
