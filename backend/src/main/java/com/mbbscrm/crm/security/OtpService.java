package com.mbbscrm.crm.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;

/**
 * One-time codes for staff sign-in by mobile number.
 *
 * There is no WhatsApp/SMS provider yet, so while {@code app.otp.show-on-screen} is true the code is handed
 * back to the login page and shown there. That is a stand-in for delivery, NOT security: anyone who knows a
 * staff mobile number can sign in. Set OTP_SHOW_ON_SCREEN=false the moment codes are delivered to the phone
 * instead (send the code from {@link #deliver}), and never expose the site publicly before that.
 */
@Service
public class OtpService {

    static final Duration VALIDITY = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final LoginOtpRepository otps;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final boolean showOnScreen;

    public OtpService(LoginOtpRepository otps, AppUserRepository users, PasswordEncoder encoder,
                      @Value("${app.otp.show-on-screen:false}") boolean showOnScreen) {
        this.otps = otps;
        this.users = users;
        this.encoder = encoder;
        this.showOnScreen = showOnScreen;
    }

    public boolean showsOnScreen() {
        return showOnScreen;
    }

    /**
     * Creates a fresh code for the staff member with this number, cancelling any earlier one. Returns the
     * code only in show-on-screen mode; otherwise (and for unknown or inactive numbers) null.
     */
    @Transactional
    public String issue(String phone) {
        AppUser user = users.findByPhone(phone).filter(AppUser::isActive).orElse(null);
        if (user == null) {
            return null;
        }
        otps.cancelAllForUser(user.getId());
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        otps.save(new LoginOtp(user.getId(), encoder.encode(code), Instant.now().plus(VALIDITY)));
        deliver(user, code);
        return showOnScreen ? code : null;
    }

    /** Where the WhatsApp/SMS send goes once a provider is connected. */
    private void deliver(AppUser user, String code) {
        // Intentionally empty: no provider is connected. See the class comment.
    }

    /** The staff member if the code is right; otherwise null. Wrong tries are counted against the code. */
    @Transactional(noRollbackFor = ApiException.class)
    public AppUser verify(String phone, String code) {
        AppUser user = users.findByPhone(phone).filter(AppUser::isActive).orElse(null);
        if (user == null) {
            return null;
        }
        LoginOtp otp = otps.findFirstByUserIdAndUsedFalseOrderByCreatedAtDesc(user.getId()).orElse(null);
        if (otp == null || otp.getExpiresAt().isBefore(Instant.now())) {
            return null;
        }
        if (!encoder.matches(code, otp.getCodeHash())) {
            otp.wrongAttempt();
            if (otp.getAttempts() >= MAX_ATTEMPTS) {
                otp.use();
            }
            return null;
        }
        otp.use();
        return user;
    }
}
