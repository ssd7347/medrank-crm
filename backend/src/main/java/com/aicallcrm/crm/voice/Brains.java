package com.aicallcrm.crm.voice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.voice.ConversationBrain.Kind;

/**
 * Picks the brain for each call (the rule-based stand-in, an open model on our own machine, or Claude) and
 * remembers which one a call is using. Also tells the screens which brains are ready to use right now.
 */
@Component
public class Brains {

    public record BrainOption(Kind kind, String label, boolean available, String detail) {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1500)).build();

    private final SimulatedBrain simulated;
    private final LlmBrain llm;
    private final AiProperties props;
    private final Map<UUID, ConversationBrain> byCall = new ConcurrentHashMap<>();

    public Brains(SimulatedBrain simulated, LlmBrain llm, AiProperties props) {
        this.simulated = simulated;
        this.llm = llm;
        this.props = props;
    }

    /** The configured default (app.voice.ai.brain). */
    public Kind defaultKind() {
        try {
            return Kind.valueOf(props.brain().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Kind.SIMULATED;
        }
    }

    /** Starts a call on the given brain (or the default); refuses one that is not set up. */
    ConversationBrain begin(UUID callId, Kind requested) {
        Kind kind = requested == null ? defaultKind() : requested;
        if (kind == Kind.CLAUDE && !props.claudeConfigured()) {
            throw ApiException.badRequest("The Claude brain needs an Anthropic API key (ANTHROPIC_API_KEY)");
        }
        ConversationBrain brain = kind == Kind.SIMULATED ? simulated : llm.as(kind);
        byCall.put(callId, brain);
        return brain;
    }

    /** The brain a call started with; calls from before a restart fall back to the stand-in. */
    ConversationBrain of(UUID callId) {
        return byCall.getOrDefault(callId, simulated);
    }

    void finished(UUID callId) {
        ConversationBrain b = byCall.remove(callId);
        (b == null ? simulated : b).forget(callId);
    }

    public List<BrainOption> options() {
        return List.of(option(Kind.SIMULATED), option(Kind.LOCAL), option(Kind.CLAUDE));
    }

    /** One brain's option; only the open-model server is asked over the network. */
    public BrainOption option(Kind kind) {
        return switch (kind) {
            case SIMULATED -> new BrainOption(Kind.SIMULATED, "Simulated (rules, no AI)", true,
                    "Free and instant; follows keywords only.");
            case LOCAL -> local();
            case CLAUDE -> new BrainOption(Kind.CLAUDE, "Claude (" + props.anthropicModel() + ")",
                    props.claudeConfigured(), props.claudeConfigured() ? "Paid per use; fast and the most capable."
                            : "Add an Anthropic API key (ANTHROPIC_API_KEY) to use it.");
        };
    }

    /** Is the open-model server up, and does it have the model? Asked live, so the screen shows the truth. */
    private BrainOption local() {
        String label = "Our own model (" + props.localModel() + ")";
        try {
            HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(props.localUrl() + "/v1/models"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return new BrainOption(Kind.LOCAL, label, false, "The model server answered " + res.statusCode() + ".");
            }
            boolean hasModel = res.body() != null && res.body().contains("\"" + props.localModel());
            return new BrainOption(Kind.LOCAL, label, hasModel, hasModel ? "Free; runs on this machine."
                    : "The model server is running but " + props.localModel() + " is not downloaded yet.");
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new BrainOption(Kind.LOCAL, label, false, "No model server at " + props.localUrl() + ". Start Ollama.");
        }
    }
}
