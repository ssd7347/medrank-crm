package com.mbbscrm.crm.assistant;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Small fixed-window limiter for the public assistant endpoints. In-memory is enough for one backend
 * instance; move to Redis if the backend is scaled out.
 */
@Component
public class RateLimiter {

    private record Window(Instant start, int count) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /** Counts one use of {@code key}; false when more than {@code max} have happened within {@code period}. */
    public boolean allow(String key, int max, Duration period) {
        Instant now = Instant.now();
        if (windows.size() > 50_000) {
            windows.entrySet().removeIf(e -> e.getValue().start().plus(Duration.ofHours(24)).isBefore(now));
        }
        Window w = windows.compute(key, (k, old) -> old == null || old.start().plus(period).isBefore(now)
                ? new Window(now, 1) : new Window(old.start(), old.count() + 1));
        return w.count() <= max;
    }
}
