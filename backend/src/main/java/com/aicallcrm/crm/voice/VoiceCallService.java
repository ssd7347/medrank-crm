package com.aicallcrm.crm.voice;

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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aicallcrm.crm.alert.AlertService;
import com.aicallcrm.crm.alert.Priority;
import com.aicallcrm.crm.common.Phones;
import com.aicallcrm.crm.common.Role;
import com.aicallcrm.crm.contact.Contact;
import com.aicallcrm.crm.contact.ContactRepository;
import com.aicallcrm.crm.contact.ContactService;
import com.aicallcrm.crm.contact.ContactStatus;
import com.aicallcrm.crm.engagement.CallLog;
import com.aicallcrm.crm.engagement.CallLogRepository;
import com.aicallcrm.crm.settings.SettingsService;
import com.aicallcrm.crm.settings.SettingsService.Branding;
import com.aicallcrm.crm.user.AppUserRepository;
import com.aicallcrm.crm.voice.Voice.CallStatus;
import com.aicallcrm.crm.voice.Voice.Direction;
import com.aicallcrm.crm.voice.Voice.Outcome;
import com.aicallcrm.crm.voice.Voice.SkipReason;
import com.aicallcrm.crm.voice.Voice.TargetStatus;
import com.aicallcrm.crm.voice.VoicePlatformClient.OutboundCall;
import com.aicallcrm.crm.voice.VoicePlatformClient.Placed;

import tools.jackson.databind.ObjectMapper;

/**
 * The life of one AI call: placing it (or recording it as simulated), recognising an incoming caller, and
 * closing it when the platform reports the end. Closing a call stores the transcript, writes the call log and
 * the contact's timeline, updates the contact's status where the call settled it, and moves the campaign on.
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
    public record Inbound(boolean fallback, UUID callId, Map<String, String> variables, String openingLine,
                          String systemPrompt, String model) {
        static Inbound useFallback() {
            return new Inbound(true, null, Map.of(), null, null, null);
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
    private final VoicePlatforms platforms;
    private final VoiceProperties props;
    private final ContactRepository contacts;
    private final ContactService contactService;
    private final CallLogRepository callLogs;
    private final AppUserRepository users;
    private final AlertService alerts;
    private final SettingsService branding;
    private final ObjectMapper mapper;
    private final HandoffService handoffs;

    public VoiceCallService(VoiceCallRepository calls, VoiceCampaignTargetRepository targets,
                            VoiceCampaignRepository campaigns, VoiceWebhookEventRepository events,
                            VoiceSettingRepository settings, VoiceScriptService scripts, ConsentService consent,
                            VoicePlatforms platforms, VoiceProperties props, ContactRepository contacts,
                            ContactService contactService, CallLogRepository callLogs, AppUserRepository users,
                            AlertService alerts, SettingsService branding, ObjectMapper mapper, HandoffService handoffs) {
        this.calls = calls;
        this.targets = targets;
        this.campaigns = campaigns;
        this.events = events;
        this.settings = settings;
        this.scripts = scripts;
        this.consent = consent;
        this.platforms = platforms;
        this.props = props;
        this.contacts = contacts;
        this.contactService = contactService;
        this.callLogs = callLogs;
        this.users = users;
        this.alerts = alerts;
        this.branding = branding;
        this.mapper = mapper;
        this.handoffs = handoffs;
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
     * Places one campaign call. With no provider connected the call is saved as SIMULATED: what would have
     * been said is kept, the target is marked as simulated, and no phone rings.
     */
    @Transactional
    public VoiceCall placeOutbound(VoiceCampaign campaign, VoiceCampaignTarget target) {
        Contact contact = contacts.findById(target.getContactId()).orElse(null);
        VoiceCall call = new VoiceCall(Direction.OUTBOUND, campaign.getScriptName(), target.getContactId(),
                target.getPhone(), platforms.current().name().toUpperCase(Locale.ROOT));
        call.setCampaignId(campaign.getId());
        VoiceScript script = scripts.approved(campaign.getScriptName()).orElse(null);
        // Recorded when the opening line tells them it may be (RecordingService); never without telling them.
        call.setRecordingAllowed(script != null && RecordingService.announces(script.getOpeningLine()));
        Map<String, String> vars = variables(contact);
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
                    campaign.getScriptName(), script.getModel(),
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

    /** Only the company's details and the first name: anything else comes from tools, after checks. */
    Map<String, String> variables(Contact contact) {
        Branding b = branding.branding();
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("company_name", b.companyName());
        vars.put("agent_name", b.agentName());
        vars.put("first_name", contact == null ? "" : contact.firstName());
        return vars;
    }

    // ------------------------------------------------------------------ inbound

    /**
     * Someone rang the company number. A number that belongs to exactly one contact is linked to that
     * contact; otherwise the caller is unknown. Either way the call starts unverified, so nothing private is
     * said until verify_identity succeeds.
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
        VoiceScript script = scripts.approvedInbound().orElse(null);
        if (script == null) {
            return Inbound.useFallback();
        }
        String phone = Phones.normalize(callerNumber);
        if (phone == null || phone.isBlank()) {
            phone = "unknown";
        }
        List<Contact> matches = contacts.findByAnyPhone(phone);
        Contact contact = matches.size() == 1 ? matches.get(0) : null;
        VoiceCall call = new VoiceCall(Direction.INBOUND, script.getName(), contact == null ? null : contact.getId(),
                phone, platforms.current().name().toUpperCase(Locale.ROOT));
        call.setProviderCallId(providerCallId);
        call.setStatus(CallStatus.CONNECTED);
        call.setStartedAt(Instant.now());
        call.setRecordingAllowed(RecordingService.announces(script.getOpeningLine()));
        call.setScriptId(script.getId());
        calls.save(call);
        return describe(call);
    }

    private Inbound describe(VoiceCall call) {
        Contact contact = call.getContactId() == null ? null : contacts.findById(call.getContactId()).orElse(null);
        Map<String, String> vars = variables(contact);
        VoiceScript script = call.getScriptId() == null ? null : scripts.approvedInbound().orElse(null);
        return new Inbound(script == null, call.getId(), vars,
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
        if (callId != null && providerCallId != null) {
            // A platform can name the answered call differently from the request that placed it (Plivo: the
            // request_uuid, then the CallUUID); transfers and hang-ups need the answered call's id.
            call.setProviderCallId(providerCallId);
        }
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

    /** The call is over. Delivering the same report twice changes nothing the second time. */
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
        // A provider's recording is kept only for a call whose opening said it may be recorded.
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
        if (status == CallStatus.COMPLETED) {
            optOutIfAsked(call);
            callbackWithdrawnIfSaid(call);
        }
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
        updateContact(call);
        updateTarget(call);
        if (call.getOutcome() == Outcome.VERIFICATION_FAILED) {
            flagRepeatedFailures(call);
        }
    }

    /**
     * The caller asked to stop ("stop calling me", "remove my number") but the call ended without the agent recording
     * it: they hung up first, or the model asked again instead of opting them out. The CRM records the opt-out itself
     * (AI calls only, as {@link CallerSignals#upgrade} does), so no campaign rings them again. Run when a call ends
     * ({@link #close}) and when the engine sends the caller's last words ({@link LiveCallService#lastWords}). Real calls
     * only; nothing happens twice, because the opt-out is noted in the transcript. True if it recorded one.
     */
    boolean optOutIfAsked(VoiceCall call) {
        if (call.isTestCall() || call.getOutcome() == Outcome.OPTED_OUT) {
            return false;
        }
        List<Line> all = lines(call);
        if (all.stream().anyMatch(l -> "opt_out".equals(l.tool()) && "OK".equals(l.status()))) {
            return false;
        }
        String said = all.stream().filter(l -> "caller".equals(l.role()) && l.text() != null).map(Line::text)
                .collect(java.util.stream.Collectors.joining(" "));
        if (!CallerSignals.optOutAsked(said)) {
            return false;
        }
        consent.optOut(call.getPhone(), call.getContactId(), "Asked on an AI call to stop AI calls (recorded by the CRM from the caller's words)");
        handoffs.optedOut(call, false);
        if (call.getOutcome() == null || call.getOutcome() == Outcome.NO_INTERACTION) {
            call.setOutcome(Outcome.OPTED_OUT);
        }
        List<Line> noted = new ArrayList<>(all);
        noted.add(Line.tool("opt_out", "OK", "The caller asked to stop; recorded by the CRM from their words"));
        call.setTranscript(toJson(noted));
        return true;
    }

    /**
     * The caller's last word on a call-back was "no need to call me back" ({@link CallerSignals#callbackWithdrawn}):
     * the call-back is flagged for staff ({@link HandoffService#withdrawn}). Run when a call ends and when the engine
     * sends the caller's last words; real calls only, and doing it twice changes nothing. True if it flagged one.
     */
    boolean callbackWithdrawnIfSaid(VoiceCall call) {
        if (call.isTestCall()) {
            return false;
        }
        String said = lines(call).stream().filter(l -> "caller".equals(l.role()) && l.text() != null).map(Line::text)
                .collect(java.util.stream.Collectors.joining(" "));
        String sentence = CallerSignals.callbackWithdrawn(said);
        return sentence != null && handoffs.withdrawn(call, sentence) > 0;
    }

    private static String defaultSummary(VoiceCall call) {
        String what = call.getScriptName() + " call";
        return switch (call.getStatus()) {
            case NO_ANSWER -> what + ": nobody answered.";
            case FAILED -> what + " could not be placed.";
            default -> what + ": " + (call.getOutcome() == null ? "ended" : words(call.getOutcome().name()).toLowerCase(Locale.ROOT))
                    + (call.getOutcomeNote() == null ? "." : ". " + call.getOutcomeNote());
        };
    }

    /** Every real call appears in the contact's call history and timeline, with a link to its transcript. */
    private void logCommunication(VoiceCall call) {
        if (call.getStatus() == CallStatus.FAILED || call.getContactId() == null) {
            return;
        }
        CallLog.Outcome outcome = call.getStatus() == CallStatus.NO_ANSWER ? CallLog.Outcome.NO_ANSWER
                : call.getOutcome() == Outcome.WRONG_PERSON ? CallLog.Outcome.WRONG_NUMBER
                : call.getOutcome() == Outcome.CALLBACK_REQUESTED || call.getOutcome() == Outcome.HANDED_OFF
                        ? CallLog.Outcome.CALL_BACK_LATER : CallLog.Outcome.CONNECTED;
        CallLog.Direction direction = call.getDirection() == Direction.INBOUND ? CallLog.Direction.INBOUND
                : CallLog.Direction.OUTBOUND;
        callLogs.save(CallLog.byAgent(call.getContactId(), call.getPhone(), direction, outcome, call.getDurationSec(),
                cut("AI agent. " + call.getSummary(), 2000), call.getId().toString()));
        contactService.logCallActivity(call.getContactId(), "AI call: " + (call.getStatus() == CallStatus.NO_ANSWER
                ? "no answer" : words(String.valueOf(call.getOutcome())).toLowerCase(Locale.ROOT)), cut(call.getSummary(), 2000));
    }

    /** What the call settled about the contact: they are interested, not interested, or were reached at all. */
    private void updateContact(VoiceCall call) {
        if (call.getContactId() == null || call.getStatus() != CallStatus.COMPLETED || call.getOutcome() == null) {
            return;
        }
        String why = "Said on an AI call";
        switch (call.getOutcome()) {
            case INTERESTED -> contactService.statusFromCall(call.getContactId(), ContactStatus.INTERESTED, why);
            case NOT_INTERESTED -> contactService.statusFromCall(call.getContactId(), ContactStatus.NOT_INTERESTED, why);
            case WRONG_PERSON, NO_INTERACTION, VERIFICATION_FAILED, OPTED_OUT -> {
            }
            default -> contacts.findById(call.getContactId()).filter(c -> ContactStatus.NEW.equals(c.getStatus()))
                    .ifPresent(c -> contactService.statusFromCall(c.getId(), ContactStatus.CONTACTED, why));
        }
    }

    /** Attempts count only for a connected call or a genuine no-answer. */
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

    /** Three failed identity checks from one number in a day is worth a person's attention. */
    private void flagRepeatedFailures(VoiceCall call) {
        long failures = calls.countByPhoneAndOutcomeAndTestCallFalseAndCreatedAtGreaterThanEqual(call.getPhone(),
                Outcome.VERIFICATION_FAILED, Instant.now().minus(Duration.ofHours(24)));
        if (failures < 3) {
            return;
        }
        String masked = HandoffService.mask(call.getPhone());
        users.findByActiveTrueAndRoleInOrderByFullName(List.of(Role.SUPER_ADMIN)).forEach(admin ->
                alerts.notifyUser(admin.getId(), call.getContactId(), "VOICE_VERIFICATION", Priority.URGENT,
                        "Identity checks failed 3 times from " + masked,
                        "AI calls with this number failed identity verification " + failures + " times in 24 hours. "
                                + "Check whether someone is trying to get a customer's details.",
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
