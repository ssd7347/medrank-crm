package com.aicallcrm.crm.voice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aicallcrm.crm.security.OtpService;
import com.aicallcrm.crm.voice.ConversationBrain.Kind;
import com.aicallcrm.crm.voice.SpeechService.SpeechStatus;

/**
 * "Ready for a real call?": the checks of docs/first-real-call.md, done by the CRM itself on request instead of by hand
 * on the day. It reads the settings, asks Plivo for this account (a read, nothing changes), asks the public address
 * what the internet can reach, and asks the speech engine and the brain whether they are ready. Nothing here places a
 * call or changes anything. {@link #judge} turns those facts into the list; it is pure, so every rule is tested.
 */
@Service
public class ReadinessService {

    public enum State {
        OK, WARN, FAIL
    }

    public record Check(String key, String title, State state, String detail) {
    }

    public record Readiness(boolean ready, Instant checkedAt, List<Check> checks) {
    }

    /** What the checks found; null for a probe that was not made. */
    record Facts(String platform, List<String> plivoMissing, String fromNumber, String apiBase, Integer accountStatus,
                 String publicUrl, String engineUrl, Integer meStatus, Integer hookStatus, boolean codesBySms,
                 boolean codesOnScreen, boolean signing, SpeechStatus speech, Kind brain, Brains.BrainOption brainOption,
                 boolean windowOpen, LocalTime windowStart, LocalTime windowEnd, boolean enabled, boolean paused,
                 String deskNumber,
                 int approvedScripts, int approvedRecorded) {
    }

    static final String PLIVO_API = "https://api.plivo.com/v1";

    private final Probe probe;
    private final VoicePlatforms platforms;
    private final PlivoProperties plivo;
    private final PlivoClient plivoClient;
    private final OtpService otp;
    private final VoiceProperties voice;
    private final SpeechService speech;
    private final Brains brains;
    private final VoiceCallService calls;
    private final VoiceScriptRepository scripts;
    private final Clock clock;

    ReadinessService(Probe probe, VoicePlatforms platforms, PlivoProperties plivo, PlivoClient plivoClient,
                     OtpService otp, VoiceProperties voice, SpeechService speech, Brains brains, VoiceCallService calls,
                     VoiceScriptRepository scripts, Clock clock) {
        this.probe = probe;
        this.platforms = platforms;
        this.plivo = plivo;
        this.plivoClient = plivoClient;
        this.otp = otp;
        this.voice = voice;
        this.speech = speech;
        this.brains = brains;
        this.calls = calls;
        this.scripts = scripts;
        this.clock = clock;
    }

    public Readiness check() {
        boolean isPlivo = "plivo".equalsIgnoreCase(platforms.current().name());
        List<String> missing = new ArrayList<>();
        if (plivo.authId().isBlank()) {
            missing.add("PLIVO_AUTH_ID");
        }
        if (plivo.authToken().isBlank()) {
            missing.add("PLIVO_AUTH_TOKEN");
        }
        if (plivo.fromNumber().isBlank()) {
            missing.add("PLIVO_FROM_NUMBER");
        }
        if (plivo.publicUrl().isBlank()) {
            missing.add("PLIVO_PUBLIC_URL");
        }
        if (plivo.engineUrl().isBlank()) {
            missing.add("PLIVO_ENGINE_URL");
        }
        Integer account = isPlivo && missing.isEmpty() ? plivoClient.accountStatus() : null;
        boolean publicSet = isPlivo && plivo.publicUrl().startsWith("https://");
        Integer me = publicSet ? probe.status("GET", plivo.url("/api/auth/me")) : null;
        Integer hook = publicSet ? probe.status("POST", plivo.url("/api/voice/hooks/plivo/hangup")) : null;
        Kind brain = brains.defaultKind();
        Brains.BrainOption option = brains.options().stream().filter(o -> o.kind() == brain).findFirst().orElse(null);
        List<VoiceScript> usable = scripts.findAll().stream().filter(VoiceScript::usable).toList();
        int recorded = (int) usable.stream().filter(s -> RecordingService.announces(s.getOpeningLine())).count();
        Facts f = new Facts(platforms.current().name(), missing, plivo.fromNumber(), plivo.apiBase(), account,
                plivo.publicUrl(), plivo.engineUrl(), me, hook, otp.sendsCodes(), otp.showsOnScreen(),
                voice.signingConfigured(), speech.status(), brain, option,
                CallEligibility.inWindow(voice.windowStart(), voice.windowEnd(), Instant.now(clock)),
                voice.windowStart(), voice.windowEnd(), voice.enabled(), calls.paused(), voice.deskTransferNumber(),
                usable.size(),
                recorded);
        List<Check> checks = judge(f);
        return new Readiness(checks.stream().noneMatch(c -> c.state() == State.FAIL), Instant.now(clock), checks);
    }

    /** Asks an address from outside, as Plivo or a stranger would: no sign-in, no body, redirects not followed. */
    @Component
    static class Probe {

        private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        /** The HTTP status the address answered, or null if it could not be reached. */
        Integer status(String method, String url) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
                        .method(method, HttpRequest.BodyPublishers.noBody()).build();
                return HTTP.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (java.io.IOException | IllegalArgumentException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    static List<Check> judge(Facts f) {
        List<Check> out = new ArrayList<>();
        boolean isPlivo = "plivo".equalsIgnoreCase(f.platform());
        out.add(isPlivo ? ok("LINE", "Phone line", "Plivo.")
                : fail("LINE", "Phone line", "Calls are simulated (VOICE_PLATFORM=" + f.platform() + "): no phone rings. "
                        + "Set VOICE_PLATFORM=plivo."));
        if (isPlivo) {
            out.add(plivoSettings(f));
            out.add(trimSlash(f.apiBase()).equals(PLIVO_API) ? ok("PLIVO_API", "Plivo's address", PLIVO_API + ".")
                    : fail("PLIVO_API", "Plivo's address", "PLIVO_API_BASE is " + f.apiBase() + ", which is only for a "
                            + "rehearsal: no real call, transfer or hang-up reaches Plivo. Remove PLIVO_API_BASE."));
            out.add(account(f));
            out.add(publicAddress(f));
            out.add(reach(f));
            out.add(callbacks(f));
        }
        out.add(f.signing() ? ok("SECRET", "CRM and speech engine secret", "VOICE_HMAC_SECRET is set.")
                : fail("SECRET", "CRM and speech engine secret", "VOICE_HMAC_SECRET is missing or shorter than 32 "
                        + "characters: the speech engine's requests are refused. Use the same value on both."));
        out.add(engine(f.speech()));
        out.add(brain(f));
        String window = f.windowStart() + " to " + f.windowEnd() + " Indian time";
        out.add(f.windowOpen() ? ok("HOURS", "Calling hours", "Open now (" + window + ").")
                : warn("HOURS", "Calling hours", "Closed now (" + window + "): campaign calls wait until "
                        + f.windowStart() + "."));
        if (!f.enabled()) {
            out.add(fail("PAUSE", "Pause all", "VOICE_ENABLED is false: AI calling is switched off, so no AI call is "
                    + "placed. Remove VOICE_ENABLED or set it to true."));
        } else {
            out.add(f.paused() ? fail("PAUSE", "Pause all", "\"Pause all\" is on: no AI call is placed. Turn it off on "
                    + "the AI voice agent page.") : ok("PAUSE", "Pause all", "Off."));
        }
        out.add(f.deskNumber() == null || f.deskNumber().isBlank()
                ? warn("DESK", "Staff desk", "No desk number (VOICE_DESK_NUMBER): a caller who asks for a person gets a "
                        + "call-back task instead of a transfer.")
                : ok("DESK", "Staff desk", "Transfers go to " + f.deskNumber() + "."));
        if (f.approvedScripts() == 0) {
            out.add(fail("SCRIPT", "Approved script", "No approved script: a campaign cannot start. Approve one on the "
                    + "Scripts page."));
        } else if (f.approvedRecorded() == 0) {
            out.add(warn("SCRIPT", "Approved script", f.approvedScripts() + " approved, but no opening says the call may "
                    + "be recorded, so no call will be recorded."));
        } else {
            out.add(ok("SCRIPT", "Approved script", f.approvedScripts() + " approved; " + f.approvedRecorded()
                    + " say the call may be recorded."));
        }
        return out;
    }

    private static Check plivoSettings(Facts f) {
        if (!f.plivoMissing().isEmpty()) {
            return fail("PLIVO_SETTINGS", "Plivo settings", "Missing: " + String.join(", ", f.plivoMissing()) + ".");
        }
        if (!f.fromNumber().matches("\\+[1-9][0-9]{7,14}")) {
            return fail("PLIVO_SETTINGS", "Plivo settings", "PLIVO_FROM_NUMBER must be the rented number with its "
                    + "country code, like +91XXXXXXXXXX.");
        }
        return ok("PLIVO_SETTINGS", "Plivo settings", "Filled in; calls come from " + f.fromNumber() + ".");
    }

    private static Check account(Facts f) {
        if (!f.plivoMissing().isEmpty()) {
            return fail("PLIVO_KEYS", "Plivo account", "Not checked: the Plivo settings are not filled in.");
        }
        if (f.accountStatus() == null) {
            return fail("PLIVO_KEYS", "Plivo account", "Plivo could not be reached at " + f.apiBase() + ".");
        }
        if (f.accountStatus() / 100 == 2) {
            return ok("PLIVO_KEYS", "Plivo account", "Plivo accepted PLIVO_AUTH_ID and PLIVO_AUTH_TOKEN.");
        }
        return fail("PLIVO_KEYS", "Plivo account", "Plivo answered " + f.accountStatus() + ": check PLIVO_AUTH_ID and "
                + "PLIVO_AUTH_TOKEN.");
    }

    private static Check publicAddress(Facts f) {
        String pub = f.publicUrl() == null ? "" : f.publicUrl();
        String eng = f.engineUrl() == null ? "" : f.engineUrl();
        if (!pub.startsWith("https://")) {
            return fail("PUBLIC", "Public address", "PLIVO_PUBLIC_URL must be an https:// address Plivo can reach.");
        }
        if (!eng.startsWith("wss://") || !trimSlash(eng).endsWith("/call")) {
            return fail("PUBLIC", "Public address", "PLIVO_ENGINE_URL must be the call stream, wss://.../call.");
        }
        if (!host(pub).equals(host(eng))) {
            return fail("PUBLIC", "Public address", "PLIVO_PUBLIC_URL (" + host(pub) + ") and PLIVO_ENGINE_URL ("
                    + host(eng) + ") point at different places; in the first-call layout both are the gateway.");
        }
        return ok("PUBLIC", "Public address", pub + " and " + eng + ".");
    }

    private static Check reach(Facts f) {
        Integer s = f.meStatus();
        if (s == null) {
            return fail("REACH", "What the internet reaches", "Nothing answered at " + f.publicUrl() + ": is the tunnel "
                    + "or the hosting up?");
        }
        if (s == 404) {
            return ok("REACH", "What the internet reaches", "Only the gateway: sign-in and the CRM's own pages are not "
                    + "on the internet.");
        }
        if (s == 401) {
            return f.codesBySms() && !f.codesOnScreen()
                    ? ok("REACH", "What the internet reaches", "The CRM itself; sign-in codes go by SMS and are not "
                            + "shown on the login page.")
                    : fail("REACH", "What the internet reaches", "The CRM itself is on the internet while sign-in codes "
                            + "are not sent by SMS (or are shown on the login page): anyone who knows a staff number "
                            + "could sign in. Point the tunnel at the gateway (voice-engine/gateway.py) instead.");
        }
        return fail("REACH", "What the internet reaches", f.publicUrl() + " answered " + s + " where the gateway "
                + "answers 404 and the CRM 401.");
    }

    private static Check callbacks(Facts f) {
        Integer s = f.hookStatus();
        if (s == null) {
            return fail("CALLBACKS", "Plivo's callbacks", "Nothing answered at " + f.publicUrl() + ".");
        }
        if (s == 401) {
            return ok("CALLBACKS", "Plivo's callbacks", "They reach this CRM, which refuses unsigned ones.");
        }
        return fail("CALLBACKS", "Plivo's callbacks", "A test callback to " + trimSlash(f.publicUrl())
                + "/api/voice/hooks/plivo/hangup got " + s + " instead of this CRM's refusal (401): Plivo's calls "
                + "would not reach the CRM. Check the gateway's CRM_URL.");
    }

    private static Check engine(SpeechStatus s) {
        if (s == null || !s.available()) {
            return fail("ENGINE", "Speech engine", s == null ? "Not checked." : s.detail());
        }
        if (s.warmUpError() != null) {
            return fail("ENGINE", "Speech engine", "Its speech models failed to load: " + s.warmUpError());
        }
        if (!Boolean.TRUE.equals(s.warmed())) {
            return fail("ENGINE", "Speech engine", "Running, but its speech models are still loading. Check again in "
                    + "a minute.");
        }
        return ok("ENGINE", "Speech engine", "Ready: ear " + s.sttModel() + ", voice " + s.voice() + ".");
    }

    private static Check brain(Facts f) {
        if (f.brain() == Kind.SIMULATED) {
            return fail("BRAIN", "The brain", "AI_BRAIN is SIMULATED: the rule-based stand-in cannot hold a real call. "
                    + "Set AI_BRAIN=LOCAL (our own model) or CLAUDE.");
        }
        if (f.brainOption() == null || !f.brainOption().available()) {
            return fail("BRAIN", "The brain", (f.brainOption() == null ? f.brain().name() : f.brainOption().label())
                    + ": " + (f.brainOption() == null ? "not set up." : f.brainOption().detail()));
        }
        return ok("BRAIN", "The brain", f.brainOption().label() + " is answering.");
    }

    private static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static Check ok(String key, String title, String detail) {
        return new Check(key, title, State.OK, detail);
    }

    private static Check warn(String key, String title, String detail) {
        return new Check(key, title, State.WARN, detail);
    }

    private static Check fail(String key, String title, String detail) {
        return new Check(key, title, State.FAIL, detail);
    }

    @RestController
    static class ReadinessController {

        private final ReadinessService readiness;

        ReadinessController(ReadinessService readiness) {
            this.readiness = readiness;
        }

        @GetMapping("/api/voice/readiness")
        @PreAuthorize("hasRole('SUPER_ADMIN')")
        public Readiness readiness() {
            return readiness.check();
        }
    }
}
