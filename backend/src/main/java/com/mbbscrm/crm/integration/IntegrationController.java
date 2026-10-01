package com.mbbscrm.crm.integration;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.alert.MessageSender;
import com.mbbscrm.crm.alert.SimulatedMessageSender;

/**
 * One honest list of which outside services are connected and what the CRM does in the meantime, so nobody
 * assumes a message was delivered or a document was auto-verified when it was not.
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

    private final MessageSender sender;
    private final boolean r2;

    private final com.mbbscrm.crm.voice.VoicePlatforms voice;

    public IntegrationController(MessageSender sender, @Value("${app.storage.r2.endpoint:}") String r2Endpoint,
                                 @Value("${app.storage.r2.bucket:}") String r2Bucket,
                                 com.mbbscrm.crm.voice.VoicePlatforms voice) {
        this.voice = voice;
        this.sender = sender;
        this.r2 = !r2Endpoint.isBlank() && !r2Bucket.isBlank();
    }

    @GetMapping
    public List<Integration> list() {
        boolean messaging = !(sender instanceof SimulatedMessageSender);
        return List.of(
                new Integration("MESSAGING", "WhatsApp & SMS", "Alerts (4.9)",
                        messaging ? State.CONNECTED : State.NOT_CONNECTED,
                        "Messages are written in the student's language and logged, but not delivered. Staff must call.",
                        "A WhatsApp Business provider (e.g. Gupshup, Interakt) and an SMS provider (e.g. MSG91) with approved templates."),
                new Integration("STORAGE", "Cloudflare R2 document storage", "Documents (4.7)",
                        r2 ? State.CONNECTED : State.NOT_CONNECTED,
                        "Uploaded documents are kept on this server's disk.",
                        "R2 bucket and access keys in the server settings."),
                new Integration("DIGILOCKER", "DigiLocker", "Auto-verified documents (4.16)", State.NOT_CONNECTED,
                        "Documents are uploaded by staff or by the family in the portal and verified by hand.",
                        "DigiLocker partner approval and API credentials from the issuing departments."),
                new Integration("ESIGN", "Aadhaar e-Sign", "Agreements (4.20)", State.NOT_CONNECTED,
                        "Families accept agreements in the portal by typing their name, or staff record a signed paper copy.",
                        "An account with a licensed e-Sign provider (e.g. eMudhra, NSDL)."),
                new Integration("TELEPHONY", "Click-to-call & recording", "Call logging (4.21)", State.NOT_CONNECTED,
                        "The Call button opens the phone dialer and staff record the outcome. No recordings.",
                        "An Exotel or Knowlarity account and a virtual number."),
                new Integration("VIDEO", "Zoom / Google Meet", "Video counselling (4.19)", State.NOT_CONNECTED,
                        "Video sessions get a free Jitsi Meet link, or staff paste their own Zoom/Meet link.",
                        "A Zoom or Google Workspace account to create meetings and fetch recordings automatically."),
                new Integration("ASSISTANT_AI", "AI model for the assistant", "Website assistant (4.17)",
                        State.NOT_CONNECTED,
                        "The assistant answers only from the FAQ entries you write, matched by keywords.",
                        "An Anthropic API key, plus a WhatsApp provider to run the same assistant on WhatsApp."),
                new Integration("VOICE_AGENT", "AI voice calling agent", "AI voice agent (4.29)",
                        voice.live() ? State.CONNECTED : State.NOT_CONNECTED,
                        "Consent, campaigns, scripts, the callback queue and the test console all work, but no "
                                + "phone rings: campaign calls are recorded as simulated.",
                        "A voice-AI platform (e.g. Retell, Vapi, Bolna) with an Anthropic API key, and an Indian "
                                + "telephony provider (Exotel or Knowlarity) with DLT registration."),
                new Integration("LOANS", "Loan partner systems", "Education loans (4.26)", State.NOT_CONNECTED,
                        "Applications are tracked here and updated by the loan desk after talking to the lender.",
                        "API access agreed with each lender."),
                new Integration("PAYMENTS", "Online payment gateway", "Fees (4.8)", State.NOT_CONNECTED,
                        "Payments are recorded by the accountant after the money is received.",
                        "A Razorpay (or similar) merchant account."));
    }
}
