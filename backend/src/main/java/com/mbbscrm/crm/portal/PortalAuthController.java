package com.mbbscrm.crm.portal;

import java.time.Instant;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Phones;
import com.mbbscrm.crm.security.AppProperties;
import com.mbbscrm.crm.security.LoginAttemptService;
import com.mbbscrm.crm.security.TokenService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Portal sign-in for students and parents (spec 4.10): phone number + password, after a one-time activation
 * with the code staff gave them. Separate cookie and token type from staff logins.
 */
@RestController
@RequestMapping("/api/portal/auth")
public class PortalAuthController {

    static final String REFRESH_COOKIE = "crm_portal_refresh";
    private static final String COOKIE_PATH = "/api/portal/auth";
    private static final String GENERIC_FAILURE = "Wrong phone number or password";

    private final PortalAccountRepository accounts;
    private final PortalSessionService sessions;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final LoginAttemptService attempts;
    private final AuditService audit;
    private final AppProperties props;
    /** Compared against when the phone is unknown, so response time does not reveal which phones exist. */
    private final String dummyHash;

    public PortalAuthController(PortalAccountRepository accounts, PortalSessionService sessions,
                                PasswordEncoder passwordEncoder, TokenService tokens, LoginAttemptService attempts,
                                AuditService audit, AppProperties props) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.attempts = attempts;
        this.audit = audit;
        this.props = props;
        this.dummyHash = passwordEncoder.encode("not-a-real-password-just-for-timing");
    }

    public record LoginRequest(@NotBlank String phone, @NotBlank String password) {
    }

    public record ActivateRequest(@NotBlank String phone, @NotBlank String code,
                                  @NotBlank @Size(min = 8, max = 72, message = "must be 8-72 characters") String password) {
    }

    public record PortalSession(String accessToken, long expiresIn, String displayName) {
    }

    @PostMapping("/login")
    @Transactional(noRollbackFor = ApiException.class)
    public ResponseEntity<PortalSession> login(@Valid @RequestBody LoginRequest req) {
        String phone = Phones.normalize(req.phone());
        String key = attemptKey(phone);
        requireNotLocked(key);
        PortalAccount account = accounts.findByPhone(phone).orElse(null);
        boolean usable = account != null && account.isActive() && account.isActivated();
        boolean ok = passwordEncoder.matches(req.password(), usable ? account.getPasswordHash() : dummyHash);
        if (!usable || !ok) {
            attempts.recordFailure(key);
            throw new ApiException(HttpStatus.UNAUTHORIZED, GENERIC_FAILURE);
        }
        attempts.recordSuccess(key);
        return open(account);
    }

    /** First sign-in: the code from the counsellor plus a password of the family's own choosing. */
    @PostMapping("/activate")
    @Transactional(noRollbackFor = ApiException.class)
    public ResponseEntity<PortalSession> activate(@Valid @RequestBody ActivateRequest req) {
        String phone = Phones.normalize(req.phone());
        String key = attemptKey(phone);
        requireNotLocked(key);
        PortalAccount account = accounts.findByPhone(phone).orElse(null);
        boolean pending = account != null && account.isActive() && account.getActivationCodeHash() != null
                && account.getActivationExpiresAt() != null && account.getActivationExpiresAt().isAfter(Instant.now());
        boolean ok = passwordEncoder.matches(PortalAccessService.normalizeCode(req.code()),
                pending ? account.getActivationCodeHash() : dummyHash);
        if (!pending || !ok) {
            attempts.recordFailure(key);
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    "That code is wrong or has expired. Ask your counsellor for a new one.");
        }
        attempts.recordSuccess(key);
        account.activate(passwordEncoder.encode(req.password()));
        sessions.revokeAll(account.getId());
        audit.record(null, "PORTAL_ACTIVATED", "PORTAL_ACCOUNT", account.getId(), null);
        return open(account);
    }

    @PostMapping("/refresh")
    public ResponseEntity<PortalSession> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Session expired, please log in again");
        }
        PortalSessionService.Rotation rotation = sessions.rotate(token);
        return respond(rotation.account(), rotation.newToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        if (token != null && !token.isBlank()) {
            sessions.revoke(token);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).build();
    }

    private ResponseEntity<PortalSession> open(PortalAccount account) {
        account.setLastLoginAt(Instant.now());
        return respond(account, sessions.create(account.getId()));
    }

    private ResponseEntity<PortalSession> respond(PortalAccount account, String refreshToken) {
        PortalSession body = new PortalSession(tokens.issuePortalToken(account.getId(), account.getDisplayName()),
                tokens.accessTtlSeconds(), account.getDisplayName());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(refreshToken, props.jwt().refreshTtl().toSeconds()).toString())
                .body(body);
    }

    private void requireNotLocked(String key) {
        if (attempts.isLocked(key)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again in 15 minutes.");
        }
    }

    private static String attemptKey(String phone) {
        return "portal:" + phone;
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
