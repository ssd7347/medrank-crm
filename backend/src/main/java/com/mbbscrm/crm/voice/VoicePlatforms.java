package com.mbbscrm.crm.voice;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Picks the voice platform named by {@code app.voice.platform}, falling back to the simulator. */
@Component
public class VoicePlatforms {

    private static final Logger log = LoggerFactory.getLogger(VoicePlatforms.class);

    /**
     * Stands in while no provider is connected. It places no call; the CRM records what it would have
     * dialled and marks it SIMULATED, exactly as WhatsApp messages are handled without a provider.
     */
    @Component
    static class Simulated implements VoicePlatformClient {
        @Override
        public String name() {
            return "simulated";
        }

        @Override
        public boolean live() {
            return false;
        }

        @Override
        public Placed createOutboundCall(OutboundCall call) {
            return Placed.failed("No voice platform is connected");
        }

        @Override
        public boolean transfer(String providerCallId, String deskNumberE164, String summary) {
            return false;
        }
    }

    private final VoicePlatformClient current;

    public VoicePlatforms(List<VoicePlatformClient> clients, VoiceProperties props) {
        VoicePlatformClient simulated = clients.stream().filter(c -> !c.live()).findFirst().orElseThrow();
        this.current = clients.stream().filter(c -> c.name().equalsIgnoreCase(props.platform())).findFirst()
                .orElseGet(() -> {
                    log.warn("No voice platform adapter named '{}' is installed; calls stay simulated",
                            props.platform());
                    return simulated;
                });
        log.info("AI voice calls will use the '{}' platform", current.name());
    }

    public VoicePlatformClient current() {
        return current;
    }

    public boolean live() {
        return current.live();
    }
}
