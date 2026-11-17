package com.aicallcrm.crm.integration;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aicallcrm.crm.security.OtpService;
import com.aicallcrm.crm.voice.DndService;
import com.aicallcrm.crm.voice.VoicePlatforms;

/**
 * One honest list of which outside services are connected and what the CRM does in the meantime, so nobody
 * assumes a call was made or a number was checked when it was not.
 */
@RestController
@RequestMapping("/api/integrations")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class IntegrationController {

    public enum State {
        CONNECTED, NOT_CONNECTED
    }

    public record Integration(String key, String name, String module, State state, String today, String needs) {
    }

    private final VoicePlatforms voice;
    private final DndService dnd;
    private final OtpService otp;
    private final boolean otpOnScreen;

    public IntegrationController(VoicePlatforms voice, DndService dnd, OtpService otp,
                                 @Value("${app.otp.show-on-screen:false}") boolean otpOnScreen) {
        this.voice = voice;
        this.dnd = dnd;
        this.otp = otp;
        this.otpOnScreen = otpOnScreen;
    }

    @GetMapping
    public List<Integration> list() {
        return List.of(
                new Integration("VOICE_AGENT", "AI voice calling", "AI voice agent",
                        voice.live() ? State.CONNECTED : State.NOT_CONNECTED,
                        "Consent, campaigns, scripts, the call-back queue and the test console all work, but no "
                                + "phone rings: campaign calls are recorded as simulated.",
                        "This company's own Plivo account (India data region, KYC done, a number; in India, DLT "
                                + "registration) and a GPU computer for our AI model and speech engine, then "
                                + "VOICE_PLATFORM=plivo (docs/voice-agent.md). Another voice platform can be "
                                + "connected through an adapter instead."),
                new Integration("DND", "Do-not-disturb checks", "AI voice agent",
                        dnd.connected() ? State.CONNECTED : State.NOT_CONNECTED,
                        "Numbers are not checked against the national do-not-disturb register; only people who "
                                + "gave consent are called.",
                        "The scrubbing service of your telephony provider."),
                loginCodes(otp.sendsCodes(), otpOnScreen));
    }

    /** Connected only when codes really reach the phone; hiding them from the screen alone sends nothing. */
    static Integration loginCodes(boolean sent, boolean onScreen) {
        String today = sent ? "Codes are sent by SMS through MSG91. If one does not arrive, MSG91's SMS log says why."
                        + (onScreen ? " They are also shown on the login page: set OTP_SHOW_ON_SCREEN=false." : "")
                : onScreen ? "The one-time login code is shown on the login page. Anyone who knows a staff number can "
                        + "sign in, so do not put the site online like this."
                : "Codes are neither shown on the login page nor sent to the phone, so nobody can sign in. Until SMS "
                        + "is connected, OTP_SHOW_ON_SCREEN=true (only while the site is not on the internet).";
        return new Integration("LOGIN_CODES", "SMS for login codes", "Sign-in",
                sent ? State.CONNECTED : State.NOT_CONNECTED, today,
                "This company's own MSG91 account with a DLT-registered sender ID and an approved sign-in template, "
                        + "then OTP_SMS_PROVIDER=msg91, MSG91_AUTH_KEY and MSG91_OTP_TEMPLATE_ID "
                        + "(docs/new-customer-setup.md).");
    }
}
