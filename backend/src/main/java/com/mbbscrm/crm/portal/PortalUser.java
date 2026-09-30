package com.mbbscrm.crm.portal;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.security.TokenService;

/** The signed-in student or parent for the current portal request. */
public record PortalUser(Long accountId) {

    public static PortalUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)
                || !TokenService.PORTAL_ROLE.equals(jwt.getClaimAsString(TokenService.ROLE_CLAIM))) {
            throw ApiException.forbidden("Not signed in to the portal");
        }
        return new PortalUser(Long.valueOf(jwt.getSubject()));
    }
}
