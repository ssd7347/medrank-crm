package com.mbbscrm.crm.voice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.assistant.AssistantService;
import com.mbbscrm.crm.assistant.AssistantService.Answer;
import com.mbbscrm.crm.assistant.AssistantService.PublicFaq;
import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.Language;
import com.mbbscrm.crm.common.LeadSource;
import com.mbbscrm.crm.counselling.AuthorityType;
import com.mbbscrm.crm.lead.Lead;
import com.mbbscrm.crm.lead.LeadRepository;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentRepository;
import com.mbbscrm.crm.voice.HandoffService.Handoff;
import com.mbbscrm.crm.voice.Voice.HandoffReason;
import com.mbbscrm.crm.voice.Voice.Outcome;
import com.mbbscrm.crm.voice.Voice.PersonType;
import com.mbbscrm.crm.voice.VoiceFacts.DeadlineFact;

/**
 * The tools the agent may use during a call (spec 18.8.2). Rules every handler keeps:
 * the student comes from our own record of the call, never from an argument; replies are small maps with
 * only what may be spoken (no Aadhaar, date of birth, address, full phone number or payment details);
 * and only four tools change anything: callback, handoff, outcome and opt-out.
 */
@Component
public class VoiceTools {

    static final String DISCLAIMER = "These are estimates based on previous years, not a guarantee.";
    static final int MAX_VERIFY_ATTEMPTS = 3;

    @FunctionalInterface
    interface Handler {
        ToolResult handle(VoiceCall call, Map<String, Object> args);
    }

    /** {@code minLevel}: 0 = anyone on the call, 1 = only after verify_identity has succeeded. */
    public record Tool(String name, int minLevel, String description, Map<String, Object> inputSchema,
                       Handler handler) {
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final StudentRepository students;
    private final LeadRepository leads;
    private final VoiceFacts facts;
    private final AssistantService assistant;
    private final HandoffService handoffs;
    private final ConsentService consent;
    private final AuditService audit;

    public VoiceTools(StudentRepository students, LeadRepository leads, VoiceFacts facts, AssistantService assistant,
                      HandoffService handoffs, ConsentService consent, AuditService audit) {
        this.students = students;
        this.leads = leads;
        this.facts = facts;
        this.assistant = assistant;
        this.handoffs = handoffs;
        this.consent = consent;
        this.audit = audit;

        add("get_call_context", 0, "Returns why this call is happening, the caller's language and first name, "
                + "and whether identity must be verified before personal details are shared. Call it first.",
                schema(Map.of()), this::callContext);
        add("verify_identity", 0, "Checks the caller's identity. Ask for the student's full name and either the "
                + "last 4 digits of the NEET roll number or the home state, then call this. At most 3 attempts.",
                schema(Map.of(
                        "student_full_name", str("The student's full name as the caller said it."),
                        "neet_roll_last4", str("Last 4 digits of the NEET roll number."),
                        "home_state", str("The student's home state."),
                        "speaker_type", enumOf("Who is speaking.", "STUDENT", "PARENT"))), this::verifyIdentity);
        add("get_deadlines", 0, "Returns the caller's upcoming counselling deadlines (general published dates "
                + "until identity is verified). Use whenever the caller asks 'when is ...' or the call is a deadline "
                + "reminder. Never state a date that this tool did not return.", schema(Map.of()), this::deadlines);
        add("get_round_status", 1, "Returns the current round, its stage, whether the result is out and when the "
                + "decision window ends, for All India (AIQ) or State counselling.",
                schema(Map.of("authority", enumOf("Which counselling.", "AIQ", "STATE")), "authority"),
                this::roundStatus);
        add("get_allotment_summary", 1, "Returns the latest seat allotment: college, quota, category and whether "
                + "a decision has been recorded. Read-only; never advise on the decision.", schema(Map.of()),
                this::allotment);
        add("get_document_status", 1, "Returns how many required documents are verified and the names of those "
                + "still missing or expiring.", schema(Map.of()), this::documentStatus);
        add("get_fee_status", 1, "Returns the next consultancy-fee instalment due: amount, due date, overdue or "
                + "not. Informational only; never press for payment.", schema(Map.of()), this::feeStatus);
        add("get_refund_rule_summary", 1, "Returns the recorded rule for giving up the allotted seat, in plain "
                + "words. Information only: a counsellor must confirm before any decision.", schema(Map.of()),
                this::refundRule);
        add("get_predictor_summary", 1, "Returns up to 3 shortlisted colleges with a High/Moderate/Low chance "
                + "band. Always read the disclaimer that comes with it.", schema(Map.of()), this::predictor);
        add("search_faq", 0, "Searches the approved knowledge base for general questions about documents, "
                + "rounds, quotas and our services. Answer only from what it returns.",
                schema(Map.of("query", str("The caller's question in their own words.")), "query"), this::searchFaq);
        add("schedule_callback", 0, "Books a counsellor callback when the caller is busy or wants to talk to a "
                + "person later.",
                schema(Map.of(
                        "preferred_time_text", str("When the caller would like the call, in their words."),
                        "reason", str("Why they want the callback, in one sentence."),
                        "caller_name", str("The caller's name, if they are not already known to us."))),
                this::scheduleCallback);
        add("request_human_handoff", 0, "Use when the caller wants to accept, withdraw or upgrade a seat, asks "
                + "about refunds or disputes, is upset or confused, asks for a human, or a tool failed twice.",
                schema(Map.of(
                        "reason_code", enumOf("Why a person is needed.",
                                Arrays.stream(HandoffReason.values()).map(Enum::name).toArray(String[]::new)),
                        "summary", str("One or two sentences for the counsellor.")), "reason_code", "summary"),
                this::handoff);
        add("log_call_outcome", 0, "Records how the call ended. Call it once, just before saying goodbye.",
                schema(Map.of(
                        "outcome_code", enumOf("How the call ended.",
                                Arrays.stream(Outcome.values()).map(Enum::name).toArray(String[]::new)),
                        "note", str("Anything the counsellor should know, such as a promised date.")),
                        "outcome_code"), this::logOutcome);
        add("opt_out", 0, "Use at once when the caller asks not to be called again. Confirm it to them.",
                schema(Map.of("scope", enumOf("What to stop.", "AI_CALLS", "ALL_CALLS")), "scope"), this::optOut);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public Collection<Tool> all() {
        return tools.values();
    }

    private void add(String name, int minLevel, String description, Map<String, Object> schema, Handler handler) {
        tools.put(name, new Tool(name, minLevel, description, schema, handler));
    }

    // ------------------------------------------------------------------ context and verification

    private ToolResult callContext(VoiceCall call, Map<String, Object> args) {
        Student s = student(call);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("purpose", call.getPurpose().name());
        data.put("direction", call.getDirection().name());
        data.put("language", call.getLanguage().name());
        data.put("first_name", firstName(call, s));
        data.put("verified", call.getVerificationLevel() >= 1);
        data.put("verification_required", s != null && call.getVerificationLevel() < 1);
        data.put("recording", call.isRecordingAllowed());
        return ToolResult.ok(data, null);
    }

    private ToolResult verifyIdentity(VoiceCall call, Map<String, Object> args) {
        Student s = student(call);
        if (s == null) {
            return ToolResult.error(ToolResult.NOT_AVAILABLE,
                    "No student record is linked to this call, so identity cannot be verified. Offer a callback.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (call.getVerificationLevel() >= 1) {
            data.put("verified", true);
            data.put("attempts_left", MAX_VERIFY_ATTEMPTS - call.getVerifyFailures());
            return ToolResult.ok(data, null);
        }
        if (call.getVerifyFailures() >= MAX_VERIFY_ATTEMPTS) {
            data.put("verified", false);
            data.put("attempts_left", 0);
            return ToolResult.ok(data, "I could not verify the details, so a counsellor will help you.");
        }
        int matched = 0;
        if (nameMatches(text(args, "student_full_name"), s.getFullName())) {
            matched++;
        }
        String last4 = text(args, "neet_roll_last4") == null ? "" : text(args, "neet_roll_last4").replaceAll("\\D", "");
        String roll = s.getNeetRollNo() == null ? "" : s.getNeetRollNo().replaceAll("\\s", "");
        if (last4.length() == 4 && roll.length() >= 4 && roll.endsWith(last4)) {
            matched++;
        }
        if (s.getHomeState() != null && squash(s.getHomeState()).equals(squash(text(args, "home_state")))) {
            matched++;
        }
        if ("PARENT".equalsIgnoreCase(text(args, "speaker_type"))) {
            call.setSpeakerType(PersonType.PARENT);
        } else if ("STUDENT".equalsIgnoreCase(text(args, "speaker_type"))) {
            call.setSpeakerType(PersonType.STUDENT);
        }
        if (matched >= 2) {
            call.verified();
            data.put("verified", true);
            data.put("attempts_left", MAX_VERIFY_ATTEMPTS - call.getVerifyFailures());
            return ToolResult.ok(data, "Thank you, that's verified.");
        }
        int left = MAX_VERIFY_ATTEMPTS - call.verificationFailed();
        data.put("verified", false);
        data.put("attempts_left", Math.max(left, 0));
        if (left <= 0) {
            // Locked for the rest of the call: nothing personal is shared and a person takes over.
            Handoff h = handoffs.handoff(call, HandoffReason.VERIFICATION_FAILED,
                    "Identity could not be verified after 3 attempts. No personal details were shared.");
            data.put("handoff_mode", h.mode());
            return ToolResult.ok(data, "I could not verify the details. " + h.message());
        }
        return ToolResult.ok(data, "That did not match our records. Could you check and tell me again?");
    }

    /** Every word of the registered name (initials aside) must be in what the caller said. */
    static boolean nameMatches(String given, String registered) {
        Set<String> said = words(given);
        Set<String> expected = words(registered);
        return !expected.isEmpty() && said.containsAll(expected);
    }

    private static Set<String> words(String name) {
        Set<String> out = new HashSet<>();
        if (name != null) {
            for (String w : name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
                if (w.length() > 1) {
                    out.add(w);
                }
            }
        }
        return out;
    }

    private static String squash(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
    }

    // ------------------------------------------------------------------ read-only facts

    private ToolResult deadlines(VoiceCall call, Map<String, Object> args) {
        Student s = student(call);
        boolean personal = s != null && call.getVerificationLevel() >= 1;
        List<DeadlineFact> list = (personal ? facts.personalDeadlines(s, 60) : facts.generalDeadlines(30)).stream()
                .limit(3).toList();
        if (list.isEmpty()) {
            return ToolResult.error(ToolResult.NOT_AVAILABLE, personal
                    ? "No upcoming deadline is recorded for this student."
                    : "No counselling date is published for the coming weeks.");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DeadlineFact d : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", d.type());
            row.put("authority", d.authority());
            row.put("round", d.round());
            row.put("date_time_ist", d.at().atZone(VoiceFacts.IST).toOffsetDateTime().toString());
            row.put("spoken", VoiceFacts.when(d.at()));
            row.put("days_left", d.daysLeft());
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", personal ? "PERSONAL" : "GENERAL");
        data.put("deadlines", rows);
        return ToolResult.ok(data, (personal ? "The next date is: " : "The next published date is: ")
                + list.get(0).spoken() + ".");
    }

    private ToolResult roundStatus(VoiceCall call, Map<String, Object> args) {
        AuthorityType type = "STATE".equalsIgnoreCase(text(args, "authority")) ? AuthorityType.STATE
                : AuthorityType.CENTRAL;
        return facts.roundStatus(student(call), type).map(r -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("authority", r.authority());
            data.put("round", r.round());
            data.put("stage", r.stage());
            data.put("result_out", r.resultOut());
            data.put("decision_window_end", r.decisionWindowEnd() == null ? null
                    : VoiceFacts.when(r.decisionWindowEnd()));
            return ToolResult.ok(data, r.round() + " is at the stage: "
                    + r.stage().toLowerCase(Locale.ROOT).replace('_', ' ') + ".");
        }).orElseGet(() -> ToolResult.error(ToolResult.NOT_AVAILABLE,
                "This student is not registered for that counselling in our records."));
    }

    private ToolResult allotment(VoiceCall call, Map<String, Object> args) {
        return facts.latestAllotment(student(call)).map(a -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("round", a.round());
            data.put("college", a.college());
            data.put("quota", a.quota());
            data.put("category", a.category());
            data.put("decision_status", a.decision());
            data.put("decision_deadline", a.decisionDeadline() == null ? null : VoiceFacts.when(a.decisionDeadline()));
            return ToolResult.ok(data, "The seat allotted in " + a.round() + " is at " + a.college() + ".");
        }).orElseGet(() -> ToolResult.error(ToolResult.NOT_AVAILABLE, "No seat allotment is recorded yet."));
    }

    private ToolResult documentStatus(VoiceCall call, Map<String, Object> args) {
        VoiceFacts.DocumentFact d = facts.documents(student(call));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("required", d.required());
        data.put("verified", d.done());
        data.put("missing", d.missing());
        data.put("expiring", d.expiring());
        String hint = d.missing().isEmpty() ? "All required documents are in."
                : d.missing().size() + " required documents are still needed, including "
                        + String.join(" and ", d.missing().stream().limit(2).toList()) + ".";
        return ToolResult.ok(data, hint);
    }

    private ToolResult feeStatus(VoiceCall call, Map<String, Object> args) {
        return facts.nextFee(student(call)).map(f -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("instalment", f.label());
            data.put("amount_due_inr", f.amount());
            data.put("due_date", f.dueDate().toString());
            data.put("overdue", f.overdue());
            return ToolResult.ok(data, "An instalment of " + f.amount().stripTrailingZeros().toPlainString()
                    + " rupees " + (f.overdue() ? "was due on " : "is due on ") + VoiceFacts.day(f.dueDate()) + ".");
        }).orElseGet(() -> ToolResult.error(ToolResult.NOT_AVAILABLE, "No fee instalment is pending."));
    }

    private ToolResult refundRule(VoiceCall call, Map<String, Object> args) {
        return facts.refundRule(student(call)).map(lines -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("rule", lines.stream().map(VoiceTools::clean).toList());
            data.put("note", "Information only. A counsellor must confirm before any decision is taken.");
            return ToolResult.ok(data, null);
        }).orElseGet(() -> ToolResult.error(ToolResult.NOT_AVAILABLE, "No seat allotment is recorded yet."));
    }

    private ToolResult predictor(VoiceCall call, Map<String, Object> args) {
        List<VoiceFacts.ShortlistFact> list = facts.shortlist(student(call), 3);
        if (list.isEmpty()) {
            return ToolResult.error(ToolResult.NOT_AVAILABLE, "No college shortlist has been saved yet.");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VoiceFacts.ShortlistFact f : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("college", f.college());
            row.put("chance_band", f.band());
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shortlist", rows);
        data.put("disclaimer", DISCLAIMER);
        return ToolResult.ok(data, DISCLAIMER);
    }

    private ToolResult searchFaq(VoiceCall call, Map<String, Object> args) {
        String query = text(args, "query");
        if (query == null) {
            return ToolResult.error(ToolResult.INVALID_ARGUMENT, "A question is needed.");
        }
        Answer answer = assistant.answer(call.getLanguage(), query);
        if (answer.answer() == null && call.getLanguage() != Language.ENGLISH) {
            answer = assistant.answer(Language.ENGLISH, query);
        }
        if (answer.answer() == null) {
            return ToolResult.error(ToolResult.NOT_AVAILABLE,
                    "Nothing in the knowledge base answers this. Offer a counsellor callback.");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<PublicFaq> found = new ArrayList<>(List.of(answer.answer()));
        found.addAll(answer.related());
        for (PublicFaq f : found) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("question", clean(f.question()));
            row.put("answer", clean(f.answer()));
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answers", rows);
        return ToolResult.ok(data, clean(answer.answer().answer()));
    }

    // ------------------------------------------------------------------ the four tools that change something

    private ToolResult scheduleCallback(VoiceCall call, Map<String, Object> args) {
        if (!call.isTestCall() && call.getStudentId() == null && call.getLeadId() == null) {
            // An unknown caller who wants a callback becomes a lead, so the counsellor has someone to call.
            Lead lead = new Lead();
            String name = text(args, "caller_name");
            lead.setFullName(name == null ? "Caller " + HandoffService.mask(call.getPhone()) : cut(name, 120));
            lead.setPhone(call.getPhone());
            lead.setSource(LeadSource.PHONE);
            lead.setLanguagePreference(call.getLanguage());
            lead.setNotes("Rang in and spoke to the AI agent; asked for a call back.");
            leads.save(lead);
            audit.record(null, "LEAD_CREATED", "LEAD", lead.getId(), "source=AI_CALL");
            call.setLeadId(lead.getId());
        }
        String reason = text(args, "reason");
        Handoff h = handoffs.callback(call, text(args, "preferred_time_text"),
                reason == null ? "Asked for a call back." : reason);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callback_id", h.callbackId());
        data.put("confirmed_slot", VoiceFacts.when(h.dueAt()));
        return ToolResult.ok(data, h.message());
    }

    private ToolResult handoff(VoiceCall call, Map<String, Object> args) {
        HandoffReason reason;
        try {
            reason = HandoffReason.valueOf(String.valueOf(text(args, "reason_code")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            reason = HandoffReason.OUT_OF_SCOPE;
        }
        String summary = text(args, "summary");
        Handoff h = handoffs.handoff(call, reason, summary == null ? "No summary was given." : summary);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("handoff_mode", h.mode());
        data.put("message", h.message());
        return ToolResult.ok(data, h.message());
    }

    private ToolResult logOutcome(VoiceCall call, Map<String, Object> args) {
        Outcome outcome;
        try {
            outcome = Outcome.valueOf(String.valueOf(text(args, "outcome_code")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(ToolResult.INVALID_ARGUMENT, "Unknown outcome code.");
        }
        // An opt-out or a failed identity check is never overwritten by a politer-sounding ending.
        if (call.getOutcome() != Outcome.OPTED_OUT && call.getOutcome() != Outcome.VERIFICATION_FAILED) {
            call.setOutcome(outcome);
        }
        call.setOutcomeNote(cut(text(args, "note"), 1000));
        return ToolResult.ok(Map.of("recorded", true), null);
    }

    private ToolResult optOut(VoiceCall call, Map<String, Object> args) {
        boolean allCalls = "ALL_CALLS".equalsIgnoreCase(text(args, "scope"));
        call.setOutcome(Outcome.OPTED_OUT);
        if (!call.isTestCall()) {
            PersonType type = call.getStudentId() != null ? PersonType.STUDENT : PersonType.LEAD;
            Long personId = call.getStudentId() != null ? call.getStudentId() : call.getLeadId();
            consent.optOut(call.getPhone(), type, personId,
                    "Asked on an AI call to stop " + (allCalls ? "all calls" : "AI calls"));
            if (allCalls) {
                handoffs.inform(call, Priority.URGENT, "Asked not to be called at all",
                        "Said on an AI call that they do not want any more calls. AI calls are already stopped; "
                                + "please respect this for calls made by staff too.");
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("confirmed", true);
        data.put("scope", allCalls ? "ALL_CALLS" : "AI_CALLS");
        return ToolResult.ok(data, "I have noted that. You will not get these calls again.");
    }

    // ------------------------------------------------------------------ helpers

    private Student student(VoiceCall call) {
        return call.getStudentId() == null ? null : students.findById(call.getStudentId()).orElse(null);
    }

    private String firstName(VoiceCall call, Student s) {
        String name = s != null ? s.getFullName()
                : call.getLeadId() == null ? null : leads.findById(call.getLeadId()).map(Lead::getFullName).orElse(null);
        return name == null ? null : name.trim().split("\\s+")[0];
    }

    private static String text(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : cut(s, 500);
    }

    /** Text read out by the agent is data, never instructions: control characters out, length capped. */
    static String clean(String s) {
        return s == null ? null : cut(s.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s{2,}", " ").trim(), 800);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    // ------------------------------------------------------------------ JSON Schema for the platform

    private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        out.put("properties", new java.util.TreeMap<>(properties));
        out.put("required", List.of(required));
        return out;
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> enumOf(String description, String... values) {
        return Map.of("type", "string", "description", description, "enum", List.of(values));
    }

}
