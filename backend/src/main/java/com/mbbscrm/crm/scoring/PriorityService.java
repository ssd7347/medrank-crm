package com.mbbscrm.crm.scoring;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.common.LeadSource;
import com.mbbscrm.crm.common.LeadStatus;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.engagement.CallLog;
import com.mbbscrm.crm.engagement.CallLog.Outcome;
import com.mbbscrm.crm.fee.FeeService;
import com.mbbscrm.crm.lead.Lead;
import com.mbbscrm.crm.lead.LeadService;
import com.mbbscrm.crm.scoring.Scoring.LeadFacts;
import com.mbbscrm.crm.scoring.Scoring.LeadScore;
import com.mbbscrm.crm.scoring.Scoring.RiskScore;
import com.mbbscrm.crm.scoring.Scoring.StudentFacts;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentService;
import com.mbbscrm.crm.user.UserDtos.UserRef;

import jakarta.persistence.EntityManager;

/**
 * "What should I work on next?" (spec 4.17 prioritisation, 4.18): the current user's open leads ranked by
 * score, and their students ranked by drop-off risk. Admins see everyone's.
 */
@Service
public class PriorityService {

    private static final int SCAN_LIMIT = 500;
    private static final int SHOW = 20;

    private final EntityManager em;
    private final LeadService leads;
    private final StudentService students;
    private final FeeService fees;

    public PriorityService(EntityManager em, LeadService leads, StudentService students, FeeService fees) {
        this.em = em;
        this.leads = leads;
        this.students = students;
        this.fees = fees;
    }

    public record LeadRow(Long id, String fullName, String phone, LeadStatus status, LeadSource source,
                          UserRef assignedTo, Instant createdAt, LeadScore score) {
    }

    public record StudentRow(Long id, String fullName, String phone, UserRef counsellor, RiskScore risk) {
    }

    /** {@code leads} are the ones needing action first; {@code hotLeads} the most likely to convert. */
    public record Priorities(List<LeadRow> leads, List<LeadRow> hotLeads, List<StudentRow> students) {
    }

    @Transactional(readOnly = true)
    public Priorities mine() {
        CurrentUser me = CurrentUser.get();
        List<LeadRow> scored = List.of();
        if (me.role() == Role.SUPER_ADMIN || me.role() == Role.COUNSELLOR || me.role() == Role.TELECALLER) {
            String mine = me.isAdmin() ? "" : " and l.assignedCounsellor.id = :me";
            var q = em.createQuery("select l from Lead l left join fetch l.assignedCounsellor "
                    + "where l.status not in (com.mbbscrm.crm.common.LeadStatus.CLOSED, "
                    + "com.mbbscrm.crm.common.LeadStatus.ADMISSION_CONFIRMED)" + mine + " order by l.createdAt desc",
                    Lead.class).setMaxResults(SCAN_LIMIT);
            if (!me.isAdmin()) {
                q.setParameter("me", me.id());
            }
            scored = scoreLeads(q.getResultList());
        }
        // Needing action first: anything with a suggested next step, highest score first within that.
        List<LeadRow> action = scored.stream().filter(r -> r.score().nextAction() != null)
                .sorted(Comparator.comparingInt((LeadRow r) -> r.score().score()).reversed()).limit(SHOW).toList();
        List<LeadRow> hot = scored.stream().filter(r -> r.score().band() == Scoring.Band.HOT)
                .sorted(Comparator.comparingInt((LeadRow r) -> r.score().score()).reversed()).limit(SHOW).toList();

        List<StudentRow> risky = List.of();
        if (me.role() == Role.SUPER_ADMIN || me.role() == Role.COUNSELLOR) {
            String mine = me.isAdmin() ? "" : " where s.assignedCounsellor.id = :me";
            var q = em.createQuery("select s from Student s left join fetch s.assignedCounsellor" + mine
                    + " order by s.createdAt desc", Student.class).setMaxResults(SCAN_LIMIT);
            if (!me.isAdmin()) {
                q.setParameter("me", me.id());
            }
            risky = scoreStudents(q.getResultList()).stream()
                    .filter(r -> r.risk().risk() != Scoring.Risk.LOW)
                    .sorted(Comparator.comparingInt((StudentRow r) -> r.risk().score()).reversed()).limit(SHOW).toList();
        }
        return new Priorities(action, hot, risky);
    }

    @Transactional(readOnly = true)
    public LeadScore leadScore(Long leadId) {
        return scoreLeads(List.of(leads.requireVisible(leadId))).get(0).score();
    }

    @Transactional(readOnly = true)
    public RiskScore studentRisk(Long studentId) {
        return scoreStudents(List.of(students.requireReadable(studentId))).get(0).risk();
    }

    // ------------------------------------------------------------------ leads

    private List<LeadRow> scoreLeads(List<Lead> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(Lead::getId).toList();
        Instant now = Instant.now();

        Map<Long, Instant[]> contact = new HashMap<>();
        for (Object[] r : em.createQuery("""
                select a.leadId, min(a.createdAt), max(a.createdAt) from LeadActivity a
                where a.leadId in :ids and a.type not in (com.mbbscrm.crm.common.ActivityType.STATUS_CHANGE,
                                                          com.mbbscrm.crm.common.ActivityType.ASSIGNMENT)
                group by a.leadId""", Object[].class).setParameter("ids", ids).getResultList()) {
            contact.put((Long) r[0], new Instant[] {(Instant) r[1], (Instant) r[2]});
        }
        Map<Long, List<Outcome>> calls = new HashMap<>();
        for (CallLog c : em.createQuery("select c from CallLog c where c.leadId in :ids order by c.calledAt desc",
                CallLog.class).setParameter("ids", ids).getResultList()) {
            List<Outcome> recent = calls.computeIfAbsent(c.getLeadId(), k -> new ArrayList<>());
            if (recent.size() < 3) {
                recent.add(c.getOutcome());
            }
        }
        Map<Long, Instant> followUps = new HashMap<>();
        for (Object[] r : em.createQuery("""
                select f.lead.id, min(f.dueAt) from FollowUp f
                where f.completedAt is null and f.lead.id in :ids group by f.lead.id""", Object[].class)
                .setParameter("ids", ids).getResultList()) {
            followUps.put((Long) r[0], (Instant) r[1]);
        }

        // How each source has converted into students so far; ignored until a source has enough leads.
        Map<LeadSource, Double> rates = new EnumMap<>(LeadSource.class);
        long all = 0;
        long allConverted = 0;
        for (Object[] r : em.createQuery("""
                select l.source, count(l), sum(case when l.student is not null then 1 else 0 end)
                from Lead l group by l.source""", Object[].class).getResultList()) {
            long n = ((Number) r[1]).longValue();
            long c = ((Number) r[2]).longValue();
            all += n;
            allConverted += c;
            if (n >= 20) {
                rates.put((LeadSource) r[0], (double) c / n);
            }
        }
        Double overall = all >= 50 ? (double) allConverted / all : null;

        List<LeadRow> out = new ArrayList<>();
        for (Lead l : list) {
            Instant[] c = contact.get(l.getId());
            boolean complete = l.getNeetScore() != null && l.getCategory() != null && l.getHomeState() != null;
            LeadFacts facts = new LeadFacts(l.getStatus(), l.getCreatedAt(), complete, c == null ? null : c[0],
                    c == null ? null : c[1], calls.get(l.getId()), followUps.get(l.getId()), rates.get(l.getSource()),
                    overall);
            out.add(new LeadRow(l.getId(), l.getFullName(), l.getPhone(), l.getStatus(), l.getSource(),
                    UserRef.of(l.getAssignedCounsellor()), l.getCreatedAt(), Scoring.lead(facts, now)));
        }
        return out;
    }

    // ------------------------------------------------------------------ students

    private List<StudentRow> scoreStudents(List<Student> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(Student::getId).toList();
        Instant now = Instant.now();

        Map<Long, Long> overdueDays = new HashMap<>();
        for (FeeService.DueRow d : fees.duesUnchecked().rows()) {
            overdueDays.merge(d.studentId(), d.overdueDays(), Math::max);
        }
        Map<Long, long[]> tickets = new HashMap<>();
        for (Object[] r : em.createQuery("""
                select t.student.id, count(t), sum(case when t.dueAt < :now then 1 else 0 end) from Ticket t
                where t.student.id in :ids and t.status not in (com.mbbscrm.crm.helpdesk.TicketStatus.RESOLVED,
                                                                com.mbbscrm.crm.helpdesk.TicketStatus.CLOSED)
                group by t.student.id""", Object[].class).setParameter("ids", ids).setParameter("now", now)
                .getResultList()) {
            tickets.put((Long) r[0], new long[] {((Number) r[1]).longValue(), ((Number) r[2]).longValue()});
        }
        Map<Long, Long> grievances = new HashMap<>();
        for (Object[] r : em.createQuery("""
                select g.student.id, count(g) from Grievance g
                where g.student.id in :ids and g.status not in (com.mbbscrm.crm.grievance.GrievanceStatus.RESOLVED,
                                                                com.mbbscrm.crm.grievance.GrievanceStatus.CLOSED)
                group by g.student.id""", Object[].class).setParameter("ids", ids).getResultList()) {
            grievances.put((Long) r[0], ((Number) r[1]).longValue());
        }
        Map<Long, List<Outcome>> calls = new HashMap<>();
        for (CallLog c : em.createQuery("select c from CallLog c where c.studentId in :ids order by c.calledAt desc",
                CallLog.class).setParameter("ids", ids).getResultList()) {
            List<Outcome> recent = calls.computeIfAbsent(c.getStudentId(), k -> new ArrayList<>());
            if (recent.size() < 3) {
                recent.add(c.getOutcome());
            }
        }
        Map<Long, Long> alerts = new HashMap<>();
        for (Object[] r : em.createQuery("""
                select m.studentId, count(m) from OutboundMessage m
                where m.studentId in :ids and m.priority = com.mbbscrm.crm.alert.Priority.URGENT
                  and m.acknowledgedAt is null and m.createdAt > :recent
                group by m.studentId""", Object[].class).setParameter("ids", ids)
                .setParameter("recent", now.minus(14, ChronoUnit.DAYS)).getResultList()) {
            alerts.put((Long) r[0], ((Number) r[1]).longValue());
        }

        List<StudentRow> out = new ArrayList<>();
        for (Student s : list) {
            long[] t = tickets.getOrDefault(s.getId(), new long[2]);
            StudentFacts facts = new StudentFacts(overdueDays.getOrDefault(s.getId(), 0L), t[0], t[1],
                    grievances.getOrDefault(s.getId(), 0L), calls.get(s.getId()), alerts.getOrDefault(s.getId(), 0L));
            out.add(new StudentRow(s.getId(), s.getFullName(), s.getPhone(), UserRef.of(s.getAssignedCounsellor()),
                    Scoring.student(facts)));
        }
        return out;
    }
}
