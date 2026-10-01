package com.mbbscrm.crm.voice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.voice.Voice.DndResult;

/**
 * Do-not-disturb scrubbing with a cache (spec 18.10.1). A result is reused until it is older than
 * {@code app.voice.dnd-ttl-hours}; BLOCKED numbers are never dialled.
 */
@Service
public class DndService {

    /** Used while no scrubbing provider is connected: it cannot tell, and says so. */
    @Component
    static class NotConnected implements DndProvider {
        @Override
        public DndResult check(String phone) {
            return DndResult.UNKNOWN;
        }
    }

    private final DndCheckRepository checks;
    private final DndProvider provider;
    private final Duration ttl;

    public DndService(DndCheckRepository checks, List<DndProvider> providers, VoiceProperties props) {
        this.checks = checks;
        this.provider = providers.stream().filter(p -> !(p instanceof NotConnected)).findFirst()
                .orElseGet(() -> providers.get(0));
        this.ttl = Duration.ofHours(props.dndTtlHours());
    }

    public boolean connected() {
        return !(provider instanceof NotConnected);
    }

    @Transactional
    public DndResult check(String phone) {
        String hash = sha256(phone);
        Instant now = Instant.now();
        DndCheck cached = checks.findFirstByPhoneHashOrderByCheckedAtDescIdDesc(hash).orElse(null);
        if (cached != null && cached.getExpiresAt().isAfter(now)) {
            return cached.getResult();
        }
        DndResult result = provider.check(phone);
        checks.save(new DndCheck(hash, result, now.plus(ttl)));
        return result;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
