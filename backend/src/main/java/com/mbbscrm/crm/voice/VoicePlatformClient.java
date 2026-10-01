package com.mbbscrm.crm.voice;

import java.util.Map;
import java.util.UUID;

import com.mbbscrm.crm.common.Language;
import com.mbbscrm.crm.voice.Voice.Purpose;

/**
 * The one place that talks to a voice-AI platform (Retell, Vapi, Bland, Bolna, ...) and, through it, to the
 * telephony provider (spec 18.4). To connect a provider, add a bean implementing this interface and set
 * {@code VOICE_PLATFORM} to its {@link #name()}; nothing else in the CRM changes. See docs/voice-agent.md.
 */
public interface VoicePlatformClient {

    /** Everything the platform needs to place one call. Only what the call purpose needs is sent. */
    record OutboundCall(UUID callId, String phoneE164, Purpose purpose, Language language, String model,
                        String systemPrompt, String openingLine, Map<String, String> dynamicVariables,
                        boolean recording, int maxDurationSec) {
    }

    /** {@code accepted} false means the platform refused or could not be reached; no attempt is counted. */
    record Placed(boolean accepted, String providerCallId, String error) {
        public static Placed ok(String providerCallId) {
            return new Placed(true, providerCallId, null);
        }

        public static Placed failed(String error) {
            return new Placed(false, null, error);
        }
    }

    /** Matches {@code app.voice.platform}. */
    String name();

    /** False only for the built-in simulator, which never rings a phone. */
    default boolean live() {
        return true;
    }

    Placed createOutboundCall(OutboundCall call);

    /** Warm-transfers a connected call to the counsellor desk. False: fall back to a callback task. */
    boolean transfer(String providerCallId, String deskNumberE164, String summary);
}
