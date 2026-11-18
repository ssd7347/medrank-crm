package com.aicallcrm.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.aicallcrm.crm.voice.ConversationBrain.Kind;
import com.aicallcrm.crm.voice.ReadinessService.Check;
import com.aicallcrm.crm.voice.ReadinessService.Facts;
import com.aicallcrm.crm.voice.ReadinessService.State;
import com.aicallcrm.crm.voice.SpeechService.SpeechStatus;

/** The rules of "Ready for a real call?", one thing wrong at a time. */
class ReadinessJudgeTest {

    /** A first-call setup with everything right; each test changes one thing. */
    static class F {
        String platform = "plivo";
        List<String> missing = new ArrayList<>();
        String from = "+918000000001";
        String apiBase = "https://api.plivo.com/v1/";
        Integer account = 200;
        String pub = "https://calls.example.in";
        String engine = "wss://calls.example.in/call";
        Integer me = 404;
        Integer hook = 401;
        boolean sms = false;
        boolean onScreen = true;
        boolean signing = true;
        SpeechStatus speech = new SpeechStatus(true, "Ready", "small.en", "en_US-lessac-medium", true, null);
        Kind brain = Kind.LOCAL;
        Brains.BrainOption option = new Brains.BrainOption(Kind.LOCAL, "Our own model (ourvoice8b-r4)", true, "Free");
        boolean open = true;
        boolean enabled = true;
        boolean paused = false;
        String desk = "+914400000000";
        int approved = 1;
        int recorded = 1;

        Facts build() {
            return new Facts(platform, missing, from, apiBase, account, pub, engine, me, hook, sms, onScreen, signing,
                    speech, brain, option, open, LocalTime.of(9, 0), LocalTime.of(18, 0), enabled, paused, desk,
                    approved, recorded);
        }
    }

    static Map<String, Check> judge(Consumer<F> change) {
        F f = new F();
        change.accept(f);
        return ReadinessService.judge(f.build()).stream().collect(Collectors.toMap(Check::key, c -> c));
    }

    static void only(Map<String, Check> checks, String key, State state, String says) {
        assertThat(checks.get(key).state()).as(key).isEqualTo(state);
        assertThat(checks.get(key).detail()).as(key).contains(says);
        checks.forEach((k, c) -> {
            if (!k.equals(key)) {
                assertThat(c.state()).as(k + ": " + c.detail()).isEqualTo(State.OK);
            }
        });
    }

    @Test
    void theFirstCallSetupIsAllGreen() {
        Map<String, Check> checks = judge(f -> { });
        assertThat(checks.keySet()).containsExactlyInAnyOrder("LINE", "PLIVO_SETTINGS", "PLIVO_API", "PLIVO_KEYS",
                "PUBLIC", "REACH", "CALLBACKS", "SECRET", "ENGINE", "BRAIN", "HOURS", "PAUSE", "DESK", "SCRIPT");
        assertThat(checks.values()).allSatisfy(c -> assertThat(c.state()).as(c.key()).isEqualTo(State.OK));
    }

    @Test
    void simulatedCallsSkipThePlivoChecks() {
        Map<String, Check> checks = judge(f -> f.platform = "simulated");
        assertThat(checks).doesNotContainKeys("PLIVO_SETTINGS", "PLIVO_API", "PLIVO_KEYS", "PUBLIC", "REACH",
                "CALLBACKS");
        only(checks, "LINE", State.FAIL, "VOICE_PLATFORM=plivo");
    }

    @Test
    void plivoSettingsAndKeys() {
        Map<String, Check> missing = judge(f -> f.missing.addAll(List.of("PLIVO_AUTH_TOKEN", "PLIVO_FROM_NUMBER")));
        assertThat(missing.get("PLIVO_SETTINGS").state()).isEqualTo(State.FAIL);
        assertThat(missing.get("PLIVO_SETTINGS").detail()).contains("PLIVO_AUTH_TOKEN, PLIVO_FROM_NUMBER");
        assertThat(missing.get("PLIVO_KEYS").state()).isEqualTo(State.FAIL);

        only(judge(f -> f.from = "08000000001"), "PLIVO_SETTINGS", State.FAIL, "+91");
        only(judge(f -> f.account = 401), "PLIVO_KEYS", State.FAIL, "Plivo answered 401");
        only(judge(f -> f.account = null), "PLIVO_KEYS", State.FAIL, "could not be reached");
    }

    @Test
    void aRehearsalAddressLeftInIsCaught() {
        only(judge(f -> f.apiBase = "http://127.0.0.1:8099/v1"), "PLIVO_API", State.FAIL, "Remove PLIVO_API_BASE");
    }

    @Test
    void thePublicAddress() {
        only(judge(f -> f.pub = "http://calls.example.in"), "PUBLIC", State.FAIL, "https://");
        only(judge(f -> f.engine = "https://calls.example.in/call"), "PUBLIC", State.FAIL, "wss://");
        only(judge(f -> f.engine = "wss://other.example.in/call"), "PUBLIC", State.FAIL, "different places");
    }

    @Test
    void whatTheInternetReaches() {
        // The CRM itself is public while codes are shown on screen: anyone could sign in.
        only(judge(f -> f.me = 401), "REACH", State.FAIL, "anyone who knows a staff number");
        only(judge(f -> { f.me = 401; f.sms = true; f.onScreen = true; }), "REACH", State.FAIL, "Point the tunnel");
        // Hosted with codes by SMS: fine.
        only(judge(f -> { f.me = 401; f.sms = true; f.onScreen = false; }), "REACH", State.OK, "go by SMS");
        only(judge(f -> f.me = 502), "REACH", State.FAIL, "answered 502");
        Map<String, Check> down = judge(f -> { f.me = null; f.hook = null; });
        assertThat(down.get("REACH").state()).isEqualTo(State.FAIL);
        assertThat(down.get("CALLBACKS").state()).isEqualTo(State.FAIL);
        only(judge(f -> f.hook = 502), "CALLBACKS", State.FAIL, "CRM_URL");
        only(judge(f -> f.hook = 404), "CALLBACKS", State.FAIL, "instead of this CRM's refusal");
    }

    @Test
    void theSpeechEngineAndTheBrain() {
        only(judge(f -> f.signing = false), "SECRET", State.FAIL, "VOICE_HMAC_SECRET");
        only(judge(f -> f.speech = new SpeechStatus(false, "The speech engine is not running at x.", null, null, null,
                null)), "ENGINE", State.FAIL, "not running");
        only(judge(f -> f.speech = new SpeechStatus(true, "Ready", "small.en", "v", false, null)), "ENGINE",
                State.FAIL, "still loading");
        only(judge(f -> f.speech = new SpeechStatus(true, "Ready", "small.en", "v", false, "no CUDA")), "ENGINE",
                State.FAIL, "no CUDA");
        // An engine too old to say whether it warmed up is not taken as ready.
        only(judge(f -> f.speech = new SpeechStatus(true, "Ready", "small.en", "v", null, null)), "ENGINE",
                State.FAIL, "still loading");
        only(judge(f -> f.brain = Kind.SIMULATED), "BRAIN", State.FAIL, "cannot hold a real call");
        only(judge(f -> f.option = new Brains.BrainOption(Kind.LOCAL, "Our own model (x)", false,
                "No model server at http://localhost:11434. Start Ollama.")), "BRAIN", State.FAIL, "Start Ollama");
    }

    @Test
    void hoursPauseDeskAndScripts() {
        only(judge(f -> f.open = false), "HOURS", State.WARN, "wait until 09:00");
        only(judge(f -> f.paused = true), "PAUSE", State.FAIL, "Pause all");
        only(judge(f -> f.enabled = false), "PAUSE", State.FAIL, "VOICE_ENABLED is false");
        only(judge(f -> f.desk = ""), "DESK", State.WARN, "call-back task");
        only(judge(f -> { f.approved = 0; f.recorded = 0; }), "SCRIPT", State.FAIL, "No approved script");
        only(judge(f -> f.recorded = 0), "SCRIPT", State.WARN, "no call will be recorded");
    }
}
