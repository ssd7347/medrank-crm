package com.mbbscrm.crm.voice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Language;
import com.mbbscrm.crm.common.PageResponse;
import com.mbbscrm.crm.lead.Lead;
import com.mbbscrm.crm.lead.LeadRepository;
import com.mbbscrm.crm.lead.LeadService;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentRepository;
import com.mbbscrm.crm.student.StudentService;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.user.UserDtos.UserRef;
import com.mbbscrm.crm.voice.Voice.CallStatus;
import com.mbbscrm.crm.voice.Voice.CallbackStatus;
import com.mbbscrm.crm.voice.Voice.CampaignStatus;
import com.mbbscrm.crm.voice.Voice.Direction;
import com.mbbscrm.crm.voice.Voice.HandoffReason;
import com.mbbscrm.crm.voice.Voice.Outcome;
import com.mbbscrm.crm.voice.Voice.Purpose;
import com.mbbscrm.crm.voice.VoiceCallService.Line;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import jakarta.validation.constraints.Size;

/**
 * What staff see of the voice agent (spec 18.15): call history, transcripts with the tool trail, the
 * callback queue and the numbers. Transcripts are limited to admins and the counsellor who looks after
 * that student or lead, and every time one is opened it is written to the audit log.
 */
@Service
public class VoiceQueryService {

    public record CallRow(UUID id, Direction direction, Purpose purpose, Long studentId, Long leadId,
                          String personName, String phone, Language language, CallStatus status, Outcome outcome,
                          HandoffReason handoffReason, boolean verified, Integer durationSec, boolean testCall,
                          boolean flaggedWrong, String provider, Long campaignId, String summary, Instant createdAt) {
    }

    public record ToolRow(String tool, String status, Integer latencyMs, Instant at) {
    }

    public record CallbackView(Long id, Long studentId, Long leadId, String personName, String phone,
                               UUID voiceCallId, String reason, String preferredTimeText, String summary,
                               Priority priority, CallbackStatus status, UserRef assignedTo, Instant dueAt,
                               boolean overdue, Instant createdAt, Instant doneAt, String doneNote) {
    }

    public record CallDetail(CallRow call, List<Line> transcript, List<ToolRow> tools, String outcomeNote,
                             String disconnectReason, boolean recordingAllowed, String recordingRef, String flagNote,
                             String script, Instant startedAt, Instant endedAt, List<CallbackView> callbacks) {
    }

    public record FlagRequest(@Size(max = 1000) String note) {
    }

    public record DoneRequest(@Size(max = 1000) String note) {
    }

    public record Overview(boolean enabled, boolean paused, String platform, boolean live, boolean signingConfigured,
                           boolean dndConnected, boolean transferConfigured, LocalTime windowStart,
                           LocalTime windowEnd, int openCallbacks, int overdueCallbacks, int runningCampaigns) {
    }

    public record Metrics(int days, int calls, int connected, int noAnswer, int failed, int simulated,
                          int connectRatePercent, Integer avgDurationSec, Map<String, Integer> outcomes,
                          Map<String, Integer> handoffs, Map<String, Integer> purposes, int handoffRatePercent,
                          int toolCalls, Integer toolP50Ms, Integer toolP95Ms, int toolErrorRatePercent,
                          long optOuts, int flagged, BigDecimal estimatedCostInr, BigDecimal costPerCallInr,
                          BigDecimal costThisMonthInr, BigDecimal monthlyCapInr) {
    }

    private final VoiceCallRepository calls;
    private final VoiceToolAuditRepository toolAudits;
    private final CallbackRequestRepository callbacks;
    private final VoiceCampaignRepository campaigns;
    private final VoiceScriptRepository scripts;
    private final ContactConsentRepository consents;
    private final VoiceCallService callService;
    private final VoicePlatforms platforms;
    private final DndService dnd;
    private final VoiceProperties props;
    private final StudentRepository students;
    private final LeadRepository leads;
    private final StudentService studentService;
    private final LeadService leadService;
    private final AppUserRepository users;
    private final AuditService audit;

    public VoiceQueryService(VoiceCallRepository calls, VoiceToolAuditRepository toolAudits,
                             CallbackRequestRepository callbacks, VoiceCampaignRepository campaigns,
                             VoiceScriptRepository scripts, ContactConsentRepository consents,
                             VoiceCallService callService, VoicePlatforms platforms, DndService dnd,
                             VoiceProperties props, StudentRepository students, LeadRepository leads,
                             StudentService studentService, LeadService leadService, AppUserRepository users,
                             AuditService audit) {
        this.calls = calls;
        this.toolAudits = toolAudits;
        this.callbacks = callbacks;
        this.campaigns = campaigns;
        this.scripts = scripts;
        this.consents = consents;
        this.callService = callService;
        this.platforms = platforms;
        this.dnd = dnd;
        this.props = props;
        this.students = students;
        this.leads = leads;
        this.studentService = studentService;
        this.leadService = leadService;
        this.users = users;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ overview and the kill switch

    @Transactional(readOnly = true)
    public Overview overview() {
        CurrentUser me = CurrentUser.get();
        Instant now = Instant.now();
        List<CallbackRequest> open = visibleCallbacks(me, true);
        return new Overview(props.enabled(), callService.paused(), platforms.current().name(), platforms.live(),
                props.signingConfigured(), dnd.connected(), !props.deskTransferNumber().isBlank(),
                props.windowStart(), props.windowEnd(), open.size(),
                (int) open.stream().filter(c -> c.getDueAt().isBefore(now)).count(),
                me.isAdmin() ? campaigns.findByStatus(CampaignStatus.RUNNING).size() : 0);
    }

    /** "Pause all" (spec 18.10.3): stops dialling at once, for every campaign. */
    @Transactional
    public Overview setPaused(boolean paused) {
        CurrentUser me = admin();
        callService.setPaused(paused, me.id());
        audit.record(me.id(), paused ? "VOICE_PAUSED_ALL" : "VOICE_RESUMED_ALL", "VOICE", null, null);
        return overview();
    }

    // ------------------------------------------------------------------ calls

    @Transactional(readOnly = true)
    public PageResponse<CallRow> search(CallStatus status, Outcome outcome, Purpose purpose, Long campaignId,
                                        boolean tests, boolean flagged, int page, int size) {
        CurrentUser me = CurrentUser.get();
        Specification<VoiceCall> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            // Test-console calls belong to the admin who is trying things out; nobody else needs them.
            p.add(cb.equal(root.get("testCall"), tests && me.isAdmin()));
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            if (outcome != null) {
                p.add(cb.equal(root.get("outcome"), outcome));
            }
            if (purpose != null) {
                p.add(cb.equal(root.get("purpose"), purpose));
            }
            if (campaignId != null) {
                p.add(cb.equal(root.get("campaignId"), campaignId));
            }
            if (flagged) {
                p.add(cb.isTrue(root.get("flaggedWrong")));
            }
            if (!me.isAdmin()) {
                Subquery<Long> myStudents = query.subquery(Long.class);
                Root<Student> s = myStudents.from(Student.class);
                myStudents.select(s.get("id")).where(cb.equal(s.get("assignedCounsellor").get("id"), me.id()));
                Subquery<Long> myLeads = query.subquery(Long.class);
                Root<Lead> l = myLeads.from(Lead.class);
                myLeads.select(l.get("id")).where(cb.equal(l.get("assignedCounsellor").get("id"), me.id()));
                p.add(cb.or(root.get("studentId").in(myStudents), root.get("leadId").in(myLeads)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        Page<VoiceCall> result = calls.findAll(spec,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(result, this::row);
    }

    @Transactional(readOnly = true)
    public List<CallRow> forStudent(Long studentId) {
        studentService.requireReadable(studentId);
        return calls.findByStudentIdAndTestCallFalseOrderByCreatedAtDesc(studentId).stream().map(this::row).toList();
    }

    @Transactional(readOnly = true)
    public List<CallRow> forLead(Long leadId) {
        leadService.requireVisible(leadId);
        return calls.findByLeadIdAndTestCallFalseOrderByCreatedAtDesc(leadId).stream().map(this::row).toList();
    }

    /** Opens one call with its transcript. Not read-only: the view itself is recorded (spec 18.14.3). */
    @Transactional
    public CallDetail detail(UUID id) {
        CurrentUser me = CurrentUser.get();
        VoiceCall call = visibleCall(me, id);
        audit.record(me.id(), "VOICE_CALL_VIEWED", "VOICE_CALL", null, id.toString());
        List<ToolRow> tools = toolAudits.findByCallIdOrderByIdAsc(id).stream()
                .map(t -> new ToolRow(t.getTool(), t.getStatus(), t.getLatencyMs(), t.getCreatedAt())).toList();
        String script = call.getScriptId() == null ? null : scripts.findById(call.getScriptId())
                .map(s -> VoiceCallService.words(s.getPurpose().name()) + ", " + VoiceCallService.words(
                        s.getLanguage().name()) + ", version " + s.getVersion()).orElse(null);
        Instant now = Instant.now();
        return new CallDetail(row(call), callService.lines(call), tools, call.getOutcomeNote(),
                call.getDisconnectReason(), call.isRecordingAllowed(), call.getRecordingRef(), call.getFlagNote(),
                script, call.getStartedAt(), call.getEndedAt(),
                callbacks.findByVoiceCallId(id).stream().map(c -> callbackView(c, now)).toList());
    }

    /** "This was wrong": feeds the list the admin works through to improve the script (spec 18.15.2). */
    @Transactional
    public CallDetail flag(UUID id, FlagRequest req) {
        CurrentUser me = CurrentUser.get();
        VoiceCall call = visibleCall(me, id);
        call.flag(me.id(), req.note() == null || req.note().isBlank() ? null : req.note().trim());
        audit.record(me.id(), "VOICE_CALL_FLAGGED", "VOICE_CALL", null, id.toString());
        return detail(id);
    }

    private VoiceCall visibleCall(CurrentUser me, UUID id) {
        VoiceCall call = calls.findById(id).orElseThrow(() -> ApiException.notFound("Call"));
        if (me.isAdmin()) {
            return call;
        }
        AppUser owner = call.isTestCall() ? null
                : call.getStudentId() != null
                        ? students.findById(call.getStudentId()).map(Student::getAssignedCounsellor).orElse(null)
                : call.getLeadId() != null
                        ? leads.findById(call.getLeadId()).map(Lead::getAssignedCounsellor).orElse(null) : null;
        if (owner == null || !owner.getId().equals(me.id())) {
            throw ApiException.notFound("Call");
        }
        return call;
    }

    private CallRow row(VoiceCall c) {
        return new CallRow(c.getId(), c.getDirection(), c.getPurpose(), c.getStudentId(), c.getLeadId(),
                personName(c.getStudentId(), c.getLeadId()), HandoffService.mask(c.getPhone()), c.getLanguage(),
                c.getStatus(), c.getOutcome(), c.getHandoffReason(), c.getVerificationLevel() >= 1,
                c.getDurationSec(), c.isTestCall(), c.isFlaggedWrong(), c.getProvider(), c.getCampaignId(),
                c.getSummary(), c.getCreatedAt());
    }

    private String personName(Long studentId, Long leadId) {
        if (studentId != null) {
            return students.findById(studentId).map(Student::getFullName).orElse(null);
        }
        return leadId == null ? null : leads.findById(leadId).map(Lead::getFullName).orElse(null);
    }

    // ------------------------------------------------------------------ callback queue

    @Transactional(readOnly = true)
    public List<CallbackView> callbackQueue(boolean done) {
        CurrentUser me = CurrentUser.get();
        Instant now = Instant.now();
        return visibleCallbacks(me, !done).stream().map(c -> callbackView(c, now)).toList();
    }

    /** Admins see every callback; everyone else sees their own and the ones nobody has taken yet. */
    private List<CallbackRequest> visibleCallbacks(CurrentUser me, boolean open) {
        List<CallbackRequest> list = open
                ? callbacks.findByStatusInOrderByDueAtAsc(List.of(CallbackStatus.OPEN, CallbackStatus.ASSIGNED))
                : callbacks.findTop100ByStatusOrderByDoneAtDesc(CallbackStatus.DONE);
        return list.stream().filter(c -> me.isAdmin() || c.getAssignedTo() == null
                || c.getAssignedTo().getId().equals(me.id())).toList();
    }

    /** "I'll take this one", or an admin giving it to someone. */
    @Transactional
    public CallbackView assign(Long id, Long userId) {
        CurrentUser me = CurrentUser.get();
        CallbackRequest cb = callbacks.findById(id).orElseThrow(() -> ApiException.notFound("Callback"));
        Long target = userId == null ? me.id() : userId;
        boolean takingFree = cb.getAssignedTo() == null && target.equals(me.id());
        if (!me.isAdmin() && !takingFree) {
            throw ApiException.forbidden("Only an admin can give a callback to someone else");
        }
        if (cb.getStatus() == CallbackStatus.DONE) {
            throw ApiException.conflict("This callback is already done");
        }
        cb.assign(users.findById(target).filter(AppUser::isActive)
                .orElseThrow(() -> ApiException.badRequest("Staff member not found or inactive")));
        audit.record(me.id(), "VOICE_CALLBACK_ASSIGNED", "CALLBACK_REQUEST", cb.getId(), "to=" + target);
        return callbackView(cb, Instant.now());
    }

    @Transactional
    public CallbackView complete(Long id, DoneRequest req) {
        CurrentUser me = CurrentUser.get();
        CallbackRequest cb = callbacks.findById(id).orElseThrow(() -> ApiException.notFound("Callback"));
        if (!me.isAdmin() && cb.getAssignedTo() != null && !cb.getAssignedTo().getId().equals(me.id())) {
            throw ApiException.forbidden("This callback belongs to someone else");
        }
        if (cb.getStatus() != CallbackStatus.DONE) {
            cb.complete(me.id(), req == null || req.note() == null || req.note().isBlank() ? null : req.note().trim());
            audit.record(me.id(), "VOICE_CALLBACK_DONE", "CALLBACK_REQUEST", cb.getId(), null);
        }
        return callbackView(cb, Instant.now());
    }

    private CallbackView callbackView(CallbackRequest c, Instant now) {
        return new CallbackView(c.getId(), c.getStudentId(), c.getLeadId(),
                personName(c.getStudentId(), c.getLeadId()), c.getPhone(), c.getVoiceCallId(), c.getReason(),
                c.getPreferredTimeText(), c.getSummary(), c.getPriority(), c.getStatus(),
                UserRef.of(c.getAssignedTo()), c.getDueAt(),
                c.getStatus() != CallbackStatus.DONE && c.getDueAt().isBefore(now), c.getCreatedAt(), c.getDoneAt(),
                c.getDoneNote());
    }

    // ------------------------------------------------------------------ numbers (spec 18.15.1)

    @Transactional(readOnly = true)
    public Metrics metrics(int days) {
        admin();
        int window = Math.clamp(days, 1, 365);
        Instant since = Instant.now().minus(Duration.ofDays(window));
        List<VoiceCall> list = calls.findByTestCallFalseAndCreatedAtGreaterThanEqual(since);
        int connected = 0, noAnswer = 0, failed = 0, simulated = 0, flagged = 0, handedOff = 0;
        long seconds = 0;
        int timed = 0;
        Map<String, Integer> outcomes = new TreeMap<>();
        Map<String, Integer> handoffs = new TreeMap<>();
        Map<String, Integer> purposes = new TreeMap<>();
        for (VoiceCall c : list) {
            purposes.merge(c.getPurpose().name(), 1, Integer::sum);
            switch (c.getStatus()) {
                case COMPLETED, CONNECTED -> connected++;
                case NO_ANSWER -> noAnswer++;
                case FAILED -> failed++;
                case SIMULATED -> simulated++;
                default -> {
                }
            }
            if (c.getOutcome() != null) {
                outcomes.merge(c.getOutcome().name(), 1, Integer::sum);
            }
            if (c.getHandoffReason() != null) {
                handoffs.merge(c.getHandoffReason().name(), 1, Integer::sum);
                handedOff++;
            }
            if (c.getDurationSec() != null && c.getStatus() == CallStatus.COMPLETED) {
                seconds += c.getDurationSec();
                timed++;
            }
            if (c.isFlaggedWrong()) {
                flagged++;
            }
        }
        List<VoiceToolAudit> toolRows = toolAudits.findByCreatedAtGreaterThanEqual(since);
        List<Integer> latencies = toolRows.stream().map(VoiceToolAudit::getLatencyMs).filter(l -> l != null).sorted()
                .toList();
        long toolErrors = toolRows.stream().filter(t -> "INTERNAL".equals(t.getStatus())).count();
        int rung = connected + noAnswer;
        BigDecimal cost = cost(list);
        return new Metrics(window, list.size(), connected, noAnswer, failed, simulated, percent(connected, rung),
                timed == 0 ? null : (int) (seconds / timed), outcomes, handoffs, purposes,
                percent(handedOff, connected), toolRows.size(), percentile(latencies, 50), percentile(latencies, 95),
                percent(toolErrors, toolRows.size()), consents.countByRevokedAtGreaterThanEqual(since), flagged, cost,
                connected == 0 ? null : cost.divide(BigDecimal.valueOf(connected), 2, RoundingMode.HALF_UP),
                estimatedCostThisMonth(), props.monthlyCapInr());
    }

    /** Connected minutes (rounded up per call) times the configured rate. An estimate, not an invoice. */
    @Transactional(readOnly = true)
    public BigDecimal estimatedCostThisMonth() {
        Instant monthStart = LocalDate.now(VoiceFacts.IST).withDayOfMonth(1).atStartOfDay(VoiceFacts.IST).toInstant();
        return cost(calls.findByTestCallFalseAndCreatedAtGreaterThanEqual(monthStart));
    }

    private BigDecimal cost(List<VoiceCall> list) {
        long minutes = list.stream().filter(c -> c.getDurationSec() != null && c.getDurationSec() > 0)
                .mapToLong(c -> (c.getDurationSec() + 59) / 60).sum();
        return props.costPerMinuteInr().multiply(BigDecimal.valueOf(minutes)).setScale(2, RoundingMode.HALF_UP);
    }

    static int percent(long part, long whole) {
        return whole == 0 ? 0 : (int) Math.round(100.0 * part / whole);
    }

    /** Nearest-rank percentile of an ascending list; null when there is nothing to measure. */
    static Integer percentile(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) {
            return null;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    private static CurrentUser admin() {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only an admin can do this");
        }
        return me;
    }
}
