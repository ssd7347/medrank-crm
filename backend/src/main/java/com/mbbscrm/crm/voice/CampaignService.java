package com.mbbscrm.crm.voice;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Language;
import com.mbbscrm.crm.common.LeadStatus;
import com.mbbscrm.crm.engagement.CallLog;
import com.mbbscrm.crm.engagement.CallLogRepository;
import com.mbbscrm.crm.fee.FeeService;
import com.mbbscrm.crm.lead.Lead;
import com.mbbscrm.crm.lead.LeadRepository;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentRepository;
import com.mbbscrm.crm.voice.CallEligibility.Verdict;
import com.mbbscrm.crm.voice.Voice.CampaignStatus;
import com.mbbscrm.crm.voice.Voice.PersonType;
import com.mbbscrm.crm.voice.Voice.Purpose;
import com.mbbscrm.crm.voice.Voice.SkipReason;
import com.mbbscrm.crm.voice.Voice.TargetStatus;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Outbound campaigns (spec 18.10): an admin picks a purpose, the CRM works out who that applies to, shows
 * how many can be called and why the rest cannot, and the campaign then runs, pauses and finishes.
 * Draft -> Running <-> Paused -> Done.
 */
@Service
public class CampaignService {

    private static final int PREVIEW_LIMIT = 2000;

    public record CampaignRequest(@NotBlank @Size(max = 120) String name, @NotNull Purpose purpose,
                                  LocalTime windowStart, LocalTime windowEnd,
                                  @Min(1) @Max(20) Integer maxConcurrent, @Min(1) @Max(5) Integer maxAttempts,
                                  @Min(30) @Max(1440) Integer retryGapMin, @Min(1) @Max(30) Integer lookaheadDays,
                                  Long branchId) {
    }

    public record Counts(int total, int pending, int done, int simulated, int skipped, int failed) {
    }

    public record CampaignView(Long id, String name, Purpose purpose, CampaignStatus status, LocalTime windowStart,
                               LocalTime windowEnd, int maxConcurrent, int maxAttempts, int retryGapMin,
                               int lookaheadDays, Long branchId, Instant createdAt, Instant startedAt,
                               Instant finishedAt, Counts counts, boolean scriptApproved) {
    }

    public record TargetView(Long id, Long studentId, Long leadId, String name, String phone, PersonType recipient,
                             TargetStatus status, SkipReason skipReason, int attempts, Instant nextAttemptAt,
                             UUID lastCallId) {
    }

    /**
     * Who would be called if the campaign ran this minute. {@code waiting} are fine but not right now (for
     * example outside calling hours); {@code skipped} will not be called at all, with the reason.
     */
    public record Preview(int checked, int eligibleNow, Map<SkipReason, Integer> waiting,
                          Map<SkipReason, Integer> skipped, boolean dndChecked) {
    }

    public record CampaignDetail(CampaignView campaign, List<TargetView> targets, Preview preview) {
    }

    private final VoiceCampaignRepository campaigns;
    private final VoiceCampaignTargetRepository targets;
    private final StudentRepository students;
    private final LeadRepository leads;
    private final CallLogRepository callLogs;
    private final FeeService fees;
    private final VoiceFacts facts;
    private final ConsentService consent;
    private final CallEligibility eligibility;
    private final DndService dnd;
    private final VoiceScriptService scripts;
    private final VoiceProperties props;
    private final AuditService audit;

    public CampaignService(VoiceCampaignRepository campaigns, VoiceCampaignTargetRepository targets,
                           StudentRepository students, LeadRepository leads, CallLogRepository callLogs,
                           FeeService fees, VoiceFacts facts, ConsentService consent, CallEligibility eligibility,
                           DndService dnd, VoiceScriptService scripts, VoiceProperties props, AuditService audit) {
        this.campaigns = campaigns;
        this.targets = targets;
        this.students = students;
        this.leads = leads;
        this.callLogs = callLogs;
        this.fees = fees;
        this.facts = facts;
        this.consent = consent;
        this.eligibility = eligibility;
        this.dnd = dnd;
        this.scripts = scripts;
        this.props = props;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<CampaignView> list() {
        admin();
        return campaigns.findAllByOrderByCreatedAtDesc().stream()
                .map(c -> view(c, targets.findByCampaignIdOrderByIdAsc(c.getId()))).toList();
    }

    @Transactional
    public CampaignDetail get(Long id) {
        admin();
        VoiceCampaign c = load(id);
        List<VoiceCampaignTarget> list = targets.findByCampaignIdOrderByIdAsc(id);
        return new CampaignDetail(view(c, list), list.stream().map(this::targetView).toList(),
                c.getStatus() == CampaignStatus.DONE ? null : preview(c, list));
    }

    // ------------------------------------------------------------------ changes

    @Transactional
    public CampaignDetail create(CampaignRequest req) {
        CurrentUser me = admin();
        if (!req.purpose().outbound()) {
            throw ApiException.badRequest("A campaign makes outgoing calls; choose an outgoing purpose");
        }
        VoiceCampaign c = new VoiceCampaign(req.purpose(), me.id());
        apply(c, req);
        campaigns.save(c);
        rebuildAudience(c);
        audit.record(me.id(), "VOICE_CAMPAIGN_CREATED", "VOICE_CAMPAIGN", c.getId(), c.getPurpose().name());
        return get(c.getId());
    }

    @Transactional
    public CampaignDetail update(Long id, CampaignRequest req) {
        CurrentUser me = admin();
        VoiceCampaign c = load(id);
        if (c.getStatus() == CampaignStatus.RUNNING || c.getStatus() == CampaignStatus.DONE) {
            throw ApiException.conflict("Pause the campaign before changing it");
        }
        if (req.purpose() != c.getPurpose()) {
            throw ApiException.badRequest("The purpose of a campaign cannot be changed; create a new one");
        }
        apply(c, req);
        if (c.getStatus() == CampaignStatus.DRAFT) {
            rebuildAudience(c);
        }
        audit.record(me.id(), "VOICE_CAMPAIGN_UPDATED", "VOICE_CAMPAIGN", c.getId(), null);
        return get(id);
    }

    /** Works out again who the campaign applies to. Only before it has started. */
    @Transactional
    public CampaignDetail refreshAudience(Long id) {
        admin();
        VoiceCampaign c = load(id);
        if (c.getStatus() != CampaignStatus.DRAFT) {
            throw ApiException.conflict("The list of people is fixed once a campaign has started");
        }
        rebuildAudience(c);
        return get(id);
    }

    @Transactional
    public CampaignDetail start(Long id) {
        CurrentUser me = admin();
        VoiceCampaign c = load(id);
        if (c.getStatus() == CampaignStatus.DONE) {
            throw ApiException.conflict("This campaign has finished");
        }
        if (targets.countByCampaignIdAndStatus(id, TargetStatus.PENDING) == 0) {
            throw ApiException.badRequest("There is nobody left to call in this campaign");
        }
        if (scripts.approved(c.getPurpose(), Language.ENGLISH).isEmpty()) {
            throw ApiException.badRequest("Approve a script for this kind of call first (Call scripts)");
        }
        c.setStatus(CampaignStatus.RUNNING);
        if (c.getStartedAt() == null) {
            c.setStartedAt(Instant.now());
        }
        audit.record(me.id(), "VOICE_CAMPAIGN_STARTED", "VOICE_CAMPAIGN", c.getId(), null);
        return get(id);
    }

    @Transactional
    public CampaignDetail pause(Long id) {
        CurrentUser me = admin();
        VoiceCampaign c = load(id);
        if (c.getStatus() != CampaignStatus.RUNNING) {
            throw ApiException.conflict("Only a running campaign can be paused");
        }
        c.setStatus(CampaignStatus.PAUSED);
        audit.record(me.id(), "VOICE_CAMPAIGN_PAUSED", "VOICE_CAMPAIGN", c.getId(), null);
        return get(id);
    }

    /** Ends a campaign for good; anyone not yet called stays uncalled. */
    @Transactional
    public CampaignDetail finish(Long id) {
        CurrentUser me = admin();
        VoiceCampaign c = load(id);
        c.setStatus(CampaignStatus.DONE);
        c.setFinishedAt(Instant.now());
        audit.record(me.id(), "VOICE_CAMPAIGN_FINISHED", "VOICE_CAMPAIGN", c.getId(), null);
        return get(id);
    }

    private void apply(VoiceCampaign c, CampaignRequest req) {
        c.setName(req.name().trim());
        c.setWindowStart(req.windowStart() == null ? props.windowStart() : req.windowStart());
        c.setWindowEnd(req.windowEnd() == null ? props.windowEnd() : req.windowEnd());
        if (!c.getWindowStart().isBefore(c.getWindowEnd())) {
            throw ApiException.badRequest("Calling hours must start before they end");
        }
        c.setMaxConcurrent(req.maxConcurrent() == null ? 3 : req.maxConcurrent());
        c.setMaxAttempts(req.maxAttempts() == null ? 3 : req.maxAttempts());
        c.setRetryGapMin(req.retryGapMin() == null ? 240 : req.retryGapMin());
        c.setLookaheadDays(req.lookaheadDays() == null ? 3 : req.lookaheadDays());
        c.setBranchId(req.branchId());
    }

    // ------------------------------------------------------------------ who to call

    private void rebuildAudience(VoiceCampaign c) {
        targets.deleteByCampaignId(c.getId());
        targets.flush();
        Set<String> seen = new HashSet<>();
        List<VoiceCampaignTarget> out = new ArrayList<>();
        if (c.getPurpose().forLeads()) {
            Specification<Lead> isNew = (root, q, cb) -> cb.equal(root.get("status"), LeadStatus.NEW);
            for (Lead l : leads.findAll(isNew)) {
                if (inBranch(c, l.getBranch() == null ? null : l.getBranch().getId()) && usable(l.getPhone(), seen)) {
                    out.add(new VoiceCampaignTarget(c.getId(), null, l.getId(), l.getPhone(), PersonType.LEAD));
                }
            }
        } else if (c.getPurpose() == Purpose.MISSED_CALL_FOLLOWUP) {
            missedCalls(c, seen, out);
        } else {
            for (Student s : candidates(c)) {
                if (inBranch(c, s.getBranch() == null ? null : s.getBranch().getId())
                        && facts.applies(c.getPurpose(), s, c.getLookaheadDays())) {
                    addStudent(c, s, seen, out);
                }
            }
        }
        targets.saveAll(out);
    }

    /** Fee reminders start from the dues list; everything else has to look at every student. */
    private List<Student> candidates(VoiceCampaign c) {
        if (c.getPurpose() != Purpose.FEE_REMINDER) {
            return students.findAll();
        }
        LocalDate until = LocalDate.now(VoiceFacts.IST).plusDays(c.getLookaheadDays());
        Set<Long> ids = new HashSet<>();
        fees.duesUnchecked().rows().stream().filter(d -> d.overdueDays() > 0 || !d.dueDate().isAfter(until))
                .forEach(d -> ids.add(d.studentId()));
        return students.findAllById(ids);
    }

    /** People whose most recent call from a staff member, in the look-back period, did not get through. */
    private void missedCalls(VoiceCampaign c, Set<String> seen, List<VoiceCampaignTarget> out) {
        Instant since = Instant.now().minus(c.getLookaheadDays(), ChronoUnit.DAYS);
        Map<String, CallLog> latest = new LinkedHashMap<>();
        for (CallLog log : callLogs.findByCalledAtGreaterThanEqualOrderByCalledAtAsc(since)) {
            if (log.getProvider() == null) {
                latest.put(log.getStudentId() != null ? "S" + log.getStudentId() : "L" + log.getLeadId(), log);
            }
        }
        for (CallLog log : latest.values()) {
            if (log.getOutcome().reached()) {
                continue;
            }
            if (log.getStudentId() != null) {
                students.findById(log.getStudentId())
                        .filter(s -> inBranch(c, s.getBranch() == null ? null : s.getBranch().getId()))
                        .ifPresent(s -> addStudent(c, s, seen, out));
            } else {
                leads.findById(log.getLeadId())
                        .filter(l -> inBranch(c, l.getBranch() == null ? null : l.getBranch().getId())
                                && usable(l.getPhone(), seen))
                        .ifPresent(l -> out.add(new VoiceCampaignTarget(c.getId(), null, l.getId(), l.getPhone(),
                                PersonType.LEAD)));
            }
        }
    }

    /**
     * One number per student: the student's own if it has consent, otherwise the parent's if that has
     * consent, otherwise the student's (which the preview will then show as "no consent").
     */
    private void addStudent(VoiceCampaign c, Student s, Set<String> seen, List<VoiceCampaignTarget> out) {
        boolean studentOk = consent.state(s.getPhone()) == ConsentService.State.GRANTED;
        boolean parentOk = s.getParentPhone() != null
                && consent.state(s.getParentPhone()) == ConsentService.State.GRANTED;
        boolean useParent = !studentOk && parentOk;
        String phone = useParent ? s.getParentPhone() : s.getPhone();
        if (usable(phone, seen)) {
            out.add(new VoiceCampaignTarget(c.getId(), s.getId(), null, phone,
                    useParent ? PersonType.PARENT : PersonType.STUDENT));
        }
    }

    private static boolean usable(String phone, Set<String> seen) {
        return phone != null && !phone.isBlank() && seen.add(phone);
    }

    private static boolean inBranch(VoiceCampaign c, Long branchId) {
        return c.getBranchId() == null || c.getBranchId().equals(branchId);
    }

    private Preview preview(VoiceCampaign c, List<VoiceCampaignTarget> list) {
        Instant now = Instant.now();
        int eligible = 0;
        int checked = 0;
        Map<SkipReason, Integer> waiting = new EnumMap<>(SkipReason.class);
        Map<SkipReason, Integer> skipped = new EnumMap<>(SkipReason.class);
        for (VoiceCampaignTarget t : list) {
            if (t.getStatus() != TargetStatus.PENDING || checked >= PREVIEW_LIMIT) {
                continue;
            }
            checked++;
            Verdict v = eligibility.check(c, t, now);
            if (v.ok()) {
                eligible++;
            } else {
                (v.reason().permanent() ? skipped : waiting).merge(v.reason(), 1, Integer::sum);
            }
        }
        return new Preview(checked, eligible, waiting, skipped, dnd.connected());
    }

    // ------------------------------------------------------------------ views

    private CampaignView view(VoiceCampaign c, List<VoiceCampaignTarget> list) {
        int pending = 0, done = 0, simulated = 0, skipped = 0, failed = 0;
        for (VoiceCampaignTarget t : list) {
            switch (t.getStatus()) {
                case PENDING -> pending++;
                case DONE -> done++;
                case SIMULATED -> simulated++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }
        return new CampaignView(c.getId(), c.getName(), c.getPurpose(), c.getStatus(), c.getWindowStart(),
                c.getWindowEnd(), c.getMaxConcurrent(), c.getMaxAttempts(), c.getRetryGapMin(), c.getLookaheadDays(),
                c.getBranchId(), c.getCreatedAt(), c.getStartedAt(), c.getFinishedAt(),
                new Counts(list.size(), pending, done, simulated, skipped, failed),
                scripts.approved(c.getPurpose(), Language.ENGLISH).isPresent());
    }

    private TargetView targetView(VoiceCampaignTarget t) {
        String name = t.getStudentId() != null
                ? students.findById(t.getStudentId()).map(Student::getFullName).orElse(null)
                : leads.findById(t.getLeadId()).map(Lead::getFullName).orElse(null);
        return new TargetView(t.getId(), t.getStudentId(), t.getLeadId(), name, HandoffService.mask(t.getPhone()),
                t.getRecipient(), t.getStatus(), t.getSkipReason(), t.getAttempts(), t.getNextAttemptAt(),
                t.getLastCallId());
    }

    private VoiceCampaign load(Long id) {
        return campaigns.findById(id).orElseThrow(() -> ApiException.notFound("Campaign"));
    }

    private static CurrentUser admin() {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only an admin can manage calling campaigns");
        }
        return me;
    }
}
