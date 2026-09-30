package com.mbbscrm.crm.security;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.assistant.RateLimiter;
import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Phones;
import com.mbbscrm.crm.portal.PortalAuthController;
import com.mbbscrm.crm.portal.StudentRegistrationService;
import com.mbbscrm.crm.portal.StudentRegistrationService.RegisterRequest;
import com.mbbscrm.crm.portal.StudentRegistrationService.Registered;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.user.UserDtos.ChangePasswordRequest;
import com.mbbscrm.crm.user.UserDtos.UserResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * The common login for everyone: a mobile number and a one-time code. The number decides who you are: the
 * admin, a staff member (created by the admin), or a student/parent. Students can also register here.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final String REFRESH_COOKIE = "crm_refresh";
    private static final String COOKIE_PATH = "/api/auth";

    /** Compared against when the email is unknown, so response time does not reveal which emails exist. */
    private final String dummyHash;

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokens;
    private final LoginAttemptService loginAttempts;
    private final AuditService audit;
    private final AppProperties props;
    private final OtpService otps;
    private final RateLimiter limiter;
    private final PortalAuthController portalAuth;
    private final StudentRegistrationService registrations;

    public AuthController(AppUserRepository users, PasswordEncoder passwordEncoder, TokenService tokenService,
                          RefreshTokenService refreshTokens, LoginAttemptService loginAttempts,
                          AuditService audit, AppProperties props, OtpService otps, RateLimiter limiter,
                          PortalAuthController portalAuth, StudentRegistrationService registrations) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.refreshTokens = refreshTokens;
        this.loginAttempts = loginAttempts;
        this.audit = audit;
        this.props = props;
        this.otps = otps;
        this.limiter = limiter;
        this.portalAuth = portalAuth;
        this.registrations = registrations;
        this.dummyHash = passwordEncoder.encode("not-a-real-password-just-for-timing");
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record AuthResponse(String accessToken, long expiresIn, UserResponse user) {
    }

    public record OtpRequest(@NotBlank String phone) {
    }

    public record OtpVerify(@NotBlank String phone, @NotBlank String code) {
    }

    /** {@code codeOnScreen} is set only in the temporary show-on-screen mode (see OtpService). */
    public record OtpSent(int validForSeconds, String codeOnScreen, boolean shownOnScreen) {
    }

    /**
     * Result of the common login. {@code kind} is STAFF (then {@code user} is set; the admin is a staff
     * member with the SUPER_ADMIN role) or PORTAL (then {@code displayName} is set, for a student or parent).
     */
    public record LoginResult(String kind, String accessToken, long expiresIn, UserResponse user, String displayName) {
    }

    /**
     * Step 1 of sign-in. The reply is the same whether or not the number is registered, except in
     * show-on-screen mode where the code itself is returned.
     */
    @PostMapping("/otp/request")
    public OtpSent requestOtp(@Valid @RequestBody OtpRequest req) {
        String phone = tenDigits(req.phone());
        if (!limiter.allow("otp:" + phone, 5, Duration.ofMinutes(10))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many codes requested. Try again in 10 minutes.");
        }
        String code = otps.issue(phone);
        return new OtpSent((int) OtpService.VALIDITY.toSeconds(), code, otps.showsOnScreen());
    }

    /** Step 2 of sign-in: opens a staff or a portal session depending on whose number it is. */
    @PostMapping("/otp/verify")
    @Transactional(noRollbackFor = ApiException.class)
    public ResponseEntity<LoginResult> verifyOtp(@Valid @RequestBody OtpVerify req) {
        String phone = Phones.normalize(req.phone().trim());
        String key = "otp:" + phone;
        if (loginAttempts.isLocked(key)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again in 15 minutes.");
        }
        OtpService.Identity who = otps.verify(phone, req.code().replaceAll("\\s", ""));
        if (who == null) {
            loginAttempts.recordFailure(key);
            audit.recordStandalone(null, "LOGIN_FAILED", "USER", null, "otp");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "That code is wrong or has expired. Request a new one.");
        }
        loginAttempts.recordSuccess(key);
        if (who.staff() != null) {
            AppUser user = who.staff();
            audit.recordStandalone(user.getId(), "LOGIN", "USER", user.getId(), "otp");
            ResponseCookie cookie = cookie(refreshTokens.create(user), props.jwt().refreshTtl().toSeconds());
            return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
                    .body(new LoginResult("STAFF", tokenService.issueAccessToken(user), tokenService.accessTtlSeconds(),
                            UserResponse.of(user), null));
        }
        audit.recordStandalone(null, "PORTAL_LOGIN", "PORTAL_ACCOUNT", who.portal().getId(), "otp");
        PortalAuthController.PortalSession session = portalAuth.describe(who.portal());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, portalAuth.openSession(who.portal()).toString())
                .body(new LoginResult("PORTAL", session.accessToken(), session.expiresIn(), null, session.displayName()));
    }

    /** "Register" on the login page. Students only; staff accounts are created by the admin. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Registered register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        String client = forwarded == null || forwarded.isBlank() ? http.getRemoteAddr() : forwarded.split(",")[0].trim();
        if (!limiter.allow("register:" + client, 5, Duration.ofHours(1))
                || !limiter.allow("register:all", 300, Duration.ofHours(24))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please try again later.");
        }
        if (req.website() != null && !req.website().isBlank()) {
            // A bot filled the hidden field: pretend it worked and create nothing.
            return new Registered(null, req.fullName().trim());
        }
        return registrations.register(req);
    }

    /**
     * Email + password sign-in for staff. The login page no longer offers it (everyone uses the one-time
     * code); it remains for the first admin as a fallback and for automated tests.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        String email = req.email().trim();
        if (loginAttempts.isLocked(email)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed attempts. Try again in 15 minutes.");
        }
        AppUser user = users.findByEmailIgnoreCase(email).orElse(null);
        boolean ok = passwordEncoder.matches(req.password(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !ok || !user.isActive()) {
            loginAttempts.recordFailure(email);
            audit.recordStandalone(user == null ? null : user.getId(), "LOGIN_FAILED", "USER",
                    user == null ? null : user.getId(), null);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        loginAttempts.recordSuccess(email);
        audit.recordStandalone(user.getId(), "LOGIN", "USER", user.getId(), null);
        return withSession(user, refreshTokens.create(user));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Session expired, please log in again");
        }
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(token);
        return withSession(rotation.user(), rotation.newToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        if (token != null && !token.isBlank()) {
            refreshTokens.revoke(token);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).build();
    }

    @GetMapping("/me")
    public UserResponse me() {
        return users.findById(CurrentUser.get().id()).map(UserResponse::of)
                .orElseThrow(() -> ApiException.notFound("User"));
    }

    @PostMapping("/change-password")
    @Transactional
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        AppUser user = users.findById(CurrentUser.get().id()).orElseThrow(() -> ApiException.notFound("User"));
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        refreshTokens.revokeAllForUser(user.getId());
        audit.record(user.getId(), "PASSWORD_CHANGED", "USER", user.getId(), null);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).build();
    }

    private static String tenDigits(String raw) {
        String phone = Phones.normalize(raw.trim());
        if (phone == null || !phone.matches("[0-9]{10}")) {
            throw ApiException.badRequest("Enter your 10-digit mobile number");
        }
        return phone;
    }

    private ResponseEntity<AuthResponse> withSession(AppUser user, String refreshToken) {
        AuthResponse body = new AuthResponse(tokenService.issueAccessToken(user), tokenService.accessTtlSeconds(),
                UserResponse.of(user));
        ResponseCookie cookie = cookie(refreshToken, props.jwt().refreshTtl().toSeconds());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(body);
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
