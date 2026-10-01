package com.mbbscrm.crm.voice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.counselling.AuthorityType;
import com.mbbscrm.crm.counselling.CounsellingDtos.AllotmentResponse;
import com.mbbscrm.crm.counselling.CounsellingDtos.RoundResponse;
import com.mbbscrm.crm.counselling.CounsellingDtos.TrackResponse;
import com.mbbscrm.crm.counselling.CounsellingRoundEntity;
import com.mbbscrm.crm.counselling.CounsellingService;
import com.mbbscrm.crm.counselling.RoundRepository;
import com.mbbscrm.crm.document.DocumentService;
import com.mbbscrm.crm.document.DocumentService.Checklist;
import com.mbbscrm.crm.document.DocumentService.ChecklistItem;
import com.mbbscrm.crm.fee.FeeService;
import com.mbbscrm.crm.fee.FeeService.InstallmentView;
import com.mbbscrm.crm.fee.FeeService.PlanView;
import com.mbbscrm.crm.fee.PlanStatus;
import com.mbbscrm.crm.predictor.PredictorShortlistRepository;
import com.mbbscrm.crm.refund.RefundRuleEngine;
import com.mbbscrm.crm.refund.RefundRuleEngine.Assessment;
import com.mbbscrm.crm.refund.RefundRuleEngine.Seat;
import com.mbbscrm.crm.student.Student;

/**
 * The small, spoken-friendly facts the agent may tell a caller (spec 18.3, "least data to the model").
 * Everything here is read straight from the CRM for one student; nothing is estimated. The same facts
 * decide who a campaign should call and whether a call is still worth making at dial time.
 */
@Service
public class VoiceFacts {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter SPOKEN = DateTimeFormatter.ofPattern("EEEE d MMMM, h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter SPOKEN_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);

    public record DeadlineFact(String type, String authority, String round, Instant at, long daysLeft) {
        public String spoken() {
            return type.toLowerCase(Locale.ROOT).replace('_', ' ') + " for " + authority + " " + round + " is on "
                    + when(at);
        }
    }

    public record RoundFact(String authority, String round, String stage, boolean resultOut,
                            Instant decisionWindowEnd) {
    }

    public record AllotmentFact(String round, String college, String quota, String category, String decision,
                                Instant decisionDeadline) {
    }

    public record DocumentFact(int required, int done, List<String> missing, List<String> expiring) {
    }

    public record FeeFact(String label, BigDecimal amount, LocalDate dueDate, boolean overdue) {
    }

    public record ShortlistFact(String college, String band) {
    }

    private final CounsellingService counselling;
    private final RoundRepository rounds;
    private final DocumentService documents;
    private final FeeService fees;
    private final PredictorShortlistRepository shortlist;
    private final RefundRuleEngine refundRules;

    public VoiceFacts(CounsellingService counselling, RoundRepository rounds, DocumentService documents,
                      FeeService fees, PredictorShortlistRepository shortlist, RefundRuleEngine refundRules) {
        this.counselling = counselling;
        this.rounds = rounds;
        this.documents = documents;
        this.fees = fees;
        this.shortlist = shortlist;
        this.refundRules = refundRules;
    }

    static String when(Instant at) {
        return SPOKEN.format(at.atZone(IST));
    }

    static String day(LocalDate date) {
        return SPOKEN_DAY.format(date);
    }

    // ------------------------------------------------------------------ deadlines

    /** This student's own upcoming dates, soonest first: round deadlines of their tracks and open decisions. */
    @Transactional(readOnly = true)
    public List<DeadlineFact> personalDeadlines(Student s, int withinDays) {
        Instant now = Instant.now();
        Instant until = now.plus(withinDays, ChronoUnit.DAYS);
        List<DeadlineFact> out = new ArrayList<>();
        for (TrackResponse t : counselling.tracksForStudent(s)) {
            String authority = t.authority().code();
            for (RoundResponse r : t.rounds()) {
                add(out, "CHOICE_FILLING_CLOSES", authority, r.label(), r.choiceFillingEnd(), now, until);
                add(out, "RESULT", authority, r.label(), r.resultAt(), now, until);
                add(out, "REPORTING_CLOSES", authority, r.label(), r.reportingEnd(), now, until);
            }
            for (AllotmentResponse a : t.allotments()) {
                if (a.college() != null && a.decision() == null) {
                    add(out, "SEAT_DECISION", authority, a.roundLabel(), a.decisionDeadline(), now, until);
                }
            }
        }
        out.sort(Comparator.comparing(DeadlineFact::at));
        return out;
    }

    /** Published round dates for every authority: safe to say to anyone, verified or not. */
    @Transactional(readOnly = true)
    public List<DeadlineFact> generalDeadlines(int withinDays) {
        Instant now = Instant.now();
        Instant until = now.plus(withinDays, ChronoUnit.DAYS);
        List<DeadlineFact> out = new ArrayList<>();
        for (CounsellingRoundEntity r : rounds.findWithDeadlineBetween(now, until)) {
            String authority = r.getAuthority().getCode();
            add(out, "CHOICE_FILLING_CLOSES", authority, r.label(), r.getChoiceFillingEnd(), now, until);
            add(out, "RESULT", authority, r.label(), r.getResultAt(), now, until);
            add(out, "REPORTING_CLOSES", authority, r.label(), r.getReportingEnd(), now, until);
        }
        out.sort(Comparator.comparing(DeadlineFact::at));
        return out;
    }

    private static void add(List<DeadlineFact> out, String type, String authority, String round, Instant at,
                            Instant now, Instant until) {
        if (at != null && at.isAfter(now) && !at.isAfter(until)) {
            out.add(new DeadlineFact(type, authority, round, at, ChronoUnit.DAYS.between(now, at)));
        }
    }

    // ------------------------------------------------------------------ rounds and allotments

    /** Where the student stands with the central (AIQ) or their state authority; empty if no such track. */
    @Transactional(readOnly = true)
    public Optional<RoundFact> roundStatus(Student s, AuthorityType type) {
        Instant now = Instant.now();
        for (TrackResponse t : counselling.tracksForStudent(s)) {
            if (t.authority().authorityType() != type || t.rounds().isEmpty()) {
                continue;
            }
            RoundResponse current = t.rounds().stream().filter(r -> !"CLOSED".equals(r.phase())).findFirst()
                    .orElse(t.rounds().get(t.rounds().size() - 1));
            Instant decisionEnd = t.allotments().stream()
                    .filter(a -> a.roundId().equals(current.id()) && a.college() != null && a.decision() == null)
                    .map(AllotmentResponse::decisionDeadline).filter(d -> d != null && d.isAfter(now)).findFirst()
                    .orElse(null);
            boolean resultOut = current.resultAt() != null && !now.isBefore(current.resultAt());
            return Optional.of(new RoundFact(t.authority().name(), current.label(), current.phase(), resultOut,
                    decisionEnd));
        }
        return Optional.empty();
    }

    /** The most recently recorded allotment across all of the student's tracks. */
    @Transactional(readOnly = true)
    public Optional<AllotmentFact> latestAllotment(Student s) {
        return latestAllotted(s).map(p -> {
            AllotmentResponse a = p.allotment();
            return new AllotmentFact(a.roundLabel(), a.college().name(), String.valueOf(a.quota()),
                    String.valueOf(a.category()), a.decision() == null ? "NOT_DECIDED" : a.decision().name(),
                    a.decisionDeadline());
        });
    }

    private record Allotted(TrackResponse track, AllotmentResponse allotment) {
    }

    private Optional<Allotted> latestAllotted(Student s) {
        Allotted best = null;
        for (TrackResponse t : counselling.tracksForStudent(s)) {
            for (AllotmentResponse a : t.allotments()) {
                if (a.college() != null && a.recordedAt() != null
                        && (best == null || a.recordedAt().isAfter(best.allotment().recordedAt()))) {
                    best = new Allotted(t, a);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** What giving up the latest allotted seat would cost, in the refund rule's own words (spec 4.27). */
    @Transactional(readOnly = true)
    public Optional<List<String>> refundRule(Student s) {
        return latestAllotted(s).map(p -> {
            AllotmentResponse a = p.allotment();
            Assessment assessment = refundRules.assess(new Seat(p.track().academicYear(), p.track().authority().id(),
                    a.college().id(), a.roundType(), a.recordedAt()), Instant.now());
            return assessment.lines();
        });
    }

    // ------------------------------------------------------------------ documents, fees, shortlist

    @Transactional(readOnly = true)
    public DocumentFact documents(Student s) {
        Checklist c = documents.buildChecklist(s);
        List<String> expiring = c.items().stream().filter(i -> i.expired() || i.expiringSoon())
                .map(ChecklistItem::name).toList();
        return new DocumentFact(c.requiredCount(), c.requiredDone(), c.missingRequired(), expiring);
    }

    /** The earliest instalment that still has a balance on an active plan. */
    @Transactional(readOnly = true)
    public Optional<FeeFact> nextFee(Student s) {
        LocalDate today = LocalDate.now(IST);
        FeeFact next = null;
        for (PlanView p : fees.plansForStudent(s)) {
            if (p.status() != PlanStatus.ACTIVE) {
                continue;
            }
            for (InstallmentView i : p.installments()) {
                if (i.balance().signum() > 0 && (next == null || i.dueDate().isBefore(next.dueDate()))) {
                    next = new FeeFact(i.label(), i.balance(), i.dueDate(), i.dueDate().isBefore(today));
                }
            }
        }
        return Optional.ofNullable(next);
    }

    @Transactional(readOnly = true)
    public List<ShortlistFact> shortlist(Student s, int max) {
        return shortlist.findByStudentIdOrderByCreatedAtAsc(s.getId()).stream().limit(max)
                .map(p -> new ShortlistFact(p.getCollege().getName(), p.getBand() == null ? null : p.getBand().name()))
                .toList();
    }

    // ------------------------------------------------------------------ is a call still worth making?

    /** True when a call of this purpose would have something to say to this student right now. */
    @Transactional(readOnly = true)
    public boolean applies(Voice.Purpose purpose, Student s, int lookaheadDays) {
        return switch (purpose) {
            case DEADLINE_REMINDER -> !personalDeadlines(s, lookaheadDays).isEmpty();
            case DOC_NUDGE -> !documents(s).missing().isEmpty();
            case FEE_REMINDER -> nextFee(s)
                    .map(f -> f.overdue() || !f.dueDate().isAfter(LocalDate.now(IST).plusDays(lookaheadDays)))
                    .orElse(false);
            default -> true;
        };
    }
}
