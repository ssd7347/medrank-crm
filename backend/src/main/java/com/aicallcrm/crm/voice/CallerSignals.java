package com.aicallcrm.crm.voice;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aicallcrm.crm.voice.LlmClient.ToolCall;
import com.aicallcrm.crm.voice.Voice.HandoffReason;

/**
 * What the caller said decides how urgently a person follows up, not the model's choice of words.
 *
 * A model sometimes books an ordinary call-back for a caller who just said they lost their job, or hands over a
 * disputed charge under the wrong reason. A DISTRESS or COMPLAINT_OR_DISPUTE hand-over is urgent
 * ({@link HandoffReason#high()}), so: when the caller's words in this call show distress or a dispute and the agent
 * books a call-back or hands over, the CRM makes it that hand-over. The word lists were checked on about 400 saved
 * test calls (28 Oct 2026): no false alarms; the only misses were callers who never mentioned it. The training kit
 * reads the same lists and aliases from training/spec/agent-spec.json (TrainingSpecTest keeps it in step).
 */
final class CallerSignals {

    private CallerSignals() {
    }

    /** Matched against the caller's words, lower case. */
    static final String DISTRESS = "lost (my|our) (job|income|business)|laid off|no (job|income)|(can'?t|cannot) afford|"
            + "hospital|passed away|(very|so|really) stressed|depress|crying|falling apart|(can'?t|cannot) cope|"
            + "medical (bills|emergency)|struggling (to pay|financially|with money)|no money|"
            + "don'?t have (the |any )?money|don'?t know how i('ll| will| am going to) (manage|pay|cope)";
    static final String DISPUTE = "already paid|already cleared|amount[^.?!]{0,30}(wrong|incorrect|not due)|"
            + "paid (it|this|that) (already|last)|(amount|charge|bill) (is|was) (wrong|incorrect|not correct)|"
            + "charged twice|double charged|refund|money back|not my (loan|account|charge)|never (took|agreed)|"
            + "wrong amount|incorrect amount|overcharged|lawyer|consumer court|i want to complain|file a complaint";
    static final String OPT_OUT = "stop calling|stop these calls|(don'?t|do not|never) (ever )?call (me |this number )?(again|any ?more)|"
            + "no more calls|remove my (number|name)|take (me|my number) off|unsubscribe|don'?t want (these|any|your) calls";
    /** A call-back or a new date asked for, and the same said in the negative ("I don't need a callback"). */
    static final String CALLBACK = "call (me )?back|call me (again )?(later|tomorrow|after|in an? |next|this evening|tonight|some ?time)|"
            + "ring me (back|later)|call-?back|re-?schedule|postpone|"
            + "(change|move|shift) (it|the (date|time|day|appointment|visit|slot)|my (appointment|visit|slot|booking))|"
            + "(another|some other|a different) (day|date|time|slot)";
    static final String NO_CALLBACK = "(don'?t|do not|never|no need to|not) (need |want )?(to )?(a |an |any )?"
            + "(call (me )?back|call-?back|re-?schedule|postpone|(change|move|shift) (it|the|my))|"
            + "no call-? ?back|no need (to|for) (a |any )?call";
    /** The argument the CRM adds to log_call_outcome: the caller's sentence asking for a call-back. */
    static final String CALLBACK_ASKED = "caller_asked_for_callback";
    private static final Pattern OPT_OUT_WORDS = Pattern.compile(OPT_OUT);
    private static final Pattern CALLBACK_WORDS = Pattern.compile(CALLBACK);
    private static final Pattern NO_CALLBACK_WORDS = Pattern.compile(NO_CALLBACK);
    private static final Pattern SENTENCE = Pattern.compile("[^.?!]+[.?!]?");
    private static final Pattern DISTRESS_WORDS = Pattern.compile(DISTRESS);
    private static final Pattern DISPUTE_WORDS = Pattern.compile(DISPUTE);

    /** Hand-over reasons models invent, understood instead of refused. A wrong person is not a hand-over. */
    static final Map<String, String> REASON_ALIASES = new TreeMap<>(Map.of(
            "DISPUTE", "COMPLAINT_OR_DISPUTE", "COMPLAINT", "COMPLAINT_OR_DISPUTE",
            "DISTRESSED", "DISTRESS", "HUMAN", "CALLER_REQUEST", "PERSON", "CALLER_REQUEST",
            "HUMAN_REQUEST", "CALLER_REQUEST", "VERIFICATION_FAILURE", "VERIFICATION_FAILED"));

    /** The valid reason a model meant, or null when it is not one we know. */
    static HandoffReason reason(Object raw) {
        if (raw == null) {
            return null;
        }
        String r = String.valueOf(raw).trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        r = REASON_ALIASES.getOrDefault(r, r);
        try {
            return HandoffReason.valueOf(r);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** DISTRESS, COMPLAINT_OR_DISPUTE, or null, from everything the caller has said in the call. */
    static HandoffReason heard(String callerWords) {
        String s = callerWords == null ? "" : callerWords.toLowerCase(Locale.ROOT);
        if (DISTRESS_WORDS.matcher(s).find()) {
            return HandoffReason.DISTRESS;
        }
        return DISPUTE_WORDS.matcher(s).find() ? HandoffReason.COMPLAINT_OR_DISPUTE : null;
    }

    /** The caller's last sentence asking for a call-back or a new date (lower case), or null when there is none. */
    static String callbackAsked(String callerWords) {
        String asked = null;
        Matcher m = SENTENCE.matcher(callerWords == null ? "" : callerWords.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String s = m.group().trim();
            if (CALLBACK_WORDS.matcher(s).find() && !NO_CALLBACK_WORDS.matcher(s).find()) {
                asked = s;
            }
        }
        return asked;
    }

    /**
     * The tool call the CRM actually runs: a call-back or hand-over becomes an urgent DISTRESS / COMPLAINT_OR_DISPUTE
     * hand-over when the caller's words show it. A VERIFICATION_FAILED hand-over is left alone (the caller's identity
     * is not known), and distress is never downgraded to a dispute. Logging an outcome for a caller who asked not to be
     * called again becomes the opt-out, whatever the model meant to log (8B retest call #31, 1 Nov). Logging an outcome
     * for a caller who asked for a call-back or a new date carries their words, so the call-back gets booked
     * (VoiceTools.logOutcome; 8B retest calls #17 and #41, 2 Nov). Any other call is returned unchanged.
     */
    static ToolCall upgrade(ToolCall c, String callerWords) {
        if ("log_call_outcome".equals(c.name())) {
            if (callerWords != null && OPT_OUT_WORDS.matcher(callerWords.toLowerCase(Locale.ROOT)).find()) {
                return new ToolCall(c.id(), "opt_out", Map.of("scope", "AI_CALLS"));
            }
            String asked = callbackAsked(callerWords);
            if (asked == null || c.arguments().containsKey(CALLBACK_ASKED)
                    || "CALLBACK_REQUESTED".equalsIgnoreCase(String.valueOf(c.arguments().get("outcome_code")))) {
                return c;     // nothing asked, or the outcome itself books it
            }
            Map<String, Object> args = new LinkedHashMap<>(c.arguments());
            args.put(CALLBACK_ASKED, asked);
            return new ToolCall(c.id(), c.name(), args);
        }
        boolean handoff = "request_human_handoff".equals(c.name());
        if (!handoff && !"schedule_callback".equals(c.name())) {
            return c;
        }
        HandoffReason signal = heard(callerWords);
        if (signal == null) {
            return c;
        }
        HandoffReason asked = handoff ? reason(c.arguments().get("reason_code")) : null;
        if (asked == signal || asked == HandoffReason.VERIFICATION_FAILED
                || (asked == HandoffReason.DISTRESS && signal == HandoffReason.COMPLAINT_OR_DISPUTE)) {
            return c;
        }
        Object note = handoff ? c.arguments().get("summary") : c.arguments().get("reason");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("reason_code", signal.name());
        args.put("summary", (signal == HandoffReason.DISTRESS ? "The caller sounded distressed" : "The caller disputes a charge")
                + " (marked urgent by the CRM)." + (note == null ? "" : " Agent's note: " + note));
        return new ToolCall(c.id(), "request_human_handoff", args);
    }
}
