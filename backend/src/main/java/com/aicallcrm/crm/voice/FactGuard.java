package com.aicallcrm.crm.voice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stops a model from saying a fact nobody gave it. A small model's worst habit on a phone call is inventing an amount,
 * a date or a deadline ("your visit is on Thursday, 20th April"): every number, currency, weekday, month and length
 * of time in a reply must already have been heard in the call, in the script, from the caller or from a tool.
 * The same rule as the training kit's checker ({@code training/callkit.py}, {@code ungrounded}).
 */
final class FactGuard {

    private FactGuard() {
    }

    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern CURRENCY = Pattern.compile("\\$|\\busd\\b|\\bdollars?\\b|\\brs\\.?\\s*\\d|₹|\\blakhs?\\b|\\bcrores?\\b");
    private static final List<String> DAYS_AND_MONTHS = List.of("monday", "tuesday", "wednesday", "thursday", "friday",
            "saturday", "sunday", "january", "february", "march", "april", "may", "june", "july", "august", "september",
            "october", "november", "december");
    /** "may" is also an ordinary word ("may I have your name"): only a date counts. */
    private static final Pattern MAY_DATE = Pattern.compile("\\b(\\d+\\s+may|may\\s+\\d+)\\b");
    private static final Pattern DURATION = Pattern.compile("\\b(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|"
            + "eleven|twelve|fifteen|twenty|thirty|few|couple of)\\s+(minute|hour|day|week|month|year)s?\\b");

    /** The facts in {@code said} that {@code heard} never contained; empty when the reply is grounded. */
    static List<String> unheard(String said, String heard) {
        String s = said == null ? "" : said.toLowerCase(Locale.ROOT);
        String h = heard == null ? "" : heard.toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        Set<String> numbersHeard = new HashSet<>();
        for (Matcher m = NUMBER.matcher(h); m.find(); ) {
            numbersHeard.add(m.group());
        }
        for (Matcher m = NUMBER.matcher(s); m.find(); ) {
            if (!numbersHeard.contains(m.group())) {
                out.add(m.group());
            }
        }
        Matcher c = CURRENCY.matcher(s);
        if (c.find() && !CURRENCY.matcher(h).find()) {
            out.add(c.group());
        }
        for (String word : DAYS_AND_MONTHS) {
            Pattern w = Pattern.compile("\\b" + word + "\\b");
            if (w.matcher(s).find() && !w.matcher(h).find() && (!"may".equals(word) || MAY_DATE.matcher(s).find())) {
                out.add(word);
            }
        }
        for (Matcher m = DURATION.matcher(s); m.find(); ) {
            if (!h.contains(m.group())) {
                out.add(m.group());
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Words that tell whoever answered that someone owes money. Until the identity check passes the agent may use one
     * only if the opening line, an identity question, the caller or a tool used it first (8B test call #7, 31 Oct: it
     * told a stranger "I'm calling about an overdue EMI payment"). Matched against lower case; the kit reads this too.
     *
     * <p>Then what the other starter packs call about (compliance checklist 2.5; in the saved training calls the model
     * told whoever answered "Your appointment is for your dental check-up" and asked about "our courses"): a clinic
     * visit, a course or admission, an insurance policy, a property. Not "enquiry", which the model also uses for any
     * question ("happy to help with your inquiry"), nor "of course".
     *
     * <p>And what MedRank calls about (the NEET counselling pack): NEET, counselling, choice filling, an allotted seat,
     * a rank, a score or cut-off, a college, MBBS or BDS, documents. The company's own name says none of them
     * ("MedRank" is one word, not "rank").
     */
    static final String REASON_WORDS = "\\b(overdue|emis?|loans?|debts?|dues|arrears|outstanding|install?ments?|"
            + "pay|paying|payments?|premiums?|fees?|amounts?|bills?|owe|owed|owing|"
            + "appointments?|check[- ]?ups?|doctors?|consultations?|treatments?|prescriptions?|medicines?|medications?|"
            + "vaccinations?|scans?|test results?|diagnos[ie]s|symptoms?|"
            + "admissions?|enrol(?:l)?(?:ments?|ed|ing)?|(?<!of )courses?|class(?:es)?|tuition|exams?|counsell?ors?|"
            + "insurance|polic(?:y|ies)|renew(?:als?|ed|ing)?|claims?|"
            + "propert(?:y|ies)|visits?|flats?|apartments?|plots?|villas?|"
            + "neet|counsell?ing|choice[- ]filling|allot(?:ments?|ted)|seats?|ranks?|scores?|cut[- ]?offs?|colleges?|"
            + "mbbs|bds|documents?)\\b";
    private static final Pattern REASON = Pattern.compile(REASON_WORDS);

    /** Said instead when the model names the reason again after its private note; it names nothing. */
    static final String REASON_FALLBACK = "It is about your account with us. May I have your full name, please?";
    /** The same, when the caller asked whether this is a person: that question always gets a true answer. */
    static final String REASON_FALLBACK_AI = "I am an AI assistant, not a person. It is about your account with us. May I have your full name, please?";
    static final String AI_QUESTION = "real person|human|robot|machine|\\bai\\b|\\bbot\\b|automated|computer";
    private static final Pattern AI_ASKED = Pattern.compile(AI_QUESTION);

    /** The fixed line for what the caller just said. */
    static String reasonFallback(String said) {
        return said != null && AI_ASKED.matcher(said.toLowerCase(Locale.ROOT)).find() ? REASON_FALLBACK_AI : REASON_FALLBACK;
    }

    /** The words in {@code said} that give away why we are calling and {@code allowed} never used; empty when none. */
    static List<String> reasonWords(String said, String allowed) {
        String s = said == null ? "" : said.toLowerCase(Locale.ROOT);
        Set<String> ok = new HashSet<>();
        for (Matcher m = REASON.matcher(allowed == null ? "" : allowed.toLowerCase(Locale.ROOT)); m.find(); ) {
            ok.add(m.group());
        }
        Set<String> out = new LinkedHashSet<>();
        for (Matcher m = REASON.matcher(s); m.find(); ) {
            if (!ok.contains(m.group())) {
                out.add(m.group());
            }
        }
        return new ArrayList<>(out);
    }

    /** The private note the model gets once when it names the reason for the call too early. */
    static String reasonReminder(List<String> words) {
        return "\n\nPRIVATE NOTE (never mention it): your last reply said " + String.join(", ", words)
                + " before the identity check passed. Whoever answered may not be the customer. Say only that it is about"
                + " their account with us, and ask for their full name.";
    }

    /** The private note the model gets once, with the reply it must not repeat left out of the call. */
    static String reminder(List<String> facts) {
        return "\n\nPRIVATE NOTE (never mention it): your last reply stated " + String.join(", ", facts)
                + ", which no tool returned and the caller did not say. Never guess a date, time, amount or number."
                + " Use only what the tools or the caller gave. If you do not have it, say you cannot check right now"
                + " and offer a call-back.";
    }
}
