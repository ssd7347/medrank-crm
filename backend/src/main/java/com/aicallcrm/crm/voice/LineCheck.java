package com.aicallcrm.crm.voice;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.aicallcrm.crm.voice.Brains.BrainOption;
import com.aicallcrm.crm.voice.ConversationBrain.Kind;
import com.aicallcrm.crm.voice.SpeechService.SpeechStatus;

/**
 * Before a campaign rings someone on a real line: are the parts the call needs answering? A call placed while one is
 * down reaches a real person and fails in front of them. With the speech engine or the tunnel down they answer to
 * silence. With the brain down every person answered hears "a member of our team will call you back". A pilot list
 * would be used up that way. So {@link CampaignDispatcher} asks here first, at most once a sweep and only when it is
 * about to place a call (so nothing is checked outside calling hours, when the GPU computer may be off). While a part
 * is down the calls are held, the people stay due, and the calls go ahead by themselves once it answers again.
 *
 * <p>Asked: the speech engine's {@code /health} (models loaded), the configured brain (the open-model server has the
 * model; Claude has its key), and the public address as Plivo would reach it (an unsigned callback must get this
 * CRM's 401 through the tunnel and the gateway; see {@link ReadinessService}). Simulated calls ring nobody, so nothing
 * is held for them. Incoming calls are not affected.
 */
@Component
public class LineCheck {

    /** Campaign calls are held: why, since when, and when the line was last asked. */
    public record Hold(String reason, Instant since, Instant checkedAt) {
    }

    private final VoicePlatforms platforms;
    private final SpeechService speech;
    private final Brains brains;
    private final PlivoProperties plivo;
    private final ReadinessService.Probe probe;

    private volatile Hold hold;

    public LineCheck(VoicePlatforms platforms, SpeechService speech, Brains brains, PlivoProperties plivo,
                     ReadinessService.Probe probe) {
        this.platforms = platforms;
        this.speech = speech;
        this.brains = brains;
        this.plivo = plivo;
        this.probe = probe;
    }

    /** Null when a call can go ahead; otherwise what is down, in words for the admin. */
    String problem() {
        if (!"plivo".equalsIgnoreCase(platforms.current().name())) {
            return null;
        }
        SpeechStatus s = speech.status();
        if (!s.available()) {
            return s.detail();
        }
        if (s.warmUpError() != null) {
            return "The speech engine's speech models failed to load: " + s.warmUpError();
        }
        if (!Boolean.TRUE.equals(s.warmed())) {
            return "The speech engine is still loading its speech models.";
        }
        Kind kind = brains.defaultKind();
        if (kind != Kind.SIMULATED) {
            BrainOption brain = brains.option(kind);
            if (!brain.available()) {
                return "The AI brain is not available: " + brain.detail();
            }
        }
        Integer hook = probe.status("POST", plivo.url("/api/voice/hooks/plivo/hangup"));
        String address = plivo.url("");
        if (hook == null) {
            return "Nothing answers at the public address " + address + ": is the tunnel, the hosting or the gateway down?";
        }
        if (hook != 401) {
            return "Plivo's callbacks would not reach the CRM: a test callback through " + address + " got " + hook
                    + " instead of the CRM's 401. Check the gateway.";
        }
        return null;
    }

    /** The current hold, or null when campaign calls are not held. */
    public Hold hold() {
        return hold;
    }

    /** Notes what a check found: +1 when a hold starts, -1 when it ends, 0 when nothing changed. */
    synchronized int record(String problem, Instant at) {
        Hold was = hold;
        if (problem == null) {
            hold = null;
            return was == null ? 0 : -1;
        }
        hold = new Hold(problem, was == null ? at : was.since(), at);
        return was == null ? 1 : 0;
    }
}
