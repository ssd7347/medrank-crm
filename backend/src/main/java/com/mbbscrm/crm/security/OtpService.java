package com.mbbscrm.crm.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.portal.PortalAccount;
import com.mbbscrm.crm.portal.PortalAccountRepository;
import com.mbbscrm.crm.security.LoginOtp.Subject;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;

/**
 * One-time codes for the common login: a mobile number belongs either to a staff member (the admin included)
 * or to a student/parent portal login, and the same two steps sign in both.
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
    private final PortalAccountRepository portalAccounts;
    private final PasswordEncoder encoder;
    private final boolean showOnScreen;

    public OtpService(LoginOtpRepository otps, AppUserRepository users, PortalAccountRepository portalAccounts,
                      PasswordEncoder encoder, @Value("${app.otp.show-on-screen:false}") boolean showOnScreen) {
        this.otps = otps;
        this.users = users;
        this.portalAccounts = portalAccounts;
        this.encoder = encoder;
        this.showOnScreen = showOnScreen;
    }

    /** Who a mobile number signs in as. Exactly one of the two is set. */
    public record Identity(AppUser staff, PortalAccount portal) {
        Subject type() {
            return staff != null ? Subject.STAFF : Subject.PORTAL;
        }

        Long id() {
            return staff != null ? staff.getId() : portal.getId();
        }
    }

    public boolean showsOnScreen() {
        return showOnScreen;
    }

    /** Staff first: a staff member whose number is also on a student record signs in as staff. */
    private Identity identify(String phone) {
        AppUser staff = users.findByPhone(phone).filter(AppUser::isActive).orElse(null);
        if (staff != null) {
            return new Identity(staff, null);
        }
        PortalAccount portal = portalAccounts.findByPhone(phone).filter(PortalAccount::isActive).orElse(null);
        return portal == null ? null : new Identity(null, portal);
    }

    /**
     * Creates a fresh code for whoever owns this number, cancelling any earlier one. Returns the code only
     * in show-on-screen mode; otherwise (and for unknown or switched-off numbers) null.
     */
    @Transactional
    public String issue(String phone) {
        Identity who = identify(phone);
        if (who == null) {
            return null;
        }
        otps.cancelAllFor(who.type(), who.id());
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        otps.save(new LoginOtp(who.type(), who.id(), encoder.encode(code), Instant.now().plus(VALIDITY)));
        deliver(phone, code);
        return showOnScreen ? code : null;
    }

    /** Where the WhatsApp/SMS send goes once a provider is connected. */
    private void deliver(String phone, String code) {
        // Intentionally empty: no provider is connected. See the class comment.
    }

    /** Who signed in, if the code is right; otherwise null. Wrong tries are counted against the code. */
    @Transactional(noRollbackFor = ApiException.class)
    public Identity verify(String phone, String code) {
        Identity who = identify(phone);
        if (who == null) {
            return null;
        }
        LoginOtp otp = otps.findFirstBySubjectTypeAndSubjectIdAndUsedFalseOrderByCreatedAtDesc(who.type(), who.id())
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
        if (who.portal() != null) {
            who.portal().setLastLoginAt(Instant.now());
        }
        return who;
    }
}
