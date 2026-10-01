package com.aicallcrm.crm.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.security.LoginOtp.Subject;
import com.aicallcrm.crm.user.AppUser;
import com.aicallcrm.crm.user.AppUserRepository;

/**
 * One-time codes for the login: a staff member (the admin included) signs in with their mobile number.
 *
 * There is no WhatsApp/SMS provider yet, so while {@code app.otp.show-on-screen} is true the code is handed
 * back to the login page and shown there. That is a stand-in for delivery, NOT security: anyone who knows a
 * registered mobile number can sign in. Set OTP_SHOW_ON_SCREEN=false the moment codes are delivered to the
 * phone instead (send the code from {@link #deliver}), and never expose the site publicly before that.
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

    public OtpService(LoginOtpRepository otps, AppUserRepository users,
                      PasswordEncoder encoder, @Value("${app.otp.show-on-screen:false}") boolean showOnScreen) {
        this.otps = otps;
        this.users = users;
        this.encoder = encoder;
        this.showOnScreen = showOnScreen;
    }

    public boolean showsOnScreen() {
        return showOnScreen;
    }

    private AppUser identify(String phone) {
        return users.findByPhone(phone).filter(AppUser::isActive).orElse(null);
    }

    /**
     * Creates a fresh code for whoever owns this number, cancelling any earlier one. Returns the code only
     * in show-on-screen mode; otherwise (and for unknown or switched-off numbers) null.
     */
    @Transactional
    public String issue(String phone) {
        AppUser who = identify(phone);
        if (who == null) {
            return null;
        }
        otps.cancelAllFor(LoginOtp.Subject.STAFF, who.getId());
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        otps.save(new LoginOtp(LoginOtp.Subject.STAFF, who.getId(), encoder.encode(code), Instant.now().plus(VALIDITY)));
        deliver(phone, code);
        return showOnScreen ? code : null;
    }

    /** Where the WhatsApp/SMS send goes once a provider is connected. */
    private void deliver(String phone, String code) {
        // Intentionally empty: no provider is connected. See the class comment.
    }

    /** Who signed in, if the code is right; otherwise null. Wrong tries are counted against the code. */
    @Transactional(noRollbackFor = ApiException.class)
    public AppUser verify(String phone, String code) {
        AppUser who = identify(phone);
        if (who == null) {
            return null;
        }
        LoginOtp otp = otps.findFirstBySubjectTypeAndSubjectIdAndUsedFalseOrderByCreatedAtDesc(LoginOtp.Subject.STAFF, who.getId())
                .orElse(null);
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
        return who;
    }
}
