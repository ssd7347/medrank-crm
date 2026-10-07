package com.aicallcrm.crm.voice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.aicallcrm.crm.voice.VoiceCallService.Line;

/**
 * A stand-in for the AI model, used only by the test console and the automated conversation tests. It follows
 * the same rules the real script gives the model, by keyword rather than by understanding: check identity
 * before anything private, hand complaints and distress to a person, honour "stop calling", and say only what
 * a tool returned. It speaks fixed English sentences. Every look-up goes through the real {@link ToolExecutor},
 * so the tools, the verification rule, the audit trail and the hand-overs work exactly as with a live platform.
 */
@Component
public class SimulatedBrain implements ConversationBrain {

    private static final String OFFER_CALLBACK = " Would you like a member of our team to call you back?";

    private static final Pattern INJECTION = Pattern.compile(
            "ignore (your|all|the|previous|those) |system prompt|your instructions|your rules|pretend|act as |"
                    + "another (customer|person|client)|someone else'?s|other customer|my friend'?s");
    private static final Pattern DISTRESS = Pattern.compile(
            "kill myself|suicide|end my life|want to die|harm myself|hurt myself|can'?t go on|no hope|hopeless");
    private static final Pattern OPT_OUT = Pattern.compile(
            "stop calling|don'?t call|do not call|never call|remove my number|no more calls|stop these calls|unsubscribe");
    private static final Pattern DISPUTE = Pattern.compile(
            "refund|complain|complaint|cheat|fraud|legal|court|lawyer|police|dispute|money back|wrong charge|overcharged");
    private static final Pattern CALLBACK = Pattern.compile(
            "call (me )?(back|later|tomorrow|again)|busy|not a good time|in a meeting|driving");
    private static final Pattern HUMAN = Pattern.compile(
            "human|real person|a person|speak to (a |the |your )?(someone|manager|agent|staff|team)|"
                    + "talk to (a |the |your )?(someone|manager|agent|staff|team)");
    private static final Pattern WRONG_PERSON = Pattern.compile("wrong number|no one (by|of) that name|don'?t know (him|her|them|who)");
    private static final Pattern NOT_INTERESTED = Pattern.compile("not interested|no thanks|don'?t need|already bought|already have");
    private static final Pattern INTERESTED = Pattern.compile("\\b(interested|go ahead|sign me up|i want it|book it|sounds good)\\b");
    private static final Pattern WILL_ACT = Pattern.compile("(i|we)('ll| will) (pay|come|send|do|visit|attend|transfer)");
    private static final Pattern DETAILS = Pattern.compile(
            "\\b(how much|amount|due|when|date|time|appointment|details|balance|plan|account|my order|outstanding|pending)\\b");
    private static final Pattern GOODBYE = Pattern.compile(
            "^(ok(ay)?|thanks?|thank you|got it|understood|bye|fine|noted|alright|no|nothing( else)?|that'?s all)[ .!,a-z]{0,25}$");
    private static final Pattern YES = Pattern.compile("^(yes|yeah|yep|speaking|this is|i am|i'm|it is|hello|hi|sure)\\b");

    /** What the brain has to remember between turns of one test call. */
    private static final class Session {
        boolean awaitingVerification;
        boolean delivered;
        int toolFailures;
        boolean over;
        boolean known;
        List<String> questionKeys = List.of();
    }

    private final ToolExecutor executor;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public SimulatedBrain(ToolExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void forget(UUID callId) {
        sessions.remove(callId);
    }

    @Override
    public boolean over(UUID callId) {
        Session s = sessions.get(callId);
        return s != null && s.over;
    }

    /** First thing on every call: read the context, then say the script's opening line. */
    @Override
    public List<Line> open(UUID callId, String openingLine) {
        Session s = new Session();
        sessions.put(callId, s);
        List<Line> out = new ArrayList<>();
        ToolResult ctx = run(callId, "get_call_context", Map.of(), out);
        if (ctx.ok()) {
            s.known = Boolean.TRUE.equals(ctx.data().get("known_contact"));
            List<String> keys = new ArrayList<>();
            if (ctx.data().get("verification_questions") instanceof List<?> qs) {
                for (Object q : qs) {
                    if (q instanceof Map<?, ?> m && !"full_name".equals(m.get("key"))) {
                        keys.add(String.valueOf(m.get("key")));
                    }
                }
            }
            s.questionKeys = keys;
        }
        out.add(Line.agent(openingLine));
        return out;
    }

    /** One turn: the caller said {@code said}; returns the tool calls made and what the agent replies. */
    @Override
    public List<Line> respond(UUID callId, String said) {
        Session s = sessions.computeIfAbsent(callId, k -> new Session());
        List<Line> out = new ArrayList<>();
        String text = said.toLowerCase(Locale.ROOT).trim();
        if (s.over) {
            out.add(Line.agent("This call has ended. Thank you."));
            return out;
        }

        // Safety rules come before everything else, whatever the caller was in the middle of.
        if (DISTRESS.matcher(text).find()) {
            out.add(Line.agent("I'm really sorry you are feeling this way. You are not alone in this."));
            handoff(callId, s, "DISTRESS", "Caller sounded distressed: \"" + clip(said) + "\"", out);
            return out;
        }
        if (OPT_OUT.matcher(text).find()) {
            ToolResult r = run(callId, "opt_out",
                    Map.of("scope", text.contains("all calls") || text.contains("never call") ? "ALL_CALLS" : "AI_CALLS"),
                    out);
            out.add(Line.agent(r.ok() ? r.speakHint() + " Goodbye." : "I have noted your request. Goodbye."));
            s.over = true;
            return out;
        }
        if (INJECTION.matcher(text).find()) {
            out.add(Line.agent("I can't do that. I can only discuss your own account, after checking it is you. "
                    + "How else can I help?"));
            return out;
        }
        if (DISPUTE.matcher(text).find()) {
            out.add(Line.agent("I'm sorry about that. A member of our team will look into it personally."));
            handoff(callId, s, "COMPLAINT_OR_DISPUTE", "Caller raised a complaint: \"" + clip(said) + "\"", out);
            return out;
        }
        if (HUMAN.matcher(text).find()) {
            handoff(callId, s, "CALLER_REQUEST", "Caller asked to speak to a person.", out);
            return out;
        }

        // "No" to "Am I speaking with ...?" is a wrong person; later in the call it just means "nothing else".
        if (WRONG_PERSON.matcher(text).find() || (!s.delivered && text.matches("^(no|nope)\\b.*"))) {
            end(callId, s, "WRONG_PERSON", null, "I'm sorry to have troubled you. Goodbye.", out);
            return out;
        }
        if (CALLBACK.matcher(text).find()) {
            ToolResult r = run(callId, "schedule_callback",
                    Map.of("preferred_time_text", clip(said), "reason", "Caller asked to be called back."), out);
            out.add(Line.agent(r.ok() ? r.speakHint() + " Goodbye." : "Someone will call you back. Goodbye."));
            s.over = true;
            return out;
        }
        if (NOT_INTERESTED.matcher(text).find()) {
            end(callId, s, "NOT_INTERESTED", null, "Thank you for letting me know. Goodbye.", out);
            return out;
        }
        if (WILL_ACT.matcher(text).find()) {
            end(callId, s, "WILL_ACT", clip(said), "Thank you, I have noted that. Goodbye.", out);
            return out;
        }
        if (INTERESTED.matcher(text).find() && !text.contains("not ")) {
            run(callId, "log_call_outcome", Map.of("outcome_code", "INTERESTED", "note", clip(said)), out);
            ToolResult r = run(callId, "schedule_callback",
                    Map.of("preferred_time_text", "soon", "reason", "Interested; wants the next step."), out);
            out.add(Line.agent("Wonderful. " + (r.ok() ? r.speakHint() : "Someone will call you.") + " Goodbye."));
            s.over = true;
            return out;
        }
        // Anything else said while the identity question is open is taken as the answer to it, so a promise to
        // pay or a request for a call-back above is never wasted as a failed attempt.
        if (s.awaitingVerification) {
            verify(callId, s, said, out);
            return out;
        }
        if (DETAILS.matcher(text).find()) {
            details(callId, s, out);
            return out;
        }
        if (GOODBYE.matcher(text).find()) {
            end(callId, s, s.delivered ? "ACKNOWLEDGED" : "NO_INTERACTION", null,
                    "Thank you for your time. Goodbye.", out);
            return out;
        }
        if (!s.delivered && YES.matcher(text).find()) {
            if (s.known) {
                details(callId, s, out);
            } else {
                s.delivered = true;
                out.add(Line.agent("How can I help you today?"));
            }
            return out;
        }

        ToolResult faq = run(callId, "search_faq", Map.of("query", clip(said)), out);
        out.add(Line.agent(faq.ok() ? faq.speakHint()
                : failed(faq) ? tooManyFailures(callId, s, out)
                : "I don't have that information." + OFFER_CALLBACK));
        return out;
    }

    // ------------------------------------------------------------------ steps

    /** Reads out what may be shared; asks for verification first when the details are private. */
    private void details(UUID callId, Session s, List<Line> out) {
        s.delivered = true;
        ToolResult r = run(callId, "get_contact_details", Map.of(), out);
        if (!r.ok() && ToolResult.VERIFICATION_REQUIRED.equals(r.errorCode())) {
            s.awaitingVerification = true;
            out.add(Line.agent(askToVerify(s)));
            return;
        }
        if (r.ok() && Boolean.TRUE.equals(r.data().get("more_after_verification"))) {
            // Say what may be said, then ask for the check before the private details.
            s.awaitingVerification = true;
            out.add(Line.agent(speak(callId, s, r, out) + " " + askToVerify(s)));
            return;
        }
        out.add(Line.agent(speak(callId, s, r, out)));
    }

    private static String askToVerify(Session s) {
        return s.questionKeys.isEmpty()
                ? "Before I share your details, may I have your full name, please?"
                : "Before I share your details, may I have your full name and your "
                        + s.questionKeys.get(0).replace('_', ' ') + "?";
    }

    private void verify(UUID callId, Session s, String said, List<Line> out) {
        // "Priya Shankar, CUST-1042" or "Priya Shankar and 1042": the second part answers the question.
        String[] parts = said.split("(?i),|\\band\\b|\\bmy (customer|account|id)\\b", 2);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("full_name", parts[0].replaceAll("(?i)\\b(my|name|is|this|i am|it is|full)\\b", " ").trim());
        if (parts.length > 1 && !s.questionKeys.isEmpty()) {
            Map<String, String> answers = new LinkedHashMap<>();
            String answer = parts[1].replaceAll("(?i)\\b(is|id|number|no)\\b", " ").trim();
            s.questionKeys.forEach(k -> answers.put(k, answer));
            args.put("answers", answers);
        }
        ToolResult r = run(callId, "verify_identity", args, out);
        if (r.ok() && Boolean.TRUE.equals(r.data().get("verified"))) {
            s.awaitingVerification = false;
            ToolResult answer = run(callId, "get_contact_details", Map.of(), out);
            out.add(Line.agent("Thank you, that's verified. " + speak(callId, s, answer, out)));
        } else if (r.ok() && Integer.valueOf(0).equals(r.data().get("attempts_left"))) {
            s.awaitingVerification = false;
            s.over = true;
            out.add(Line.agent(r.speakHint() + " Goodbye."));
        } else {
            out.add(Line.agent(r.ok() ? r.speakHint() : r.errorMessage()));
        }
    }

    private void handoff(UUID callId, Session s, String reason, String summary, List<Line> out) {
        ToolResult r = run(callId, "request_human_handoff", Map.of("reason_code", reason, "summary", summary), out);
        out.add(Line.agent(r.ok() ? r.speakHint() + " Goodbye." : "Someone will call you back. Goodbye."));
        s.over = true;
    }

    private void end(UUID callId, Session s, String outcome, String note, String goodbye, List<Line> out) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("outcome_code", outcome);
        if (note != null) {
            args.put("note", note);
        }
        run(callId, "log_call_outcome", args, out);
        out.add(Line.agent(goodbye));
        s.over = true;
    }

    /** Turns a tool result into a sentence. Nothing is said that the tool did not return. */
    private String speak(UUID callId, Session s, ToolResult r, List<Line> out) {
        if (r.ok()) {
            s.toolFailures = 0;
            return r.speakHint() != null ? r.speakHint() : "I have checked that for you.";
        }
        if (failed(r)) {
            return tooManyFailures(callId, s, out);
        }
        return r.errorMessage().split("(?<=\\.) ")[0] + OFFER_CALLBACK;
    }

    private static boolean failed(ToolResult r) {
        return ToolResult.INTERNAL.equals(r.errorCode()) || ToolResult.RATE_LIMITED.equals(r.errorCode());
    }

    /** Two failed look-ups in a row and a person takes over. */
    private String tooManyFailures(UUID callId, Session s, List<Line> out) {
        if (++s.toolFailures < 2) {
            return "I can't check that right now." + OFFER_CALLBACK;
        }
        ToolResult r = run(callId, "request_human_handoff",
                Map.of("reason_code", "TOOL_FAILURE", "summary", "Two look-ups failed during the call."), out);
        s.over = true;
        return "I'm sorry, I can't check that right now. " + (r.ok() ? r.speakHint() : "") + " Goodbye.";
    }

    private ToolResult run(UUID callId, String tool, Map<String, Object> args, List<Line> out) {
        ToolResult r = executor.execute(callId, tool, args);
        out.add(Line.tool(tool, r.status(), r.ok() ? null : r.errorMessage()));
        return r;
    }

    private static String clip(String s) {
        String t = s.trim();
        return t.length() <= 300 ? t : t.substring(0, 300);
    }
}
