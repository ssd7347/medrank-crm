package com.aicallcrm.crm.voice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.aicallcrm.crm.contact.ContactRepository;
import com.aicallcrm.crm.voice.LlmClient.Message;
import com.aicallcrm.crm.voice.LlmClient.Reply;
import com.aicallcrm.crm.voice.LlmClient.ToolCall;
import com.aicallcrm.crm.voice.LlmClient.ToolSpec;
import com.aicallcrm.crm.voice.VoiceCallService.Line;

import tools.jackson.databind.ObjectMapper;

/**
 * A real language model holding the conversation: the approved script is its system prompt, and every fact it
 * may say comes from the CRM's tools, run through {@link ToolExecutor} so the verification rule, the audit trail
 * and the hand-overs apply exactly as for any platform. Which model it is (an open model on our own machine or
 * Claude) is chosen per call.
 */
@Component
public class LlmBrain {

    private static final Logger log = LoggerFactory.getLogger(LlmBrain.class);
    /** Tool rounds the model may take before it must answer; more than this and a person takes over. */
    static final int MAX_ROUNDS = 6;
    /** Tools that wrap a call up: once one succeeds, the next thing the agent says is its goodbye. */
    static final Set<String> CLOSING_TOOLS = Set.of("log_call_outcome", "opt_out", "request_human_handoff");
    /** Not offered to the model: its answer is already in the prompt, so asking for it again only wastes a turn. */
    static final Set<String> NOT_OFFERED = Set.of("get_call_context");
    /** A caller cannot follow more than this in one go; a longer reply is cut down before it is spoken. */
    static final int MAX_SENTENCES = 3;
    /** The space after a sentence; "Dr. Rao" and the like are not sentence ends. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<!\\b(?:Dr|Mr|Mrs|Ms|St)\\.)(?<=[.!?])\\s+(?=\\S)");
    private static final Pattern GOODBYE = Pattern.compile("\\b(good\\s?bye|bye)\\b", Pattern.CASE_INSENSITIVE);

    /** Added to every script: the agent is being heard, not read. */
    static final String VOICE_RULES = """

            LIVE PHONE CALL
            - What you write is spoken aloud by a text-to-speech voice. Reply in one to three short sentences of plain spoken English: no lists, no markdown, no symbols, no emojis.
            - You have already said the opening line. Continue from the caller's reply.
            - Use the tools for every fact. Never make up amounts, dates, names or policies.
            - When the call is finished, call log_call_outcome (or opt_out or request_human_handoff when those apply), then say one short goodbye.
            """;

    private static final class Session {
        final ConversationBrain.Kind kind;
        final LlmClient client;
        final String system;
        final List<Message> history = new ArrayList<>();
        boolean closing;
        boolean over;

        Session(ConversationBrain.Kind kind, LlmClient client, String system) {
            this.kind = kind;
            this.client = client;
            this.system = system;
        }
    }

    private final ToolExecutor executor;
    private final VoiceTools tools;
    private final VoiceCallRepository calls;
    private final VoiceScriptRepository scripts;
    private final VoiceCallService callService;
    private final ContactRepository contacts;
    private final AiProperties props;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public LlmBrain(ToolExecutor executor, VoiceTools tools, VoiceCallRepository calls, VoiceScriptRepository scripts,
                    VoiceCallService callService, ContactRepository contacts, AiProperties props, ObjectMapper mapper,
                    TransactionTemplate tx) {
        this.executor = executor;
        this.tools = tools;
        this.calls = calls;
        this.scripts = scripts;
        this.callService = callService;
        this.contacts = contacts;
        this.props = props;
        this.mapper = mapper;
        this.tx = tx;
    }

    /** A {@link ConversationBrain} that uses this model kind (LOCAL or CLAUDE) for every call it opens. */
    ConversationBrain as(ConversationBrain.Kind kind) {
        return new ConversationBrain() {
            @Override
            public List<Line> open(UUID callId, String openingLine) {
                return LlmBrain.this.open(callId, openingLine, kind);
            }

            @Override
            public List<Line> respond(UUID callId, String said) {
                return LlmBrain.this.respond(callId, said);
            }

            @Override
            public boolean over(UUID callId) {
                Session s = sessions.get(callId);
                return s != null && s.over;
            }

            @Override
            public void forget(UUID callId) {
                sessions.remove(callId);
            }
        };
    }

    /** One Claude client for the whole application: it holds a connection pool. */
    private volatile ClaudeLlm claude;

    LlmClient client(ConversationBrain.Kind kind) {
        return switch (kind) {
            case CLAUDE -> {
                if (claude == null) {
                    synchronized (this) {
                        if (claude == null) {
                            claude = new ClaudeLlm(props, mapper);
                        }
                    }
                }
                yield claude;
            }
            case LOCAL -> new LlmClients.OpenAiCompatible(props, mapper);
            case SIMULATED -> throw new IllegalArgumentException("The simulated brain is not a model");
        };
    }

    List<Line> open(UUID callId, String openingLine, ConversationBrain.Kind kind) {
        List<Line> out = new ArrayList<>();
        ToolResult ctx = executor.execute(callId, "get_call_context", Map.of());
        out.add(Line.tool("get_call_context", ctx.status(), ctx.ok() ? null : ctx.errorMessage()));
        String system = tx.execute(st -> {
            VoiceCall call = calls.findById(callId).orElseThrow();
            VoiceScript script = call.getScriptId() == null ? null : scripts.findById(call.getScriptId()).orElse(null);
            Map<String, String> vars = callService.variables(call.getContactId() == null ? null
                    : contacts.findById(call.getContactId()).orElse(null));
            String prompt = script == null ? "" : VoiceScriptService.render(script.getSystemPrompt(), vars);
            return prompt + VOICE_RULES + "\nCALL CONTEXT (from get_call_context): " + json(ctx) + "\n";
        });
        Session s = new Session(kind, client(kind), system);
        s.history.add(Message.user("(The call has connected.)"));
        s.history.add(Message.assistant(openingLine, List.of()));
        sessions.put(callId, s);
        // While the opening line is being spoken, the model reads the script and tools, so its first reply is quick.
        List<ToolSpec> specs = specs();
        List<Message> snapshot = List.copyOf(s.history);
        CompletableFuture.runAsync(() -> {
            try {
                s.client.warmUp(system, snapshot, specs);
            } catch (RuntimeException e) {
                log.debug("Warm-up of the {} brain failed: {}", kind, e.getMessage());
            }
        });
        out.add(Line.agent(openingLine));
        return out;
    }

    List<Line> respond(UUID callId, String said) {
        Session s = sessions.get(callId);
        List<Line> out = new ArrayList<>();
        if (s == null || s.over) {
            out.add(Line.agent("This call has ended. Thank you."));
            return out;
        }
        synchronized (s) {
            s.history.add(Message.user(said.trim()));
            List<ToolSpec> specs = specs();
            try {
                for (int round = 0; round < MAX_ROUNDS; round++) {
                    Reply r = s.client.chat(s.system, s.history, specs);
                    if (r.toolCalls().isEmpty()) {
                        String text = brief(spoken(r.text()));
                        if (text.isEmpty()) {
                            text = "Sorry, could you say that again?";
                        }
                        s.history.add(Message.assistant(text, List.of(), r.raw()));
                        out.add(Line.agent(text));
                        if (s.closing) {
                            s.over = true;
                        } else if (GOODBYE.matcher(text).find()) {
                            // The model said goodbye without recording how the call went: a person reviews it, so
                            // nobody is left without a follow-up and the call is not simply retried.
                            ToolResult h = executor.execute(callId, "request_human_handoff", Map.of("reason_code", "OUT_OF_SCOPE",
                                    "summary", "The AI agent ended the call without recording an outcome. Please read the transcript and follow up."));
                            out.add(Line.tool("request_human_handoff", h.status(), h.ok() ? null : h.errorMessage()));
                            s.over = true;
                        }
                        return out;
                    }
                    s.history.add(Message.assistant(r.text(), r.toolCalls(), r.raw()));
                    if (!spoken(r.text()).isEmpty()) {
                        out.add(Line.agent(brief(spoken(r.text()))));
                    }
                    for (ToolCall c : r.toolCalls()) {
                        ToolResult res = executor.execute(callId, c.name(), c.arguments());
                        out.add(Line.tool(c.name(), res.status(), res.ok() ? null : res.errorMessage()));
                        s.history.add(Message.toolResult(c.id(), json(res)));
                        if (res.ok() && CLOSING_TOOLS.contains(c.name())) {
                            s.closing = true;
                        }
                    }
                }
                // The model kept looking things up without answering: a person takes over.
                handOver(callId, s, "The AI agent could not settle on an answer.", out);
            } catch (RuntimeException e) {
                log.warn("The {} brain failed on call {}: {}", s.kind, callId, e.getMessage());
                out.add(Line.tool("ai_model", "ERROR", e.getMessage()));
                handOver(callId, s, "The AI model was unavailable during the call.", out);
            }
            return out;
        }
    }

    private List<ToolSpec> specs() {
        return offered(tools).stream().map(t -> new ToolSpec(t.name(), t.description(), t.inputSchema())).toList();
    }

    /** The tools the model may ask for, in their fixed order. */
    static List<VoiceTools.Tool> offered(VoiceTools tools) {
        return tools.all().stream().filter(t -> !NOT_OFFERED.contains(t.name())).toList();
    }

    /** At most {@link #MAX_SENTENCES} are spoken: a longer reply keeps its first two and its last, usually the question. */
    static String brief(String text) {
        String[] parts = SENTENCE_END.split(text.trim());
        if (parts.length <= MAX_SENTENCES) {
            return text.trim();
        }
        return parts[0] + " " + parts[1] + " " + parts[parts.length - 1];
    }

    private void handOver(UUID callId, Session s, String why, List<Line> out) {
        ToolResult r = executor.execute(callId, "request_human_handoff", Map.of("reason_code", "TOOL_FAILURE", "summary", why));
        out.add(Line.tool("request_human_handoff", r.status(), r.ok() ? null : r.errorMessage()));
        out.add(Line.agent("I'm sorry, I'm having trouble on my side. " + (r.ok() && r.speakHint() != null ? r.speakHint()
                : "A member of our team will call you back.") + " Goodbye."));
        s.over = true;
    }

    /** What the model wrote, made fit to be spoken: no markdown, lists or stray symbols. */
    static String spoken(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("(?m)^\\s*([-*•]|\\d+[.)])\\s+", "").replaceAll("[*_#`>|]", "")
                .replaceAll("\\s*\\n+\\s*", " ").replaceAll("\\s{2,}", " ").trim();
        return t.length() > 600 ? t.substring(0, 600) : t;
    }

    private String json(ToolResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        if (r.data() != null && !r.data().isEmpty()) {
            m.put("data", r.data());
        }
        if (r.speakHint() != null) {
            m.put("say", r.speakHint());
        }
        if (!r.ok()) {
            m.put("error_code", r.errorCode());
            m.put("error_message", r.errorMessage());
        }
        return mapper.writeValueAsString(m);
    }
}
