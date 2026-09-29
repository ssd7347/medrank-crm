package com.mbbscrm.crm.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Role;

/** The authenticated staff member for the current request, read from the access token. */
public record CurrentUser(Long id, Role role) {

    public static CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw ApiException.forbidden("Not authenticated");
        }
        return new CurrentUser(Long.valueOf(jwt.getSubject()),
                Role.valueOf(jwt.getClaimAsString(TokenService.ROLE_CLAIM)));
    }

    public boolean isAdmin() {
        return role == Role.SUPER_ADMIN;
    }
}
