package com.mbbscrm.crm.voice;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.alert.AlertService;
import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.common.ActivityType;
import com.mbbscrm.crm.common.Language;
import com.mbbscrm.crm.common.Phones;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.engagement.CallLog;
import com.mbbscrm.crm.engagement.CallLogRepository;
import com.mbbscrm.crm.lead.Lead;
import com.mbbscrm.crm.lead.LeadActivity;
import com.mbbscrm.crm.lead.LeadActivityRepository;
import com.mbbscrm.crm.lead.LeadRepository;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentRepository;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.voice.Voice.CallStatus;
import com.mbbscrm.crm.voice.Voice.Direction;
import com.mbbscrm.crm.voice.Voice.Outcome;
import com.mbbscrm.crm.voice.Voice.Purpose;
import com.mbbscrm.crm.voice.Voice.SkipReason;
import com.mbbscrm.crm.voice.Voice.TargetStatus;
import com.mbbscrm.crm.voice.VoicePlatformClient.OutboundCall;
import com.mbbscrm.crm.voice.VoicePlatformClient.Placed;

import tools.jackson.databind.ObjectMapper;

/**
 * The life of one AI call: placing it (or recording it as simulated), recognising an incoming caller,
 * and closing it when the platform reports the end. Closing a call stores the transcript, writes the
 * communication log, and moves the campaign target on (spec 18.5).
 */
@Service
public class VoiceCallService {

    static final Set<CallStatus> LIVE = EnumSet.of(CallStatus.QUEUED, CallStatus.DIALING, CallStatus.CONNECTED);
    private static final int TRANSCRIPT_LIMIT = 95_000;
    private static final Duration STALE_AFTER = Duration.ofMinutes(30);

    /** One line of a conversation. {@code tool} and {@code status} are set for the agent's tool calls. */
    public record Line(String role, String text, String tool, String status, Instant ts) {
        public static Line agent(String text) {
            return new Line("agent", text, null, null, Instant.now());
        }

        public static Line caller(String text) {
            return new Line("caller", text, null, null, Instant.now());
        }

        public static Line tool(String tool, String status, String text) {
            return new Line("tool", text, tool, status, Instant.now());
        }
    }

    /** What the platform is told when someone rings in. {@code fallback}: play the ordinary greeting instead. */
    public record Inbound(boolean fallback, UUID callId, Purpose purpose, Map<String, String> variables,
                          String openingLine, String systemPrompt, String model) {
        static Inbound useFallback() {
            return new Inbound(true, null, null, Map.of(), null, null, null);
        }
    }

    /** The platform's end-of-call report, already translated into our terms by the adapter. */
    public record Ended(UUID callId, String providerCallId, String status, List<Line> transcript, String recordingUrl,
                        Integer durationSec, String disconnectReason, String summary) {
    }

    private final VoiceCallRepository calls;
    private final VoiceCampaignTargetRepository targets;
    private final VoiceCampaignRepository campaigns;
    private final VoiceWebhookEventRepository events;
    private final VoiceSettingRepository settings;
    private final VoiceScriptService scripts;
    private final ConsentService consent;
    private final VoiceFacts facts;
    private final VoicePlatforms platforms;
    private final VoiceProperties props;
    private final StudentRepository students;
    private final LeadRepository leads;
    private final CallLogRepository callLogs;
    private final LeadActivityRepository activities;
    private final AppUserRepository users;
    private final AlertService alerts;
    private final ObjectMapper mapper;
    private final String orgName;

    public VoiceCallService(VoiceCallRepository calls, VoiceCampaignTargetRepository targets,
                            VoiceCampaignRepository campaigns, VoiceWebhookEventRepository events,
                            VoiceSettingRepository settings, VoiceScriptService scripts, ConsentService consent,
                            VoiceFacts facts, VoicePlatforms platforms, VoiceProperties props,
                            StudentRepository students, LeadRepository leads, CallLogRepository callLogs,
                            LeadActivityRepository activities, AppUserRepository users, AlertService alerts,
                            ObjectMapper mapper, @Value("${app.org-name}") String orgName) {
        this.calls = calls;
        this.targets = targets;
        this.campaigns = campaigns;
        this.events = events;
        this.settings = settings;
        this.scripts = scripts;
        this.consent = consent;
        this.facts = facts;
        this.platforms = platforms;
        this.props = props;
        this.students = students;
        this.leads = leads;
        this.callLogs = callLogs;
        this.activities = activities;
        this.users = users;
        this.alerts = alerts;
        this.mapper = mapper;
        this.orgName = orgName;
    }

    // ------------------------------------------------------------------ the kill switch

    /** False when the feature is off or "Pause all" is on: nothing is dialled and inbound gets the fallback. */
    @Transactional(readOnly = true)
    public boolean callingAllowed() {
        return props.enabled() && !paused();
    }

    @Transactional(readOnly = true)
    public boolean paused() {
        return settings.findById(VoiceSetting.ID).map(VoiceSetting::isPaused).orElse(false);
    }

    @Transactional
    public void setPaused(boolean paused, Long userId) {
        settings.findById(VoiceSetting.ID).orElseThrow().set(paused, userId);
    }

    // ------------------------------------------------------------------ outbound

    /**
     * Places one campaign call. With no provider connected the call is saved as SIMULATED: what would
     * have been said is kept, the target is marked as simulated, and no phone rings.
     */
    @Transactional
    public VoiceCall placeOutbound(VoiceCampaign campaign, VoiceCampaignTarget target) {
        Student student = target.getStudentId() == null ? null : students.findById(target.getStudentId()).orElse(null);
        Lead lead = target.getLeadId() == null ? null : leads.findById(target.getLeadId()).orElse(null);
        Language language = student != null ? student.getLanguagePreference()
                : lead != null ? lead.getLanguagePreference() : Language.ENGLISH;
        VoiceCall call = new VoiceCall(Direction.OUTBOUND, campaign.getPurpose(), target.getStudentId(),
                target.getLeadId(), target.getPhone(), language, platforms.current().name().toUpperCase(Locale.ROOT));
        call.setCampaignId(campaign.getId());
        call.setRecordingAllowed(consent.allowsRecording(target.getPhone()));
        VoiceScript script = scripts.approved(campaign.getPurpose(), language).orElse(null);
        Map<String, String> vars = variables(campaign.getPurpose(), language, student, lead,
                campaign.getLookaheadDays());
        String opening = script == null ? "" : VoiceScriptService.render(script.getOpeningLine(), vars);
        if (script != null) {
            call.setScriptId(script.getId());
        }
        calls.save(call);
        target.setLastCallId(call.getId());

        if (script == null) {
            // start() refuses a campaign without an approved script; this covers one retired afterwards.
            close(call, CallStatus.FAILED, "NO_APPROVED_SCRIPT");
            target.setNextAttemptAt(Instant.now().plus(Duration.ofMinutes(30)));
            return call;
        }
        if (!platforms.live()) {
            call.setStatus(CallStatus.SIMULATED);
            call.setEndedAt(Instant.now());
            call.setSummary("Not dialled: no telephony or voice-AI provider is connected. The agent would have "
                    + "opened with: \"" + opening + "\"");
            target.finish(TargetStatus.SIMULATED);
            return call;
        }
        Placed placed;
        try {
            placed = platforms.current().createOutboundCall(new OutboundCall(call.getId(), e164(target.getPhone()),
                    campaign.getPurpose(), language, script.getModel(),
                    VoiceScriptService.render(script.getSystemPrompt(), vars), opening, vars,
                    call.isRecordingAllowed(), 300));
        } catch (RuntimeException e) {
            placed = Placed.failed(e.getClass().getSimpleName());
        }
        if (placed.accepted()) {
            call.setStatus(CallStatus.DIALING);
            call.setProviderCallId(placed.providerCallId());
            // Held back until the platform reports the end, so the same number is not dialled twice.
            target.setNextAttemptAt(Instant.now().plus(STALE_AFTER));
        } else {
            close(call, CallStatus.FAILED, cut(placed.error(), 40));
            target.setNextAttemptAt(Instant.now().plus(Duration.ofMinutes(10)));
        }
        return call;
    }

    /** Only what the purpose needs (spec 18.6): never a college, an amount or a document name. */
    Map<String, String> variables(Purpose purpose, Language language, Student student, Lead lead, int lookaheadDays) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("consultancy_name", orgName);
        vars.put("purpose", purpose.name());
        vars.put("language", language.name());
        String name = student != null ? student.getFullName() : lead != null ? lead.getFullName() : null;
        vars.put("first_name", name == null ? "" : name.trim().split("\\s+")[0]);
        if (purpose == Purpose.DEADLINE_REMINDER && student != null) {
            facts.personalDeadlines(student, lookaheadDays).stream().findFirst()
                    .ifPresent(d -> vars.put("deadline_text", VoiceFacts.when(d.at())));
        }
        return vars;
    }

    // ------------------------------------------------------------------ inbound

    /**
     * Someone rang the consultancy number (spec 18.5.3). A number that belongs to exactly one student gets
     * a status call; a lead or an unknown number gets general questions only. Either way the call starts
     * unverified, so nothing personal is said until verify_identity succeeds.
     */
    @Transactional
    public Inbound inbound(String callerNumber, String providerCallId) {
        if (!callingAllowed()) {
            return Inbound.useFallback();
        }
        if (providerCallId != null) {
            VoiceCall existing = calls.findByProviderCallId(providerCallId).orElse(null);
            if (existing != null) {
                return describe(existing);
            }
        }
        String phone = Phones.normalize(callerNumber);
        if (phone == null || phone.isBlank()) {
            phone = "unknown";
        }
        List<Student> family = students.findByPhoneOrParentPhone(phone, phone);
        Student student = family.size() == 1 ? family.get(0) : null;
        Lead lead = student != null || !family.isEmpty() ? null
                : leads.findByAnyPhone(phone).stream().findFirst().orElse(null);
        Purpose purpose = student != null ? Purpose.INBOUND_STATUS : Purpose.INBOUND_FAQ;
        Language language = student != null ? student.getLanguagePreference()
                : lead != null ? lead.getLanguagePreference() : Language.ENGLISH;
        if (scripts.approved(purpose, language).isEmpty()) {
            return Inbound.useFallback();
        }
        VoiceCall call = new VoiceCall(Direction.INBOUND, purpose, student == null ? null : student.getId(),
                lead == null ? null : lead.getId(), phone, language,
                platforms.current().name().toUpperCase(Locale.ROOT));
        call.setProviderCallId(providerCallId);
        call.setStatus(CallStatus.CONNECTED);
        call.setStartedAt(Instant.now());
        call.setRecordingAllowed(consent.allowsRecording(phone));
        scripts.approved(purpose, language).ifPresent(s -> call.setScriptId(s.getId()));
        calls.save(call);
        return describe(call);
    }

    private Inbound describe(VoiceCall call) {
        Student student = call.getStudentId() == null ? null : students.findById(call.getStudentId()).orElse(null);
        Lead lead = call.getLeadId() == null ? null : leads.findById(call.getLeadId()).orElse(null);
        Map<String, String> vars = variables(call.getPurpose(), call.getLanguage(), student, lead, 0);
        VoiceScript script = scripts.approved(call.getPurpose(), call.getLanguage()).orElse(null);
        return new Inbound(script == null, call.getId(), call.getPurpose(), vars,
                script == null ? null : VoiceScriptService.render(script.getOpeningLine(), vars),
                script == null ? null : VoiceScriptService.render(script.getSystemPrompt(), vars),
                script == null ? null : script.getModel());
    }

    // ------------------------------------------------------------------ webhooks

    /** The called person picked up. Returns false for a repeat delivery or an unknown call. */
    @Transactional
    public boolean started(UUID callId, String providerCallId) {
        VoiceCall call = find(callId, providerCallId);
        if (call == null || !firstDelivery(call, "call-started") || !LIVE.contains(call.getStatus())) {
            return false;
        }
        call.setStatus(CallStatus.CONNECTED);
        call.setStartedAt(Instant.now());
        return true;
    }

    /** The conversation so far, for platforms that send it while the call is still going. */
    @Transactional
    public boolean transcript(UUID callId, String providerCallId, List<Line> lines) {
        VoiceCall call = find(callId, providerCallId);
        if (call == null || !LIVE.contains(call.getStatus()) || lines.isEmpty()) {
            return false;
        }
        call.setTranscript(toJson(lines));
        return true;
    }

    /** The call is over. Delivering the same report twice changes nothing the second time (spec 18.5.4). */
    @Transactional
    public boolean ended(Ended e) {
        VoiceCall call = find(e.callId(), e.providerCallId());
        if (call == null || !firstDelivery(call, "call-ended") || !LIVE.contains(call.getStatus())) {
            return false;
        }
        CallStatus status = switch (e.status() == null ? "" : e.status().toLowerCase(Locale.ROOT)) {
            case "no_answer", "busy", "no-answer", "voicemail" -> CallStatus.NO_ANSWER;
            case "failed", "error" -> CallStatus.FAILED;
            default -> CallStatus.COMPLETED;
        };
        if (e.transcript() != null && !e.transcript().isEmpty()) {
            call.setTranscript(toJson(e.transcript()));
        }
        // A recording is kept only when this number agreed to be recorded.
        if (call.isRecordingAllowed() && e.recordingUrl() != null && !e.recordingUrl().isBlank()) {
            call.setRecordingRef(cut(e.recordingUrl(), 500));
        }
        call.setDurationSec(e.durationSec());
        if (e.summary() != null && !e.summary().isBlank()) {
            call.setSummary(cut(e.summary().trim(), 2000));
        }
        close(call, status, e.disconnectReason());
        return true;
    }

    private VoiceCall find(UUID callId, String providerCallId) {
        if (callId != null) {
            return calls.findById(callId).orElse(null);
        }
        return providerCallId == null ? null : calls.findByProviderCallId(providerCallId).orElse(null);
    }

    private boolean firstDelivery(VoiceCall call, String event) {
        String key = call.getProviderCallId() != null ? call.getProviderCallId() : call.getId().toString();
        if (events.existsByProviderCallIdAndEventType(key, event)) {
            return false;
        }
        events.save(new VoiceWebhookEvent(key, event));
        return true;
    }

    /** Calls the platform never reported back on stop holding a slot after half an hour. */
    @Transactional
    public int expireStale(Instant now) {
        List<VoiceCall> stale = calls.findByStatusInAndCreatedAtBefore(LIVE, now.minus(STALE_AFTER));
        stale.forEach(c -> close(c, CallStatus.FAILED, "NO_REPORT_FROM_PLATFORM"));
        return stale.size();
    }

    // ------------------------------------------------------------------ closing a call

    /** Final bookkeeping for every call that was really placed or answered. Test calls only get a status. */
    void close(VoiceCall call, CallStatus status, String disconnectReason) {
        call.setStatus(status);
        call.setEndedAt(Instant.now());
        call.setDisconnectReason(cut(disconnectReason, 40));
        if (status == CallStatus.COMPLETED && call.getOutcome() == null) {
            call.setOutcome(Outcome.NO_INTERACTION);
        }
        if (call.getDurationSec() == null && call.getStartedAt() != null) {
            call.setDurationSec((int) Math.max(0, ChronoUnit.SECONDS.between(call.getStartedAt(), call.getEndedAt())));
        }
        if (call.getSummary() == null) {
            call.setSummary(defaultSummary(call));
        }
        if (call.isTestCall()) {
            return;
        }
        logCommunication(call);
        updateTarget(call);
        if (call.getOutcome() == Outcome.VERIFICATION_FAILED) {
            flagRepeatedFailures(call);
        }
    }

    private static String defaultSummary(VoiceCall call) {
        String what = words(call.getPurpose().name()) + " call";
        return switch (call.getStatus()) {
            case NO_ANSWER -> what + ": nobody answered.";
            case FAILED -> what + " could not be placed.";
            default -> what + ": " + (call.getOutcome() == null ? "ended" : words(call.getOutcome().name()))
                    + (call.getOutcomeNote() == null ? "." : ". " + call.getOutcomeNote());
        };
    }

    /** Every real call appears in the student's or lead's call history with a link to its transcript. */
    private void logCommunication(VoiceCall call) {
        if (call.getStatus() == CallStatus.FAILED || (call.getStudentId() == null && call.getLeadId() == null)) {
            return;
        }
        CallLog.Outcome outcome = call.getStatus() == CallStatus.NO_ANSWER ? CallLog.Outcome.NO_ANSWER
                : call.getOutcome() == Outcome.WRONG_PERSON ? CallLog.Outcome.WRONG_NUMBER
                : call.getOutcome() == Outcome.CALLBACK_REQUESTED || call.getOutcome() == Outcome.HANDED_OFF
                        ? CallLog.Outcome.CALL_BACK_LATER : CallLog.Outcome.CONNECTED;
        CallLog.Direction direction = call.getDirection() == Direction.INBOUND ? CallLog.Direction.INBOUND
                : CallLog.Direction.OUTBOUND;
        // A call is logged against one record: the student if there is one, otherwise the lead.
        Long leadId = call.getStudentId() == null ? call.getLeadId() : null;
        String notes = "AI agent. " + call.getSummary();
        callLogs.save(CallLog.byAgent(leadId, call.getStudentId(), call.getPhone(), direction, outcome,
                call.getDurationSec(), cut(notes, 2000), call.getId().toString()));
        if (leadId != null) {
            activities.save(new LeadActivity(leadId, ActivityType.CALL,
                    "AI call: " + (call.getStatus() == CallStatus.NO_ANSWER ? "no answer"
                            : words(String.valueOf(call.getOutcome()))), cut(call.getSummary(), 2000), null));
        }
    }

    /** Attempts count only for a connected call or a genuine no-answer (spec 18.10.2). */
    private void updateTarget(VoiceCall call) {
        VoiceCampaignTarget target = targets.findFirstByLastCallId(call.getId()).orElse(null);
        if (target == null || target.getStatus() != TargetStatus.PENDING) {
            return;
        }
        VoiceCampaign campaign = campaigns.findById(target.getCampaignId()).orElseThrow();
        Instant now = Instant.now();
        if (call.getStatus() == CallStatus.FAILED) {
            target.setNextAttemptAt(now.plus(Duration.ofMinutes(10)));
            return;
        }
        target.countAttempt();
        boolean retry = call.getStatus() == CallStatus.NO_ANSWER || call.getOutcome() == Outcome.NO_INTERACTION;
        if (!retry) {
            switch (call.getOutcome()) {
                case OPTED_OUT -> target.skip(SkipReason.OPTED_OUT);
                case NOT_INTERESTED -> target.skip(SkipReason.NOT_INTERESTED);
                case WRONG_PERSON -> target.skip(SkipReason.WRONG_PERSON);
                default -> target.finish(TargetStatus.DONE);
            }
        } else if (target.getAttempts() >= campaign.getMaxAttempts()) {
            target.skip(SkipReason.MAX_ATTEMPTS);
        } else {
            LocalTime start = CallEligibility.later(campaign.getWindowStart(), props.windowStart());
            LocalTime end = CallEligibility.earlier(campaign.getWindowEnd(), props.windowEnd());
            target.setNextAttemptAt(CallEligibility.nextRetry(now, campaign.getRetryGapMin(), start, end));
        }
    }

    /** Three failed identity checks from one number in a day is worth a person's attention (spec 18.12). */
    private void flagRepeatedFailures(VoiceCall call) {
        long failures = calls.countByPhoneAndOutcomeAndTestCallFalseAndCreatedAtGreaterThanEqual(call.getPhone(),
                Outcome.VERIFICATION_FAILED, Instant.now().minus(Duration.ofHours(24)));
        if (failures < 3) {
            return;
        }
        String masked = HandoffService.mask(call.getPhone());
        users.findByActiveTrueAndRoleInOrderByFullName(List.of(Role.SUPER_ADMIN)).forEach(admin ->
                alerts.notifyUser(admin.getId(), call.getStudentId(), "VOICE_VERIFICATION", Priority.URGENT,
                        "Identity checks failed 3 times from " + masked,
                        "AI calls with this number failed identity verification " + failures + " times in 24 hours. "
                                + "Check whether someone is trying to get a student's details.",
                        "/voice/calls/" + call.getId(),
                        "VVF:" + DndService.sha256(call.getPhone()).substring(0, 16) + ":" + java.time.LocalDate.now()));
    }

    // ------------------------------------------------------------------ transcripts

    String toJson(List<Line> lines) {
        List<Line> kept = new ArrayList<>(lines);
        String json = mapper.writeValueAsString(kept);
        while (json.length() > TRANSCRIPT_LIMIT && kept.size() > 1) {
            kept = new ArrayList<>(kept.subList(0, kept.size() / 2));
            json = mapper.writeValueAsString(kept);
        }
        return json;
    }

    List<Line> lines(VoiceCall call) {
        if (call.getTranscript() == null || call.getTranscript().isBlank()) {
            return List.of();
        }
        return mapper.readValue(call.getTranscript(),
                mapper.getTypeFactory().constructCollectionType(List.class, Line.class));
    }

    static String e164(String phone) {
        return phone != null && phone.matches("\\d{10}") ? "+91" + phone : phone;
    }

    static String words(String code) {
        String s = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
