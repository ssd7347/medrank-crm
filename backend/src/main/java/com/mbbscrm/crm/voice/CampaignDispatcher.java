package com.mbbscrm.crm.voice;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.mbbscrm.crm.alert.AlertService;
import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.voice.CallEligibility.Verdict;
import com.mbbscrm.crm.voice.Voice.CampaignStatus;
import com.mbbscrm.crm.voice.Voice.TargetStatus;

/**
 * Works through running campaigns every 30 seconds (spec 18.10.2): up to {@code maxConcurrent} calls at a
 * time per campaign, each number re-checked for eligibility the moment before it is dialled. Does nothing
 * while the feature is switched off or "Pause all" is on. Also tidies up calls the platform never reported
 * on, deletes conversations past the retention period, and warns the admins about an opt-out spike or the
 * monthly cost limit.
 */
@Component
public class CampaignDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CampaignDispatcher.class);
    static final int OPT_OUT_SPIKE = 5;

    private final VoiceCampaignRepository campaigns;
    private final VoiceCampaignTargetRepository targets;
    private final VoiceCallRepository calls;
    private final ContactConsentRepository consents;
    private final CallEligibility eligibility;
    private final VoiceCallService callService;
    private final VoiceQueryService queries;
    private final AppUserRepository users;
    private final AlertService alerts;
    private final VoiceProperties props;
    private final TransactionTemplate tx;
    private final ReentrantLock lock = new ReentrantLock();

    public CampaignDispatcher(VoiceCampaignRepository campaigns, VoiceCampaignTargetRepository targets,
                              VoiceCallRepository calls, ContactConsentRepository consents,
                              CallEligibility eligibility, VoiceCallService callService,
                              VoiceQueryService queries, AppUserRepository users, AlertService alerts,
                              VoiceProperties props, TransactionTemplate tx) {
        this.campaigns = campaigns;
        this.targets = targets;
        this.calls = calls;
        this.consents = consents;
        this.eligibility = eligibility;
        this.callService = callService;
        this.queries = queries;
        this.users = users;
        this.alerts = alerts;
        this.props = props;
        this.tx = tx;
    }

    @Scheduled(initialDelayString = "PT45S", fixedDelayString = "${app.voice.dispatch-interval:PT30S}")
    public void sweep() {
        try {
            dispatch();
        } catch (RuntimeException e) {
            log.warn("Voice campaign sweep failed: {}", e.toString());
        }
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT6H")
    public void housekeeping() {
        try {
            int purged = purgeOldConversations();
            if (purged > 0) {
                log.info("Deleted {} AI call transcripts older than {} days", purged, props.retentionDays());
            }
            tx.executeWithoutResult(s -> raiseAlerts());
        } catch (RuntimeException e) {
            log.warn("Voice housekeeping failed: {}", e.toString());
        }
    }

    /** One pass over every running campaign. Returns how many calls were placed (or recorded as simulated). */
    public int dispatch() {
        if (!lock.tryLock()) {
            return 0;
        }
        try {
            Instant now = Instant.now();
            tx.executeWithoutResult(s -> callService.expireStale(now));
            if (!Boolean.TRUE.equals(tx.execute(s -> callService.callingAllowed()))) {
                return 0;
            }
            int placed = 0;
            List<Long> running = tx.execute(s -> campaigns.findByStatus(CampaignStatus.RUNNING).stream()
                    .map(VoiceCampaign::getId).toList());
            for (Long id : running) {
                Integer n = tx.execute(s -> dispatchCampaign(id, now));
                placed += n == null ? 0 : n;
            }
            return placed;
        } finally {
            lock.unlock();
        }
    }

    private int dispatchCampaign(Long campaignId, Instant now) {
        VoiceCampaign c = campaigns.findById(campaignId).orElse(null);
        if (c == null || c.getStatus() != CampaignStatus.RUNNING) {
            return 0;
        }
        long active = calls.countByCampaignIdAndStatusIn(campaignId, VoiceCallService.LIVE);
        int slots = (int) Math.max(0, c.getMaxConcurrent() - active);
        int placed = 0;
        // Work through everyone who is due: each person is either called, skipped for good, or given a
        // later time, so they leave the due list. Only people waiting for a free slot stay on it.
        for (int pass = 0; pass < 100; pass++) {
            int moved = 0;
            for (VoiceCampaignTarget t : targets.findDue(campaignId, now, PageRequest.of(0, 100))) {
                Verdict v = eligibility.check(c, t, now);
                if (v.ok()) {
                    if (placed >= slots) {
                        continue;
                    }
                    callService.placeOutbound(c, t);
                    placed++;
                } else if (v.reason().permanent()) {
                    t.skip(v.reason());
                } else {
                    t.setNextAttemptAt(v.retryAt());
                }
                moved++;
            }
            if (moved == 0) {
                break;
            }
        }
        if (active == 0 && placed == 0 && targets.countByCampaignIdAndStatus(campaignId, TargetStatus.PENDING) == 0) {
            c.setStatus(CampaignStatus.DONE);
            c.setFinishedAt(now);
        }
        return placed;
    }

    /** Transcripts and recordings are kept for {@code app.voice.retention-days}, then removed (spec 18.14.3). */
    public int purgeOldConversations() {
        Instant before = Instant.now().minus(Duration.ofDays(props.retentionDays()));
        int total = 0;
        while (true) {
            Integer n = tx.execute(s -> {
                List<VoiceCall> old = calls.findWithContentBefore(before, PageRequest.of(0, 200));
                old.forEach(VoiceCall::purgeContent);
                return old.size();
            });
            total += n == null ? 0 : n;
            if (n == null || n < 200) {
                return total;
            }
        }
    }

    /** Spec 18.15.1: a jump in opt-outs is a complaint signal, and cost is watched against a monthly cap. */
    void raiseAlerts() {
        List<Long> admins = users.findByActiveTrueAndRoleInOrderByFullName(List.of(Role.SUPER_ADMIN)).stream()
                .map(u -> u.getId()).toList();
        long optOuts = consents.countByRevokedAtGreaterThanEqual(Instant.now().minus(Duration.ofHours(24)));
        if (optOuts >= OPT_OUT_SPIKE) {
            for (Long admin : admins) {
                alerts.notifyUser(admin, null, "VOICE_OPT_OUTS", Priority.URGENT,
                        optOuts + " people asked to stop AI calls in the last 24 hours",
                        "This is unusually many. Consider pausing the campaigns and reviewing the script.", "/voice",
                        "VOPT:" + java.time.LocalDate.now());
            }
        }
        BigDecimal cost = queries.estimatedCostThisMonth();
        if (props.monthlyCapInr().signum() > 0 && cost.compareTo(props.monthlyCapInr()) >= 0) {
            for (Long admin : admins) {
                alerts.notifyUser(admin, null, "VOICE_COST", Priority.URGENT,
                        "AI calls have reached this month's cost limit",
                        "Estimated Rs " + cost.toPlainString() + " against a limit of Rs "
                                + props.monthlyCapInr().toPlainString() + ". Calls are not stopped automatically.",
                        "/voice", "VCOST:" + YearMonth.now());
            }
        }
    }
}
