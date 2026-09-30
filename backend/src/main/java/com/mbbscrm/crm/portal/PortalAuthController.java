package com.mbbscrm.crm.portal;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.security.AppProperties;
import com.mbbscrm.crm.security.TokenService;

/**
 * Keeps a portal session alive and ends it. Signing in happens on the common login page
 * ({@code /api/auth/otp/*}); portal sessions still use their own cookie and token type, so a student login
 * can never be exchanged for a staff one.
 */
@RestController
@RequestMapping("/api/portal/auth")
public class PortalAuthController {

    static final String REFRESH_COOKIE = "crm_portal_refresh";
    private static final String COOKIE_PATH = "/api/portal/auth";

    private final PortalSessionService sessions;
    private final TokenService tokens;
    private final AppProperties props;

    public PortalAuthController(PortalSessionService sessions, TokenService tokens, AppProperties props) {
        this.sessions = sessions;
        this.tokens = tokens;
        this.props = props;
    }

    public record PortalSession(String accessToken, long expiresIn, String displayName) {
    }

    /** Starts a session for an account that has just proved its mobile number on the common login page. */
    public ResponseCookie openSession(PortalAccount account) {
        return cookie(sessions.create(account.getId()), props.jwt().refreshTtl().toSeconds());
    }

    public PortalSession describe(PortalAccount account) {
        return new PortalSession(tokens.issuePortalToken(account.getId(), account.getDisplayName()),
                tokens.accessTtlSeconds(), account.getDisplayName());
    }

    @PostMapping("/refresh")
    public ResponseEntity<PortalSession> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Session expired, please log in again");
        }
        PortalSessionService.Rotation rotation = sessions.rotate(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        cookie(rotation.newToken(), props.jwt().refreshTtl().toSeconds()).toString())
                .body(describe(rotation.account()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        if (token != null && !token.isBlank()) {
            sessions.revoke(token);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).build();
    }

    private ResponseCookie cookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(props.cookie().secure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAgeSeconds)
                .build();
    }
}
