package com.aicallcrm.crm.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.security.LoginOtp.Subject;
import com.aicallcrm.crm.user.AppUser;
import com.aicallcrm.crm.user.AppUserRepository;

/**
 * One-time codes for the login: a staff member (the admin included) signs in with their mobile number.
 *
 * The code is sent by SMS once MSG91 is set up ({@link Msg91Sms}, OTP_SMS_PROVIDER=msg91). Until then, while
 * {@code app.otp.show-on-screen} is true, the code is handed back to the login page and shown there. That is a
 * stand-in for delivery, NOT security: anyone who knows a registered mobile number can sign in. Never expose the site
 * publicly with OTP_SHOW_ON_SCREEN=true.
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
    private final Msg91Sms sms;

    public OtpService(LoginOtpRepository otps, AppUserRepository users, PasswordEncoder encoder,
                      @Value("${app.otp.show-on-screen:false}") boolean showOnScreen, Msg91Sms sms) {
        this.otps = otps;
        this.users = users;
        this.encoder = encoder;
        this.showOnScreen = showOnScreen;
        this.sms = sms;
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

    /**
     * Sends the code by SMS once it is saved, so a code that was never stored is never sent, and the provider is not
     * called while the database transaction is open. In the background: the reply to the login page is the same, and
     * as quick, whether or not the number is registered or MSG91 accepted it; a failure is in the log.
     */
    private void deliver(String phone, String code) {
        if (!sms.configured()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sms.sendCodeInBackground(phone, code);
            }
        });
    }

    /** Whether {@link #deliver} really sends the code to the phone. */
    public boolean sendsCodes() {
        return sms.configured();
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
