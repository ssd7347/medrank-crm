package com.aicallcrm.crm.voice;

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

import com.aicallcrm.crm.alert.Priority;
import com.aicallcrm.crm.assistant.AssistantService;
import com.aicallcrm.crm.assistant.AssistantService.Answer;
import com.aicallcrm.crm.assistant.AssistantService.PublicFaq;
import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.contact.Contact;
import com.aicallcrm.crm.contact.ContactRepository;
import com.aicallcrm.crm.contact.ContactService;
import com.aicallcrm.crm.contact.ContactStatus;
import com.aicallcrm.crm.contact.FieldDefinition;
import com.aicallcrm.crm.contact.FieldService;
import com.aicallcrm.crm.settings.SettingsService;
import com.aicallcrm.crm.voice.ContactFacts.Fact;
import com.aicallcrm.crm.voice.HandoffService.Handoff;
import com.aicallcrm.crm.voice.Voice.HandoffReason;
import com.aicallcrm.crm.voice.Voice.Outcome;

/**
 * The tools the agent may use during a call. Rules every handler keeps: the contact comes from our own record
 * of the call, never from an argument; replies hold only what may be spoken (fields the admin marked for the
 * agent, private ones only after verification); and only four tools change anything: callback, handoff,
 * outcome and opt-out.
 */
@Component
public class VoiceTools {

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
    private final ContactRepository contacts;
    private final ContactService contactService;
    private final ContactFacts facts;
    private final AssistantService assistant;
    private final HandoffService handoffs;
    private final ConsentService consent;
    private final SettingsService settings;
    private final AuditService audit;

    public VoiceTools(ContactRepository contacts, ContactService contactService, ContactFacts facts,
                      AssistantService assistant, HandoffService handoffs, ConsentService consent,
                      SettingsService settings, AuditService audit) {
        this.contacts = contacts;
        this.contactService = contactService;
        this.facts = facts;
        this.assistant = assistant;
        this.handoffs = handoffs;
        this.consent = consent;
        this.settings = settings;
        this.audit = audit;

        add("get_call_context", 0, "Returns why this call is happening, the caller's first name, and whether (and how) "
                + "identity must be checked before private details are shared. Call it first.", schema(Map.of()),
                this::callContext);
        add("verify_identity", 0, "Checks the caller's identity. Ask for their full name and, if the call context "
                + "lists any, one of the verification questions; then call this. At most 3 attempts.",
                schema(Map.of(
                        "full_name", str("The full name as the caller said it."),
                        "answers", Map.of("type", "object", "description",
                                "Answers to the verification questions, keyed by the question's key.",
                                "additionalProperties", Map.of("type", "string")))), this::verifyIdentity);
        add("get_contact_details", 0, "Returns the details about this customer that you may share (for example "
                + "amount due, due date, appointment time, plan). Private details appear only after verify_identity. "
                + "Never state a detail this tool did not return.", schema(Map.of()), this::contactDetails);
        add("search_faq", 0, "Searches the company's approved answers to general questions (opening hours, prices, "
                + "policies). Answer only from what it returns.",
                schema(Map.of("query", str("The caller's question in their own words.")), "query"), this::searchFaq);
        add("schedule_callback", 0, "Books a call-back from a member of the team when the caller is busy or wants to "
                + "talk to a person later.",
                schema(Map.of(
                        "preferred_time_text", str("When the caller would like the call, in their words."),
                        "reason", str("Why they want the call-back, in one sentence."),
                        "caller_name", str("The caller's name, if they are not already known."))),
                this::scheduleCallback);
        add("request_human_handoff", 0, "Use when the caller complains, disputes a charge, asks for a refund or "
                + "mentions legal action, sounds distressed, asks for a person, or a tool failed twice.",
                schema(Map.of(
                        "reason_code", enumOf("Why a person is needed.",
                                Arrays.stream(HandoffReason.values()).map(Enum::name).toArray(String[]::new)),
                        "summary", str("One or two sentences for the team member.")), "reason_code", "summary"),
                this::handoff);
        add("log_call_outcome", 0, "Records how the call ended. Call it once, just before saying goodbye. "
                + "Use WILL_ACT with the promised date in the note when they promise to pay or act; INTERESTED "
                + "when they want to go ahead.",
                schema(Map.of(
                        "outcome_code", enumOf("How the call ended.",
                                Arrays.stream(Outcome.values()).map(Enum::name).toArray(String[]::new)),
                        "note", str("Anything the team should know, such as a promised date or a feedback score.")),
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

    // ------------------------------------------------------------------ context, verification, details

    private ToolResult callContext(VoiceCall call, Map<String, Object> args) {
        Contact c = contact(call);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("call_reason", call.getScriptName());
        data.put("direction", call.getDirection().name());
        data.put("company_name", settings.branding().companyName());
        data.put("first_name", c == null ? null : c.firstName());
        data.put("known_contact", c != null);
        data.put("verified", call.getVerificationLevel() >= 1);
        boolean needed = c != null && call.getVerificationLevel() < 1 && facts.hasPrivateDetails(c);
        data.put("verification_required", needed);
        List<Map<String, Object>> questions = new ArrayList<>();
        questions.add(Map.of("key", "full_name", "ask_for", "their full name"));
        // Only questions this contact has an answer on file for.
        Map<String, String> values = c == null ? Map.of() : facts.values(c);
        for (FieldDefinition f : facts.verificationFields()) {
            if (values.get(f.getFieldKey()) == null) {
                continue;
            }
            questions.add(Map.of("key", f.getFieldKey(), "ask_for", f.getLabel()));
        }
        data.put("verification_questions", c == null ? List.of() : questions);
        data.put("recording", call.isRecordingAllowed());
        return ToolResult.ok(data, null);
    }

    private ToolResult verifyIdentity(VoiceCall call, Map<String, Object> args) {
        Contact c = contact(call);
        if (c == null) {
            return ToolResult.error(ToolResult.NOT_AVAILABLE,
                    "This caller is not in our records, so identity cannot be checked. Offer a call-back.");
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
            return ToolResult.ok(data, "I could not verify the details, so a member of our team will help you.");
        }
        boolean nameOk = nameMatches(text(args, "full_name"), c.getFullName());
        Map<String, String> answers = answers(args);
        Map<String, String> values = facts.values(c);
        List<FieldDefinition> checks = facts.verificationFields().stream()
                .filter(f -> values.get(f.getFieldKey()) != null).toList();
        boolean extraOk = checks.isEmpty() || checks.stream().anyMatch(f -> {
            String said = answers.getOrDefault(f.getFieldKey(), answers.get(f.getLabel().toLowerCase(Locale.ROOT)));
            return answerMatches(f, values.get(f.getFieldKey()), said);
        });
        if (nameOk && extraOk) {
            call.verified();
            data.put("verified", true);
            data.put("attempts_left", MAX_VERIFY_ATTEMPTS - call.getVerifyFailures());
            return ToolResult.ok(data, "Thank you, that's verified.");
        }
        int left = MAX_VERIFY_ATTEMPTS - call.verificationFailed();
        data.put("verified", false);
        data.put("attempts_left", Math.max(left, 0));
        if (left <= 0) {
            // Locked for the rest of the call: nothing private is shared and a person takes over.
            Handoff h = handoffs.handoff(call, HandoffReason.VERIFICATION_FAILED,
                    "Identity could not be verified after 3 attempts. No private details were shared.");
            data.put("handoff_mode", h.mode());
            return ToolResult.ok(data, "I could not verify the details. " + h.message());
        }
        return ToolResult.ok(data, "That did not match our records. Could you check and tell me again?");
    }

    private ToolResult contactDetails(VoiceCall call, Map<String, Object> args) {
        Contact c = contact(call);
        if (c == null) {
            return ToolResult.error(ToolResult.NOT_AVAILABLE, "This caller is not in our records. Offer a call-back.");
        }
        List<Fact> visible = facts.visible(c, call.getVerificationLevel());
        boolean more = call.getVerificationLevel() < 1 && facts.hasPrivateDetails(c);
        if (visible.isEmpty()) {
            return more ? ToolResult.error(ToolResult.VERIFICATION_REQUIRED, "Ask the caller to verify identity first.")
                    : ToolResult.error(ToolResult.NOT_AVAILABLE, "There are no details on record to share. Offer a call-back.");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Fact f : visible) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", f.key());
            row.put("label", f.label());
            row.put("value", clean(f.spoken()));
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("details", rows);
        data.put("more_after_verification", more);
        String hint = visible.stream().limit(3).map(f -> f.label() + ": " + f.spoken())
                .reduce((a, b) -> a + "; " + b).orElse("") + ".";
        return ToolResult.ok(data, clean(hint));
    }

    static boolean nameMatches(String given, String registered) {
        Set<String> said = words(given);
        Set<String> expected = words(registered);
        return !expected.isEmpty() && said.containsAll(expected);
    }

    /** Exact match ignoring case and spaces, or the last four characters of a longer value (an account number). */
    static boolean answerMatches(FieldDefinition f, String stored, String said) {
        if (stored == null || said == null || said.isBlank()) {
            return false;
        }
        String s = squash(said);
        String v = squash(stored);
        if (f.getFieldType() == FieldDefinition.Type.DATE || f.getFieldType() == FieldDefinition.Type.NUMBER
                || f.getFieldType() == FieldDefinition.Type.AMOUNT) {
            try {
                return FieldService.normalise(f.getFieldType(), said).equals(stored);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        if (s.equals(v)) {
            return true;
        }
        // "10231" for CUST-10231: callers say the number part of a reference.
        String storedDigits = stored.replaceAll("\\D", "");
        String saidDigits = said.replaceAll("\\D", "");
        if (storedDigits.length() >= 4 && saidDigits.equals(storedDigits)) {
            return true;
        }
        // The last four characters of a long reference, as on most phone lines.
        return v.length() >= 6 && s.length() == 4 && v.endsWith(s);
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
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]", "");
    }

    /** Answers may come as {"answers": {"customer_id": "..."}} or as top-level keys. */
    private static Map<String, String> answers(Map<String, Object> args) {
        Map<String, String> out = new LinkedHashMap<>();
        args.forEach((k, v) -> {
            if (v instanceof String s) {
                out.put(k.toLowerCase(Locale.ROOT), s);
            }
        });
        if (args.get("answers") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(String.valueOf(k).toLowerCase(Locale.ROOT), String.valueOf(v));
                }
            });
        }
        return out;
    }

    private ToolResult searchFaq(VoiceCall call, Map<String, Object> args) {
        String query = text(args, "query");
        if (query == null) {
            return ToolResult.error(ToolResult.INVALID_ARGUMENT, "A question is needed.");
        }
        Answer answer = assistant.answer(query);
        if (answer.answer() == null) {
            return ToolResult.error(ToolResult.NOT_AVAILABLE,
                    "Nothing in the knowledge base answers this. Offer a call-back from the team.");
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
        return ToolResult.ok(Map.of("answers", rows), clean(answer.answer().answer()));
    }

    // ------------------------------------------------------------------ the four tools that change something

    private ToolResult scheduleCallback(VoiceCall call, Map<String, Object> args) {
        if (!call.isTestCall() && call.getContactId() == null) {
            // An unknown caller who wants a call-back becomes a contact, so the team has someone to call.
            Contact c = new Contact();
            String name = text(args, "caller_name");
            c.setFullName(name == null ? "Caller " + HandoffService.mask(call.getPhone()) : cut(name, 120));
            c.setPhone(call.getPhone());
            c.setSource("AI call (rang in)");
            c.setNotes("Rang in and spoke to the AI agent; asked for a call back.");
            contacts.save(c);
            audit.record(null, "CONTACT_CREATED", "CONTACT", c.getId(), "source=AI_CALL");
            call.setContactId(c.getId());
        }
        String reason = text(args, "reason");
        Handoff h = handoffs.callback(call, text(args, "preferred_time_text"),
                reason == null ? "Asked for a call back." : reason);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callback_id", h.callbackId());
        data.put("confirmed_slot", ContactFacts.spokenTime(h.dueAt()));
        return ToolResult.ok(data, h.message());
    }

    private ToolResult handoff(VoiceCall call, Map<String, Object> args) {
        // Common invented reasons ("DISPUTE", "HUMAN") are understood (CallerSignals.REASON_ALIASES); anything else
        // would file the call under the wrong heading, so the model is told the valid ones and asks again.
        HandoffReason reason = CallerSignals.reason(text(args, "reason_code"));
        if (reason == null) {
            return ToolResult.error(ToolResult.INVALID_ARGUMENT, "reason_code must be one of: "
                    + String.join(", ", Arrays.stream(HandoffReason.values()).map(Enum::name).toList())
                    + ". A wrong person is not a hand-over: use log_call_outcome with WRONG_PERSON.");
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
            consent.optOut(call.getPhone(), call.getContactId(),
                    "Asked on an AI call to stop " + (allCalls ? "all calls" : "AI calls"));
            if (allCalls && call.getContactId() != null) {
                contactService.statusFromCall(call.getContactId(), ContactStatus.DO_NOT_CALL,
                        "Asked on an AI call not to be called at all");
                handoffs.inform(call, Priority.URGENT, "Asked not to be called at all",
                        "Said on an AI call that they do not want any more calls. The contact is now marked "
                                + "\"Do not call\"; please respect this for calls made by staff too.");
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("confirmed", true);
        data.put("scope", allCalls ? "ALL_CALLS" : "AI_CALLS");
        return ToolResult.ok(data, "I have noted that. You will not get these calls again.");
    }

    // ------------------------------------------------------------------ helpers

    private Contact contact(VoiceCall call) {
        return call.getContactId() == null ? null : contacts.findById(call.getContactId()).orElse(null);
    }

    private static String text(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || v instanceof Map<?, ?>) {
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
