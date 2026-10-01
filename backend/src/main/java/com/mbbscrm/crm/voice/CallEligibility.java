package com.mbbscrm.crm.voice;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.common.LeadStatus;
import com.mbbscrm.crm.lead.LeadRepository;
import com.mbbscrm.crm.student.StudentRepository;
import com.mbbscrm.crm.voice.Voice.DndResult;
import com.mbbscrm.crm.voice.Voice.SkipReason;

/**
 * Decides, immediately before dialling, whether a number may be called right now (spec 18.10.1). Every
 * rule must pass: consent, do-not-disturb, attempts left, the call still being needed, the calling window
 * in Indian time, and not having rung the same number too recently or too often today.
 */
@Service
public class CallEligibility {

    /** {@code retryAt} is set for a "not right now" answer; permanent reasons end the target. */
    public record Verdict(boolean ok, SkipReason reason, Instant retryAt) {
        static final Verdict OK = new Verdict(true, null, null);

        static Verdict never(SkipReason reason) {
            return new Verdict(false, reason, null);
        }

        static Verdict later(SkipReason reason, Instant retryAt) {
            return new Verdict(false, reason, retryAt);
        }
    }

    private static final LocalTime AFTERNOON = LocalTime.of(14, 0);

    private final ConsentService consent;
    private final DndService dnd;
    private final VoiceFacts facts;
    private final VoiceCallRepository calls;
    private final StudentRepository students;
    private final LeadRepository leads;
    private final VoiceProperties props;

    public CallEligibility(ConsentService consent, DndService dnd, VoiceFacts facts, VoiceCallRepository calls,
                              StudentRepository students, LeadRepository leads, VoiceProperties props) {
        this.consent = consent;
        this.dnd = dnd;
        this.facts = facts;
        this.calls = calls;
        this.students = students;
        this.leads = leads;
        this.props = props;
    }

    @Transactional
    public Verdict check(VoiceCampaign campaign, VoiceCampaignTarget target, Instant now) {
        switch (consent.state(target.getPhone())) {
            case OPTED_OUT -> {
                return Verdict.never(SkipReason.OPTED_OUT);
            }
            case NONE, REFUSED -> {
                return Verdict.never(SkipReason.NO_CONSENT);
            }
            case GRANTED -> {
            }
        }
        if (dnd.check(target.getPhone()) == DndResult.BLOCKED) {
            return Verdict.never(SkipReason.DND);
        }
        if (target.getAttempts() >= campaign.getMaxAttempts()) {
            return Verdict.never(SkipReason.MAX_ATTEMPTS);
        }
        if (!stillNeeded(campaign, target)) {
            return Verdict.never(SkipReason.NO_LONGER_NEEDED);
        }
        LocalTime start = later(campaign.getWindowStart(), props.windowStart());
        LocalTime end = earlier(campaign.getWindowEnd(), props.windowEnd());
        if (!inWindow(start, end, now)) {
            return Verdict.later(SkipReason.OUTSIDE_WINDOW, nextWindowStart(start, end, now));
        }
        if (target.getNextAttemptAt() != null && target.getNextAttemptAt().isAfter(now)) {
            return Verdict.later(SkipReason.NOT_DUE_YET, target.getNextAttemptAt());
        }
        ZonedDateTime ist = now.atZone(VoiceFacts.IST);
        List<VoiceCall> today = calls.findRungSince(target.getPhone(), ist.toLocalDate().atStartOfDay(VoiceFacts.IST).toInstant());
        if (today.size() >= props.maxPerNumberPerDay()) {
            return Verdict.later(SkipReason.DAILY_LIMIT,
                    ist.toLocalDate().plusDays(1).atTime(start).atZone(VoiceFacts.IST).toInstant());
        }
        Duration gap = Duration.ofMinutes(props.minGapMinutes());
        if (!today.isEmpty() && today.get(0).getCreatedAt().plus(gap).isAfter(now)) {
            return Verdict.later(SkipReason.CALLED_RECENTLY, today.get(0).getCreatedAt().plus(gap));
        }
        return Verdict.OK;
    }

    /** Re-checked at dial time: a paid fee or an uploaded document means the call is no longer wanted. */
    private boolean stillNeeded(VoiceCampaign campaign, VoiceCampaignTarget target) {
        if (target.getStudentId() != null) {
            return students.findById(target.getStudentId())
                    .map(s -> facts.applies(campaign.getPurpose(), s, campaign.getLookaheadDays())).orElse(false);
        }
        return leads.findById(target.getLeadId())
                .map(l -> !campaign.getPurpose().forLeads() || l.getStatus() == LeadStatus.NEW).orElse(false);
    }

    // ------------------------------------------------------------------ calling-window maths (Indian time)

    static LocalTime later(LocalTime a, LocalTime b) {
        return a.isAfter(b) ? a : b;
    }

    static LocalTime earlier(LocalTime a, LocalTime b) {
        return a.isBefore(b) ? a : b;
    }

    /** The window includes its start and excludes its end. An empty window (start >= end) is never open. */
    static boolean inWindow(LocalTime start, LocalTime end, Instant now) {
        LocalTime t = now.atZone(VoiceFacts.IST).toLocalTime();
        return !t.isBefore(start) && t.isBefore(end);
    }

    /** The next moment the window opens: later today if it has not opened yet, otherwise tomorrow. */
    static Instant nextWindowStart(LocalTime start, LocalTime end, Instant now) {
        ZonedDateTime ist = now.atZone(VoiceFacts.IST);
        boolean beforeOpening = ist.toLocalTime().isBefore(start);
        return ist.toLocalDate().plusDays(beforeOpening ? 0 : 1).atTime(start).atZone(VoiceFacts.IST).toInstant();
    }

    /**
     * When to try again after nobody answered (spec 18.10.2): no sooner than the retry gap, and in the other
     * half of the day, since someone who does not pick up in the morning is more likely to in the evening.
     */
    static Instant nextRetry(Instant attemptedAt, int retryGapMin, LocalTime start, LocalTime end) {
        ZonedDateTime attempt = attemptedAt.atZone(VoiceFacts.IST);
        ZonedDateTime candidate = attempt.plusMinutes(retryGapMin);
        boolean morning = attempt.toLocalTime().isBefore(AFTERNOON);
        if (morning) {
            ZonedDateTime afternoon = attempt.toLocalDate().atTime(AFTERNOON).atZone(VoiceFacts.IST);
            if (candidate.isBefore(afternoon)) {
                candidate = afternoon;
            }
        } else if (candidate.toLocalDate().equals(attempt.toLocalDate())) {
            candidate = attempt.toLocalDate().plusDays(1).atTime(start).atZone(VoiceFacts.IST);
        }
        Instant at = candidate.toInstant();
        return inWindow(start, end, at) ? at : nextWindowStart(start, end, at);
    }
}
