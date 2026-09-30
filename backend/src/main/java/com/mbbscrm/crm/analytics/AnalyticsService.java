package com.mbbscrm.crm.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.branch.Branch;
import com.mbbscrm.crm.branch.BranchRef;
import com.mbbscrm.crm.branch.BranchRepository;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.LeadStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

/**
 * Owner's analytics (spec 4.13): the live round-day numbers, the admissions funnel, revenue, outcomes by
 * category and state, and a branch comparison (spec 4.15). Period figures count records created in the
 * period; the round-day numbers are always "right now" across all branches.
 */
@Service
public class AnalyticsService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final EntityManager em;
    private final BranchRepository branches;

    public AnalyticsService(EntityManager em, BranchRepository branches) {
        this.em = em;
        this.branches = branches;
    }

    public record RoundDay(long awaitingResults, long decisionsPending, long decisionsClosing24h,
                           long unacknowledgedUrgent) {
    }

    public record FunnelStage(String stage, long count) {
    }

    public record MonthAmount(String month, BigDecimal collected) {
    }

    public record Revenue(BigDecimal billed, BigDecimal collected, BigDecimal refunded, BigDecimal outstanding,
                          List<MonthAmount> monthly) {
    }

    public record OutcomeRow(String key, long students, long allotted, long admitted) {
    }

    public record BranchRow(BranchRef branch, long leads, long admissions, long students, BigDecimal collected) {
    }

    public record Overview(LocalDate from, LocalDate to, RoundDay roundDay, List<FunnelStage> funnel,
                           Map<LeadStatus, Long> leadsByStatus, Revenue revenue, List<OutcomeRow> byCategory,
                           List<OutcomeRow> byState, List<BranchRow> branches) {
    }

    @Transactional(readOnly = true)
    public Overview overview(LocalDate from, LocalDate to, Long branchId) {
        LocalDate end = to == null ? LocalDate.now(IST) : to;
        LocalDate start = from == null ? end.minusYears(1) : from;
        if (start.isAfter(end)) {
            throw ApiException.badRequest("The start date must be before the end date");
        }
        Instant since = start.atStartOfDay(IST).toInstant();
        Instant until = end.plusDays(1).atStartOfDay(IST).toInstant();

        // ---- funnel
        Map<LeadStatus, Long> byStatus = new EnumMap<>(LeadStatus.class);
        for (LeadStatus s : LeadStatus.values()) {
            byStatus.put(s, 0L);
        }
        long total = 0;
        long converted = 0;
        for (Object[] r : period("select l.status, count(l), sum(case when l.student is not null then 1 else 0 end) "
                + "from Lead l where l.createdAt >= :since and l.createdAt < :until", "l.branch.id",
                " group by l.status", since, until, branchId)) {
            long n = ((Number) r[1]).longValue();
            byStatus.put((LeadStatus) r[0], n);
            total += n;
            converted += ((Number) r[2]).longValue();
        }
        long feePaid = byStatus.get(LeadStatus.FEE_PAID) + byStatus.get(LeadStatus.ACTIVELY_COUNSELLED)
                + byStatus.get(LeadStatus.ADMISSION_CONFIRMED);
        List<FunnelStage> funnel = List.of(
                new FunnelStage("Inquiries", total),
                new FunnelStage("Qualified or further", total - byStatus.get(LeadStatus.NEW)
                        - closedWithoutProgress(since, until, branchId)),
                new FunnelStage("Became a student", converted),
                new FunnelStage("Fee paid or further", feePaid),
                new FunnelStage("Admission confirmed", byStatus.get(LeadStatus.ADMISSION_CONFIRMED)));

        return new Overview(start, end, roundDay(), funnel, byStatus, revenue(start, end, since, until, branchId),
                outcomes("s.category", since, until, branchId), outcomes("s.homeState", since, until, branchId),
                branchId == null ? branchRows(start, end, since, until) : List.of());
    }

    /** Closed leads that never became a student: they left the funnel before qualifying. */
    private long closedWithoutProgress(Instant since, Instant until, Long branchId) {
        List<Object[]> r = period("select count(l), count(l) from Lead l where l.createdAt >= :since "
                + "and l.createdAt < :until and l.status = com.mbbscrm.crm.common.LeadStatus.CLOSED "
                + "and l.student is null", "l.branch.id", "", since, until, branchId);
        return ((Number) r.get(0)[0]).longValue();
    }

    // ------------------------------------------------------------------ round day

    private RoundDay roundDay() {
        Instant now = Instant.now();
        long awaiting = em.createQuery("""
                select count(c) from ChoiceList c
                where c.status = com.mbbscrm.crm.counselling.ChoiceListStatus.LOCKED
                  and not exists (select a.id from AllotmentResult a
                                  where a.studentCounselling = c.studentCounselling and a.round = c.round
                                    and a.recordedAt is not null)""", Long.class).getSingleResult();
        long pending = em.createQuery("""
                select count(a) from AllotmentResult a
                where a.college is not null and a.decision is null and a.decisionDeadline > :now""", Long.class)
                .setParameter("now", now).getSingleResult();
        long closing = em.createQuery("""
                select count(a) from AllotmentResult a
                where a.college is not null and a.decision is null
                  and a.decisionDeadline > :now and a.decisionDeadline <= :soon""", Long.class)
                .setParameter("now", now).setParameter("soon", now.plus(24, ChronoUnit.HOURS)).getSingleResult();
        long unacknowledged = em.createQuery("""
                select count(m) from OutboundMessage m
                where m.priority = com.mbbscrm.crm.alert.Priority.URGENT and m.acknowledgedAt is null
                  and m.createdAt > :recent""", Long.class)
                .setParameter("recent", now.minus(14, ChronoUnit.DAYS)).getSingleResult();
        return new RoundDay(awaiting, pending, closing, unacknowledged);
    }

    // ------------------------------------------------------------------ revenue

    private Revenue revenue(LocalDate start, LocalDate end, Instant since, Instant until, Long branchId) {
        String planBranch = branchId == null ? "" : " and p.student.branch.id = :branchId";
        String viaPlanBranch = branchId == null ? "" : " and x.plan.student.branch.id = :branchId";

        BigDecimal billed = money(em.createQuery("select coalesce(sum(p.totalAmount - p.discount), 0) from FeePlan p "
                + "where p.status <> com.mbbscrm.crm.fee.PlanStatus.CANCELLED and p.createdAt >= :since "
                + "and p.createdAt < :until" + planBranch, BigDecimal.class)
                .setParameter("since", since).setParameter("until", until), branchId);
        BigDecimal collected = money(em.createQuery("select coalesce(sum(x.amount), 0) from Payment x "
                + "where x.voided = false and x.paidOn >= :start and x.paidOn <= :end" + viaPlanBranch,
                BigDecimal.class).setParameter("start", start).setParameter("end", end), branchId);
        BigDecimal refunded = money(em.createQuery("select coalesce(sum(x.amount), 0) from FeeRefund x "
                + "where x.status = com.mbbscrm.crm.fee.RefundStatus.PAID and x.paidOn >= :start and x.paidOn <= :end"
                + viaPlanBranch, BigDecimal.class).setParameter("start", start).setParameter("end", end), branchId);

        // Outstanding is "as of now", not tied to the period: what active plans still owe.
        BigDecimal activeNet = money(em.createQuery("select coalesce(sum(p.totalAmount - p.discount), 0) from FeePlan p "
                + "where p.status = com.mbbscrm.crm.fee.PlanStatus.ACTIVE" + planBranch, BigDecimal.class), branchId);
        BigDecimal activePaid = money(em.createQuery("select coalesce(sum(x.amount), 0) from Payment x "
                + "where x.voided = false and x.plan.status = com.mbbscrm.crm.fee.PlanStatus.ACTIVE" + viaPlanBranch,
                BigDecimal.class), branchId);
        BigDecimal outstanding = activeNet.subtract(activePaid).max(BigDecimal.ZERO);

        // Month-by-month collection for the last 12 months, including months with nothing collected.
        YearMonth thisMonth = YearMonth.now(IST);
        Map<YearMonth, BigDecimal> months = new TreeMap<>();
        for (int i = 11; i >= 0; i--) {
            months.put(thisMonth.minusMonths(i), BigDecimal.ZERO);
        }
        TypedQuery<Object[]> q = em.createQuery("select x.paidOn, x.amount from Payment x where x.voided = false "
                + "and x.paidOn >= :start" + viaPlanBranch, Object[].class)
                .setParameter("start", thisMonth.minusMonths(11).atDay(1));
        if (branchId != null) {
            q.setParameter("branchId", branchId);
        }
        for (Object[] r : q.getResultList()) {
            months.computeIfPresent(YearMonth.from((LocalDate) r[0]), (k, v) -> v.add((BigDecimal) r[1]));
        }
        List<MonthAmount> monthly = months.entrySet().stream()
                .map(e -> new MonthAmount(e.getKey().toString(), e.getValue())).toList();
        return new Revenue(billed, collected, refunded, outstanding, monthly);
    }

    private static BigDecimal money(TypedQuery<BigDecimal> q, Long branchId) {
        if (branchId != null) {
            q.setParameter("branchId", branchId);
        }
        return q.getSingleResult();
    }

    // ------------------------------------------------------------------ outcomes

    /** Students added in the period, grouped by {@code key}: how many got a seat and how many were admitted. */
    private List<OutcomeRow> outcomes(String key, Instant since, Instant until, Long branchId) {
        Map<String, long[]> rows = new LinkedHashMap<>();
        for (Object[] r : period("select " + key + ", count(s) from Student s "
                + "where s.createdAt >= :since and s.createdAt < :until", "s.branch.id",
                " group by " + key + " order by count(s) desc", since, until, branchId)) {
            rows.computeIfAbsent(String.valueOf(r[0]), k -> new long[3])[0] = ((Number) r[1]).longValue();
        }
        for (Object[] r : period("select " + key + ", count(distinct s.id) from AllotmentResult a "
                + "join a.studentCounselling sc join sc.student s "
                + "where a.college is not null and s.createdAt >= :since and s.createdAt < :until", "s.branch.id",
                " group by " + key, since, until, branchId)) {
            rows.computeIfAbsent(String.valueOf(r[0]), k -> new long[3])[1] = ((Number) r[1]).longValue();
        }
        for (Object[] r : period("select " + key + ", count(distinct s.id) from Lead l join l.student s "
                + "where l.status = com.mbbscrm.crm.common.LeadStatus.ADMISSION_CONFIRMED "
                + "and s.createdAt >= :since and s.createdAt < :until", "s.branch.id",
                " group by " + key, since, until, branchId)) {
            rows.computeIfAbsent(String.valueOf(r[0]), k -> new long[3])[2] = ((Number) r[1]).longValue();
        }
        List<OutcomeRow> out = new ArrayList<>();
        rows.forEach((k, v) -> out.add(new OutcomeRow(k, v[0], v[1], v[2])));
        return out;
    }

    // ------------------------------------------------------------------ branches

    private List<BranchRow> branchRows(LocalDate start, LocalDate end, Instant since, Instant until) {
        List<Branch> all = branches.findAllByOrderByName();
        if (all.isEmpty()) {
            return List.of();
        }
        Map<Long, long[]> leads = new HashMap<>();
        for (Object[] r : em.createQuery("""
                select l.branch.id, count(l),
                       sum(case when l.status = com.mbbscrm.crm.common.LeadStatus.ADMISSION_CONFIRMED then 1 else 0 end)
                from Lead l where l.createdAt >= :since and l.createdAt < :until group by l.branch.id""",
                Object[].class).setParameter("since", since).setParameter("until", until).getResultList()) {
            leads.put((Long) r[0], new long[] {((Number) r[1]).longValue(), ((Number) r[2]).longValue()});
        }
        Map<Long, Long> students = new HashMap<>();
        for (Object[] r : em.createQuery("""
                select s.branch.id, count(s) from Student s
                where s.createdAt >= :since and s.createdAt < :until group by s.branch.id""", Object[].class)
                .setParameter("since", since).setParameter("until", until).getResultList()) {
            students.put((Long) r[0], ((Number) r[1]).longValue());
        }
        Map<Long, BigDecimal> collected = new HashMap<>();
        for (Object[] r : em.createQuery("""
                select s.branch.id, sum(x.amount) from Payment x join x.plan p join p.student s
                where x.voided = false and x.paidOn >= :start and x.paidOn <= :end group by s.branch.id""",
                Object[].class).setParameter("start", start).setParameter("end", end).getResultList()) {
            collected.put((Long) r[0], (BigDecimal) r[1]);
        }
        List<BranchRow> rows = new ArrayList<>();
        for (Branch b : all) {
            long[] l = leads.getOrDefault(b.getId(), new long[2]);
            rows.add(new BranchRow(BranchRef.of(b), l[0], l[1], students.getOrDefault(b.getId(), 0L),
                    collected.getOrDefault(b.getId(), BigDecimal.ZERO)));
        }
        // Records that belong to no branch (head office) are shown too, so the rows add up to the totals.
        long[] l = leads.getOrDefault(null, new long[2]);
        long s = students.getOrDefault(null, 0L);
        BigDecimal c = collected.getOrDefault(null, BigDecimal.ZERO);
        if (l[0] > 0 || s > 0 || c.signum() > 0) {
            rows.add(new BranchRow(null, l[0], l[1], s, c));
        }
        return rows;
    }

    // ------------------------------------------------------------------ helpers

    private List<Object[]> period(String select, String branchPath, String tail, Instant since, Instant until,
                                  Long branchId) {
        TypedQuery<Object[]> q = em.createQuery(select + (branchId == null ? "" : " and " + branchPath + " = :branchId")
                + tail, Object[].class).setParameter("since", since).setParameter("until", until);
        if (branchId != null) {
            q.setParameter("branchId", branchId);
        }
        return q.getResultList();
    }
}
