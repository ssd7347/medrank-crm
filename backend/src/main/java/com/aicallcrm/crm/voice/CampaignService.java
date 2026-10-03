package com.aicallcrm.crm.voice;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.contact.Contact;
import com.aicallcrm.crm.contact.ContactRepository;
import com.aicallcrm.crm.contact.ContactStatus;
import com.aicallcrm.crm.contact.FieldService;
import com.aicallcrm.crm.contact.StageService;
import com.aicallcrm.crm.security.CurrentUser;
import com.aicallcrm.crm.voice.CallEligibility.Verdict;
import com.aicallcrm.crm.voice.Voice.CampaignStatus;
import com.aicallcrm.crm.voice.Voice.FieldOp;
import com.aicallcrm.crm.voice.Voice.SkipReason;
import com.aicallcrm.crm.voice.Voice.TargetStatus;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Outbound campaigns: the admin picks a script and says who to call (contact statuses, one upload, and/or a
 * condition on one custom field). The CRM lists those contacts, shows how many can be called and why the rest
 * cannot, and the campaign then runs, pauses and finishes. Draft -> Running <-> Paused -> Done.
 */
@Service
public class CampaignService {

    private static final int PREVIEW_LIMIT = 2000;

    public record CampaignRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 80) String scriptName,
                                  List<String> statuses, Long importBatchId,
                                  @Size(max = 40) String fieldKey, FieldOp fieldOp, @Size(max = 200) String fieldValue,
                                  LocalTime windowStart, LocalTime windowEnd,
                                  @Min(1) @Max(20) Integer maxConcurrent, @Min(1) @Max(5) Integer maxAttempts,
                                  @Min(30) @Max(1440) Integer retryGapMin, Long branchId) {
    }

    public record Counts(int total, int pending, int done, int simulated, int skipped, int failed) {
    }

    public record CampaignView(Long id, String name, String scriptName, CampaignStatus status, List<String> statuses,
                               Long importBatchId, String fieldKey, FieldOp fieldOp, String fieldValue,
                               LocalTime windowStart, LocalTime windowEnd, int maxConcurrent, int maxAttempts,
                               int retryGapMin, Long branchId, Instant createdAt, Instant startedAt,
                               Instant finishedAt, Counts counts, boolean scriptApproved) {
    }

    public record TargetView(Long id, Long contactId, String name, String phone, TargetStatus status,
                             SkipReason skipReason, int attempts, Instant nextAttemptAt, UUID lastCallId) {
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
    private final ContactRepository contacts;
    private final ContactFacts facts;
    private final FieldService fields;
    private final CallEligibility eligibility;
    private final DndService dnd;
    private final VoiceScriptService scripts;
    private final VoiceProperties props;
    private final StageService stages;
    private final AuditService audit;

    public CampaignService(VoiceCampaignRepository campaigns, VoiceCampaignTargetRepository targets,
                           ContactRepository contacts, ContactFacts facts, FieldService fields,
                           CallEligibility eligibility, DndService dnd, VoiceScriptService scripts,
                           VoiceProperties props, StageService stages, AuditService audit) {
        this.stages = stages;
        this.campaigns = campaigns;
        this.targets = targets;
        this.contacts = contacts;
        this.facts = facts;
        this.fields = fields;
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

    /** Not read-only: the preview runs do-not-disturb look-ups, which are cached. */
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
        VoiceCampaign c = new VoiceCampaign(me.id());
        apply(c, req);
        campaigns.save(c);
        rebuildAudience(c);
        audit.record(me.id(), "VOICE_CAMPAIGN_CREATED", "VOICE_CAMPAIGN", c.getId(), c.getScriptName());
        return get(c.getId());
    }

    @Transactional
    public CampaignDetail update(Long id, CampaignRequest req) {
        CurrentUser me = admin();
        VoiceCampaign c = load(id);
        if (c.getStatus() == CampaignStatus.RUNNING || c.getStatus() == CampaignStatus.DONE) {
            throw ApiException.conflict("Pause the campaign before changing it");
        }
        apply(c, req);
        if (c.getStatus() == CampaignStatus.DRAFT) {
            rebuildAudience(c);
        }
        audit.record(me.id(), "VOICE_CAMPAIGN_UPDATED", "VOICE_CAMPAIGN", c.getId(), null);
        return get(id);
    }

    /** Works out again who the campaign applies to (after a new upload, say). Only before it has started. */
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
        if (scripts.approved(c.getScriptName()).isEmpty()) {
            throw ApiException.badRequest("Approve the script \"" + c.getScriptName() + "\" first (Scripts)");
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
        String script = req.scriptName().trim();
        if (!scripts.outboundNames().contains(script)) {
            throw ApiException.badRequest("There is no outgoing-call script named \"" + script + "\"");
        }
        c.setName(req.name().trim());
        c.setScriptName(script);
        c.setFilterStatuses(req.statuses() == null || req.statuses().isEmpty() ? null
                : req.statuses().stream().map(stages::require).filter(s -> !s.equals(ContactStatus.DO_NOT_CALL)).distinct()
                        .collect(Collectors.joining(",")));
        c.setFilterImportId(req.importBatchId());
        boolean hasField = req.fieldKey() != null && !req.fieldKey().isBlank();
        if (hasField) {
            if (req.fieldOp() == null) {
                throw ApiException.badRequest("Choose how to compare the field");
            }
            if (fields.definitions().stream().noneMatch(f -> f.getFieldKey().equals(req.fieldKey().trim()))) {
                throw ApiException.badRequest("Unknown field: " + req.fieldKey());
            }
        }
        c.setFilterFieldKey(hasField ? req.fieldKey().trim() : null);
        c.setFilterFieldOp(hasField ? req.fieldOp() : null);
        c.setFilterFieldValue(hasField && req.fieldValue() != null && !req.fieldValue().isBlank()
                ? req.fieldValue().trim() : null);
        c.setWindowStart(req.windowStart() == null ? props.windowStart() : req.windowStart());
        c.setWindowEnd(req.windowEnd() == null ? props.windowEnd() : req.windowEnd());
        if (!c.getWindowStart().isBefore(c.getWindowEnd())) {
            throw ApiException.badRequest("Calling hours must start before they end");
        }
        c.setMaxConcurrent(req.maxConcurrent() == null ? 3 : req.maxConcurrent());
        c.setMaxAttempts(req.maxAttempts() == null ? 3 : req.maxAttempts());
        c.setRetryGapMin(req.retryGapMin() == null ? 240 : req.retryGapMin());
        c.setBranchId(req.branchId());
    }

    // ------------------------------------------------------------------ who to call

    private void rebuildAudience(VoiceCampaign c) {
        targets.deleteByCampaignId(c.getId());
        targets.flush();
        Set<String> seen = new HashSet<>();
        List<VoiceCampaignTarget> out = new ArrayList<>();
        for (Contact contact : contacts.findAll()) {
            if (facts.matches(c, contact) && seen.add(contact.getPhone())) {
                out.add(new VoiceCampaignTarget(c.getId(), contact.getId(), contact.getPhone()));
            }
        }
        targets.saveAll(out);
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
        return new CampaignView(c.getId(), c.getName(), c.getScriptName(), c.getStatus(),
                List.copyOf(ContactFacts.statuses(c.getFilterStatuses())), c.getFilterImportId(), c.getFilterFieldKey(),
                c.getFilterFieldOp(), c.getFilterFieldValue(), c.getWindowStart(), c.getWindowEnd(),
                c.getMaxConcurrent(), c.getMaxAttempts(), c.getRetryGapMin(), c.getBranchId(), c.getCreatedAt(),
                c.getStartedAt(), c.getFinishedAt(), new Counts(list.size(), pending, done, simulated, skipped, failed),
                scripts.approved(c.getScriptName()).isPresent());
    }

    private TargetView targetView(VoiceCampaignTarget t) {
        String name = contacts.findById(t.getContactId()).map(Contact::getFullName).orElse(null);
        return new TargetView(t.getId(), t.getContactId(), name, HandoffService.mask(t.getPhone()), t.getStatus(),
                t.getSkipReason(), t.getAttempts(), t.getNextAttemptAt(), t.getLastCallId());
    }

    private VoiceCampaign load(Long id) {
        return campaigns.findById(id).orElseThrow(() -> ApiException.notFound("Campaign"));
    }

    private static CurrentUser admin() {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only the admin can manage calling campaigns");
        }
        return me;
    }
}
