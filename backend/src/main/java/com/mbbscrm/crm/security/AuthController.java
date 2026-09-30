package com.mbbscrm.crm.security;

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
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.user.UserDtos.ChangePasswordRequest;
import com.mbbscrm.crm.user.UserDtos.UserResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

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
    private final com.mbbscrm.crm.alert.AlertService alerts;
    private final com.mbbscrm.crm.assistant.RateLimiter limiter;

    public AuthController(AppUserRepository users, PasswordEncoder passwordEncoder, TokenService tokenService,
                          RefreshTokenService refreshTokens, LoginAttemptService loginAttempts,
                          AuditService audit, AppProperties props, OtpService otps,
                          com.mbbscrm.crm.alert.AlertService alerts,
                          com.mbbscrm.crm.assistant.RateLimiter limiter) {
        this.otps = otps;
        this.alerts = alerts;
        this.limiter = limiter;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.refreshTokens = refreshTokens;
        this.loginAttempts = loginAttempts;
        this.audit = audit;
        this.props = props;
        this.dummyHash = passwordEncoder.encode("not-a-real-password-just-for-timing");
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record AuthResponse(String accessToken, long expiresIn, UserResponse user) {
    }

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

    public record OtpRequest(@NotBlank String phone) {
    }

    public record OtpVerify(@NotBlank String phone, @NotBlank String code) {
    }

    /** {@code codeOnScreen} is set only in the temporary show-on-screen mode (see OtpService). */
    public record OtpSent(int validForSeconds, String codeOnScreen, boolean shownOnScreen) {
    }

    /**
     * Step 1 of sign-in. The reply is the same whether or not the number belongs to a staff member, except
     * in show-on-screen mode where the code itself is returned.
     */
    @PostMapping("/otp/request")
    public OtpSent requestOtp(@Valid @RequestBody OtpRequest req) {
        String phone = com.mbbscrm.crm.common.Phones.normalize(req.phone().trim());
        if (phone == null || !phone.matches("[0-9]{10}")) {
            throw ApiException.badRequest("Enter your 10-digit mobile number");
        }
        if (!limiter.allow("otp:" + phone, 5, java.time.Duration.ofMinutes(10))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many codes requested. Try again in 10 minutes.");
        }
        String code = otps.issue(phone);
        return new OtpSent((int) OtpService.VALIDITY.toSeconds(), code, otps.showsOnScreen());
    }

    /** Step 2 of sign-in: the code opens a session exactly like a password login used to. */
    @PostMapping("/otp/verify")
    public ResponseEntity<AuthResponse> verifyOtp(@Valid @RequestBody OtpVerify req) {
        String phone = com.mbbscrm.crm.common.Phones.normalize(req.phone().trim());
        String key = "otp:" + phone;
        if (loginAttempts.isLocked(key)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again in 15 minutes.");
        }
        AppUser user = otps.verify(phone, req.code().replaceAll("\\s", ""));
        if (user == null) {
            loginAttempts.recordFailure(key);
            audit.recordStandalone(null, "LOGIN_FAILED", "USER", null, "otp");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "That code is wrong or has expired. Request a new one.");
        }
        loginAttempts.recordSuccess(key);
        audit.recordStandalone(user.getId(), "LOGIN", "USER", user.getId(), "otp");
        return withSession(user, refreshTokens.create(user));
    }

    public record RegisterRequest(
            @NotBlank @jakarta.validation.constraints.Size(max = 120) String fullName,
            @NotBlank String phone,
            @NotBlank @jakarta.validation.constraints.Email @jakarta.validation.constraints.Size(max = 160) String email,
            @jakarta.validation.constraints.NotNull com.mbbscrm.crm.common.Role role,
            /** Hidden from people; only bots fill it in. */
            @jakarta.validation.constraints.Size(max = 200) String website) {
    }

    /** {@code staffId} is the new account's ID, e.g. "STF-0007". */
    public record Registered(Long id, String staffId, String fullName, boolean pendingApproval) {
    }

    /**
     * Self-registration from the login page: the person enters their own details and an account (staff ID)
     * is created for them. It cannot be used to sign in until an admin approves it, because a staff account
     * opens students' personal data; the admin can also change the role that was asked for. Nobody can
     * register themselves as a Super Admin.
     */
    @PostMapping("/register")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Registered register(@Valid @RequestBody RegisterRequest req, jakarta.servlet.http.HttpServletRequest http) {
        String phone = com.mbbscrm.crm.common.Phones.normalize(req.phone().trim());
        if (phone == null || !phone.matches("[0-9]{10}")) {
            throw ApiException.badRequest("Enter your 10-digit mobile number");
        }
        if (req.role() == com.mbbscrm.crm.common.Role.SUPER_ADMIN) {
            throw ApiException.badRequest("An admin account can only be created by an existing admin");
        }
        String forwarded = http.getHeader("X-Forwarded-For");
        String client = forwarded == null || forwarded.isBlank() ? http.getRemoteAddr() : forwarded.split(",")[0].trim();
        if (!limiter.allow("register:" + client, 5, java.time.Duration.ofHours(1))
                || !limiter.allow("register:all", 100, java.time.Duration.ofHours(24))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please try again later.");
        }
        if (req.website() != null && !req.website().isBlank()) {
            // A bot filled the hidden field: pretend it worked and create nothing.
            return new Registered(null, null, req.fullName().trim(), true);
        }
        String email = req.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (users.findByPhone(phone).isPresent()) {
            throw ApiException.conflict("This mobile number already has an account. Use Log in instead.");
        }
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("This email already has an account");
        }
        AppUser u = new AppUser();
        u.setFullName(req.fullName().trim());
        u.setPhone(phone);
        u.setEmail(email);
        u.setRole(req.role());
        u.setActive(false);
        u.setPendingApproval(true);
        u.setPasswordHash(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
        users.save(u);
        audit.record(null, "REGISTRATION_REQUESTED", "USER", u.getId(), "role=" + u.getRole());
        for (AppUser admin : users.findByActiveTrueAndRoleInOrderByFullName(
                java.util.List.of(com.mbbscrm.crm.common.Role.SUPER_ADMIN))) {
            alerts.notifyUser(admin.getId(), null, "STAFF_REGISTRATION", com.mbbscrm.crm.alert.Priority.NORMAL,
                    "New staff registration: " + u.getFullName(), "Asked to join as " + u.getRole()
                            + ". Approve or reject it under Staff users.", "/admin/users", "REGISTRATION:" + u.getId());
        }
        return new Registered(u.getId(), staffId(u.getId()), u.getFullName(), true);
    }

    public static String staffId(Long id) {
        return String.format("STF-%04d", id);
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
