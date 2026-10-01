package com.aicallcrm.crm.integration;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    private final boolean otpOnScreen;

    public IntegrationController(VoicePlatforms voice, DndService dnd,
                                 @Value("${app.otp.show-on-screen:false}") boolean otpOnScreen) {
        this.voice = voice;
        this.dnd = dnd;
        this.otpOnScreen = otpOnScreen;
    }

    @GetMapping
    public List<Integration> list() {
        return List.of(
                new Integration("VOICE_AGENT", "AI voice calling", "AI voice agent",
                        voice.live() ? State.CONNECTED : State.NOT_CONNECTED,
                        "Consent, campaigns, scripts, the call-back queue and the test console all work, but no "
                                + "phone rings: campaign calls are recorded as simulated.",
                        "A voice-AI platform (e.g. Retell, Vapi, Bland, Bolna) with an AI model key, and a telephony "
                                + "provider (e.g. Exotel, Knowlarity, Twilio, Plivo). In India, DLT registration."),
                new Integration("DND", "Do-not-disturb checks", "AI voice agent",
                        dnd.connected() ? State.CONNECTED : State.NOT_CONNECTED,
                        "Numbers are not checked against the national do-not-disturb register; only people who "
                                + "gave consent are called.",
                        "The scrubbing service of your telephony provider."),
                new Integration("LOGIN_CODES", "SMS / WhatsApp for login codes", "Sign-in",
                        otpOnScreen ? State.NOT_CONNECTED : State.CONNECTED,
                        otpOnScreen ? "The one-time login code is shown on the login page. Anyone who knows a staff "
                                + "number can sign in, so do not put the site online like this."
                                : "Codes are not shown on screen.",
                        "An SMS or WhatsApp provider (e.g. MSG91, Gupshup) to send the code to the phone."));
    }
}
