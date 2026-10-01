package com.mbbscrm.crm.voice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.mbbscrm.crm.voice.Voice.Purpose;
import com.mbbscrm.crm.voice.VoiceCallService.Line;

/**
 * A stand-in for the AI model, used only by the test console and the automated conversation tests
 * (spec 18.15.3, "text harness"). It follows the same rules the real script gives the model, by keyword
 * rather than by understanding: verify before anything personal, hand off decisions, disputes and distress,
 * honour "stop calling", answer only from what a tool returned. It speaks fixed English sentences. Every
 * lookup goes through the real {@link ToolExecutor}, so the tools, the verification rule, the audit trail
 * and the handoffs are exercised exactly as a live platform would exercise them.
 */
@Component
public class SimulatedBrain {

    private static final String ASK_VERIFY = "To share those details, may I confirm the student's full name and "
            + "either the last four digits of the NEET roll number or the home state?";
    private static final String OFFER_CALLBACK = " Would you like a counsellor to call you back?";

    private static final Pattern INJECTION = Pattern.compile(
            "ignore (your|all|the|previous|those) |system prompt|your instructions|your rules|pretend|act as |"
                    + "another student|someone else'?s|other student|my friend'?s");
    private static final Pattern DISTRESS = Pattern.compile(
            "kill myself|suicide|end my life|want to die|harm myself|hurt myself|can'?t go on|no hope|hopeless");
    private static final Pattern OPT_OUT = Pattern.compile(
            "stop calling|don'?t call|do not call|never call|remove my number|no more calls|stop these calls");
    private static final Pattern DISPUTE = Pattern.compile(
            "refund|forfeit|complain|cheat|fraud|legal|court|lawyer|police|dispute|money back");
    private static final Pattern DECISION = Pattern.compile(
            "(should|shall|can|do) (i|we|he|she) .*(accept|withdraw|upgrade|freeze|float|leave|give up|join|take|keep|surrender)"
                    + "|which (college|seat|one) should|better to (accept|withdraw|upgrade|leave|join)");
    private static final Pattern CALLBACK = Pattern.compile(
            "call (me )?(back|later|tomorrow|again)|busy|not a good time|in a meeting|driving");
    private static final Pattern HUMAN = Pattern.compile(
            "human|real person|a person|speak to (a |my |the )?(counsel|someone)|talk to (a |my |the )?(counsel|someone)");
    private static final Pattern WRONG_PERSON = Pattern.compile("wrong number|no one (by|of) that name|don'?t know (him|her|them|who)");
    private static final Pattern NOT_INTERESTED = Pattern.compile("not interested|no thanks|already (joined|admitted|got)");
    private static final Pattern WILL_ACT = Pattern.compile("(i|we)('ll| will) (upload|pay|send|do|submit|bring)");
    private static final Pattern GOODBYE = Pattern.compile(
            "^(ok(ay)?|thanks?|thank you|got it|understood|bye|fine|noted|alright|no|nothing( else)?|that'?s all)[ .!,a-z]{0,25}$");
    private static final Pattern YES = Pattern.compile("^(yes|yeah|yep|speaking|this is|i am|i'm|it is|hello|hi)\\b");
    private static final Pattern FOUR_DIGITS = Pattern.compile("(\\d[\\s-]*){4,}");

    /** What the brain has to remember between turns of one test call. */
    private static final class Session {
        String pendingTool;
        Map<String, Object> pendingArgs = Map.of();
        boolean awaitingVerification;
        boolean purposeDelivered;
        int toolFailures;
        boolean over;
    }

    private final ToolExecutor executor;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public SimulatedBrain(ToolExecutor executor) {
        this.executor = executor;
    }

    void forget(UUID callId) {
        sessions.remove(callId);
    }

    boolean over(UUID callId) {
        Session s = sessions.get(callId);
        return s != null && s.over;
    }

    /** First thing on every call: read the context, then say the script's opening line. */
    List<Line> open(UUID callId, String openingLine) {
        sessions.put(callId, new Session());
        List<Line> out = new ArrayList<>();
        run(callId, "get_call_context", Map.of(), out);
        out.add(Line.agent(openingLine));
        return out;
    }

    /** One turn: the caller said {@code said}; returns the tool calls made and what the agent replies. */
    List<Line> respond(UUID callId, Purpose purpose, boolean studentKnown, String said) {
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
            out.add(Line.agent("I can't do that. I can only share details of the student this call is about, after "
                    + "checking identity. I can help with counselling dates, documents and fees."));
            return out;
        }
        if (DISPUTE.matcher(text).find()) {
            handoff(callId, s, "REFUND_OR_DISPUTE", "Caller raised a refund or dispute: \"" + clip(said) + "\"", out);
            return out;
        }
        if (DECISION.matcher(text).find()) {
            out.add(Line.agent("That is a decision a counsellor should guide you on, so I won't advise on it."));
            handoff(callId, s, "DECISION_ADVICE", "Caller asked for advice on a seat decision: \"" + clip(said) + "\"", out);
            return out;
        }
        if (HUMAN.matcher(text).find()) {
            handoff(callId, s, "CALLER_REQUEST", "Caller asked to speak to a person.", out);
            return out;
        }

        if (s.awaitingVerification) {
            verify(callId, s, said, text, out);
            return out;
        }
        // "No" to "Am I speaking with ...?" is a wrong person; later in the call it just means "nothing else".
        if (WRONG_PERSON.matcher(text).find() || (!s.purposeDelivered && text.matches("^(no|nope)\\b.*"))) {
            end(callId, s, "WRONG_PERSON", null, "I'm sorry to have troubled you. Goodbye.", out);
            return out;
        }
        if (CALLBACK.matcher(text).find()) {
            ToolResult r = run(callId, "schedule_callback",
                    Map.of("preferred_time_text", clip(said), "reason", "Caller asked to be called back."), out);
            out.add(Line.agent(r.ok() ? r.speakHint() + " Goodbye." : "A counsellor will call you back. Goodbye."));
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

        String tool = factTool(text);
        if (tool != null) {
            Map<String, Object> args = "get_round_status".equals(tool)
                    ? Map.of("authority", text.contains("state") ? "STATE" : "AIQ") : Map.of();
            lookUp(callId, s, studentKnown, tool, args, out);
            return out;
        }
        if (GOODBYE.matcher(text).find()) {
            end(callId, s, s.purposeDelivered ? "ACKNOWLEDGED" : "NO_INTERACTION", null,
                    "Thank you for your time. Goodbye.", out);
            return out;
        }
        if (!s.purposeDelivered && YES.matcher(text).find()) {
            deliverPurpose(callId, s, purpose, studentKnown, out);
            return out;
        }

        ToolResult faq = run(callId, "search_faq", Map.of("query", clip(said)), out);
        out.add(Line.agent(faq.ok() ? faq.speakHint()
                : failed(faq) ? tooManyFailures(callId, s, out)
                : "I don't have that information." + OFFER_CALLBACK));
        return out;
    }

    // ------------------------------------------------------------------ steps

    private void deliverPurpose(UUID callId, Session s, Purpose purpose, boolean studentKnown, List<Line> out) {
        s.purposeDelivered = true;
        switch (purpose) {
            case DEADLINE_REMINDER -> lookUp(callId, s, studentKnown, "get_deadlines", Map.of(), out);
            case DOC_NUDGE -> lookUp(callId, s, studentKnown, "get_document_status", Map.of(), out);
            case FEE_REMINDER -> lookUp(callId, s, studentKnown, "get_fee_status", Map.of(), out);
            case LEAD_QUALIFY -> out.add(Line.agent("Thank you. We help students through MBBS admission counselling. "
                    + "Would you like a counsellor to call you to explain how it works?"));
            case MISSED_CALL_FOLLOWUP -> out.add(Line.agent("Our counsellor tried to reach you earlier."
                    + OFFER_CALLBACK));
            default -> out.add(Line.agent("How can I help you today?"));
        }
    }

    /** Personal facts need verification first; the question is remembered and answered once verified. */
    private void lookUp(UUID callId, Session s, boolean studentKnown, String tool, Map<String, Object> args,
                        List<Line> out) {
        s.purposeDelivered = true;
        if (studentKnown && "get_deadlines".equals(tool) && !verified(callId, out)) {
            askToVerify(s, tool, args, out);
            return;
        }
        ToolResult r = run(callId, tool, args, out);
        if (!r.ok() && ToolResult.VERIFICATION_REQUIRED.equals(r.errorCode())) {
            if (studentKnown) {
                askToVerify(s, tool, args, out);
            } else {
                out.add(Line.agent("I can only share that for a student registered with us." + OFFER_CALLBACK));
            }
            return;
        }
        out.add(Line.agent(speak(callId, s, r, out)));
    }

    private void askToVerify(Session s, String tool, Map<String, Object> args, List<Line> out) {
        s.pendingTool = tool;
        s.pendingArgs = args;
        s.awaitingVerification = true;
        out.add(Line.agent(ASK_VERIFY));
    }

    /** Asks the context tool rather than keeping its own idea of the level: the server is the authority. */
    private boolean verified(UUID callId, List<Line> out) {
        ToolResult ctx = executor.execute(callId, "get_call_context", Map.of());
        return ctx.ok() && Boolean.TRUE.equals(ctx.data().get("verified"));
    }

    private void verify(UUID callId, Session s, String said, String text, List<Line> out) {
        Map<String, Object> args = new LinkedHashMap<>();
        Matcher digits = FOUR_DIGITS.matcher(said);
        String rest = said;
        if (digits.find()) {
            String d = digits.group().replaceAll("\\D", "");
            args.put("neet_roll_last4", d.substring(d.length() - 4));
            rest = said.substring(0, digits.start()) + " " + said.substring(digits.end());
        }
        // "Priya Shankar from Tamil Nadu" or "Priya Shankar, Tamil Nadu": the part after is the home state.
        String[] parts = rest.split("(?i)\\bfrom\\b|,", 2);
        args.put("student_full_name", parts[0].replaceAll(
                "(?i)\\b(my|her|his|the|student'?s?|full|name|is|this|i am|it is|roll|number|last|four|digits|and|neet)\\b", " ")
                .replaceAll("[^\\p{L} ]", " ").replaceAll("\\s+", " ").trim());
        if (parts.length > 1 && !parts[1].isBlank()) {
            args.put("home_state", parts[1].replaceAll("[^\\p{L} ]", " ").replaceAll("\\s+", " ").trim());
        }
        args.put("speaker_type", text.matches(".*\\b(mother|father|parent|son|daughter|guardian)\\b.*") ? "PARENT" : "STUDENT");

        ToolResult r = run(callId, "verify_identity", args, out);
        if (r.ok() && Boolean.TRUE.equals(r.data().get("verified"))) {
            s.awaitingVerification = false;
            String tool = s.pendingTool;
            Map<String, Object> pending = s.pendingArgs;
            s.pendingTool = null;
            ToolResult answer = run(callId, tool, pending, out);
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
        out.add(Line.agent(r.ok() ? r.speakHint() + " Goodbye." : "A counsellor will call you back. Goodbye."));
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
            if (r.speakHint() != null) {
                return r.speakHint();
            }
            Object rule = r.data().get("rule");
            return rule instanceof List<?> lines && !lines.isEmpty()
                    ? lines.stream().limit(2).map(String::valueOf).reduce((a, b) -> a + " " + b).orElse("")
                            + " A counsellor must confirm this before you decide anything."
                    : "I have checked that for you.";
        }
        if (failed(r)) {
            return tooManyFailures(callId, s, out);
        }
        // NOT_AVAILABLE messages start with a plain statement of what is missing.
        return r.errorMessage().split("(?<=\\.) ")[0] + OFFER_CALLBACK;
    }

    private static boolean failed(ToolResult r) {
        return ToolResult.INTERNAL.equals(r.errorCode()) || ToolResult.RATE_LIMITED.equals(r.errorCode());
    }

    /** Two failed lookups in a row and a person takes over (spec 18.11.3). */
    private String tooManyFailures(UUID callId, Session s, List<Line> out) {
        if (++s.toolFailures < 2) {
            return "I can't check that right now." + OFFER_CALLBACK;
        }
        ToolResult r = run(callId, "request_human_handoff",
                Map.of("reason_code", "TOOL_FAILURE", "summary", "Two lookups failed during the call."), out);
        s.over = true;
        return "I'm sorry, I can't check that right now. " + (r.ok() ? r.speakHint() : "") + " Goodbye.";
    }

    private ToolResult run(UUID callId, String tool, Map<String, Object> args, List<Line> out) {
        ToolResult r = executor.execute(callId, tool, args);
        out.add(Line.tool(tool, r.status(), r.ok() ? null : r.errorMessage()));
        return r;
    }

    private static String factTool(String text) {
        if (text.matches(".*\\b(chance|chances|predict|shortlist|cut ?off|will i get|can i get)\\b.*")) {
            return "get_predictor_summary";
        }
        if (text.matches(".*\\b(deposit|rule|what happens if|penalty)\\b.*")) {
            return "get_refund_rule_summary";
        }
        if (text.matches(".*\\b(allot|allotted|allotment|my seat|which college did)\\b.*")) {
            return "get_allotment_summary";
        }
        if (text.matches(".*\\b(document|documents|certificate|certificates|upload|papers)\\b.*")) {
            return "get_document_status";
        }
        if (text.matches(".*\\b(fee|fees|payment|pay|instalment|installment|due amount|balance)\\b.*")) {
            return "get_fee_status";
        }
        if (text.matches(".*\\b(round|result|stage|status)\\b.*")) {
            return "get_round_status";
        }
        if (text.matches(".*\\b(when|deadline|deadlines|last date|date|dates|choice filling|reporting)\\b.*")) {
            return "get_deadlines";
        }
        return null;
    }

    private static String clip(String s) {
        String t = s.trim();
        return t.length() <= 300 ? t : t.substring(0, 300);
    }
}
