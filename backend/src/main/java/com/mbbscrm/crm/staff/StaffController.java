package com.mbbscrm.crm.staff;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;

import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Counsellor & staff management (spec 4.11): workload and performance per person, and bulk reassignment
 * when someone leaves or is overloaded. Admin only.
 */
@RestController
@RequestMapping("/api/staff")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class StaffController {

    private static final List<Role> FRONT_LINE = List.of(Role.SUPER_ADMIN, Role.COUNSELLOR, Role.TELECALLER);

    private final EntityManager em;
    private final AppUserRepository users;
    private final AuditService audit;

    public StaffController(EntityManager em, AppUserRepository users, AuditService audit) {
        this.em = em;
        this.users = users;
        this.audit = audit;
    }

    public record StaffRow(Long userId, String fullName, Role role, long openLeads, long newLeadsInPeriod,
                           long convertedToStudent, long admissionsConfirmed, double admissionRate,
                           long studentsAssigned, long overdueFollowUps, long openTickets) {
    }

    @GetMapping("/performance")
    @Transactional(readOnly = true)
    public List<StaffRow> performance(@RequestParam(defaultValue = "30") int days) {
        Instant since = Instant.now().minus(Math.clamp(days, 1, 365), ChronoUnit.DAYS);
        Map<Long, Long> open = count("""
                select l.assignedCounsellor.id, count(l) from Lead l
                where l.assignedCounsellor is not null and l.status not in
                  (com.mbbscrm.crm.common.LeadStatus.CLOSED, com.mbbscrm.crm.common.LeadStatus.ADMISSION_CONFIRMED)
                group by l.assignedCounsellor.id""", null);
        Map<Long, Long> fresh = count("""
                select l.assignedCounsellor.id, count(l) from Lead l
                where l.assignedCounsellor is not null and l.createdAt >= :since group by l.assignedCounsellor.id""",
                since);
        Map<Long, Long> converted = count("""
                select l.assignedCounsellor.id, count(l) from Lead l
                where l.assignedCounsellor is not null and l.student is not null and l.createdAt >= :since
                group by l.assignedCounsellor.id""", since);
        Map<Long, Long> admitted = count("""
                select l.assignedCounsellor.id, count(l) from Lead l
                where l.assignedCounsellor is not null and l.createdAt >= :since
                  and l.status = com.mbbscrm.crm.common.LeadStatus.ADMISSION_CONFIRMED
                group by l.assignedCounsellor.id""", since);
        Map<Long, Long> studentsByCounsellor = count("""
                select s.assignedCounsellor.id, count(s) from Student s
                where s.assignedCounsellor is not null group by s.assignedCounsellor.id""", null);
        Map<Long, Long> overdue = count("""
                select f.assignedTo.id, count(f) from FollowUp f
                where f.completedAt is null and f.dueAt < CURRENT_TIMESTAMP group by f.assignedTo.id""", null);
        Map<Long, Long> tickets = count("""
                select t.assignedTo.id, count(t) from Ticket t
                where t.assignedTo is not null and t.status not in
                  (com.mbbscrm.crm.helpdesk.TicketStatus.RESOLVED, com.mbbscrm.crm.helpdesk.TicketStatus.CLOSED)
                group by t.assignedTo.id""", null);
        return users.findByActiveTrueAndRoleInOrderByFullName(FRONT_LINE).stream().map(u -> {
            long f = fresh.getOrDefault(u.getId(), 0L);
            long a = admitted.getOrDefault(u.getId(), 0L);
            return new StaffRow(u.getId(), u.getFullName(), u.getRole(), open.getOrDefault(u.getId(), 0L), f,
                    converted.getOrDefault(u.getId(), 0L), a, f == 0 ? 0 : Math.round(1000.0 * a / f) / 10.0,
                    studentsByCounsellor.getOrDefault(u.getId(), 0L), overdue.getOrDefault(u.getId(), 0L),
                    tickets.getOrDefault(u.getId(), 0L));
        }).toList();
    }

    public record ReassignRequest(@NotNull Long fromUserId, @NotNull Long toUserId, boolean leads, boolean students,
                                  boolean followUps) {
    }

    public record ReassignResult(int leads, int students, int followUps) {
    }

    /** Moves open work from one person to another, e.g. when a counsellor leaves (spec 4.11, 4.21). */
    @PostMapping("/reassign")
    @Transactional
    public ReassignResult reassign(@Valid @RequestBody ReassignRequest req) {
        if (req.fromUserId().equals(req.toUserId())) {
            throw ApiException.badRequest("Choose two different people");
        }
        AppUser from = users.findById(req.fromUserId()).orElseThrow(() -> ApiException.badRequest("User not found"));
        AppUser to = users.findById(req.toUserId()).filter(AppUser::isActive).filter(u -> FRONT_LINE.contains(u.getRole()))
                .orElseThrow(() -> ApiException.badRequest("The new owner must be an active counsellor, telecaller or admin"));
        int leads = 0;
        int students = 0;
        int followUps = 0;
        if (req.leads()) {
            leads = em.createQuery("""
                    update Lead l set l.assignedCounsellor = :to where l.assignedCounsellor = :from
                      and l.status not in (com.mbbscrm.crm.common.LeadStatus.CLOSED,
                                           com.mbbscrm.crm.common.LeadStatus.ADMISSION_CONFIRMED)""")
                    .setParameter("to", to).setParameter("from", from).executeUpdate();
        }
        if (req.students()) {
            if (to.getRole() == Role.TELECALLER) {
                throw ApiException.badRequest("Students can only be moved to a counsellor or admin");
            }
            students = em.createQuery("update Student s set s.assignedCounsellor = :to where s.assignedCounsellor = :from")
                    .setParameter("to", to).setParameter("from", from).executeUpdate();
        }
        if (req.followUps()) {
            followUps = em.createQuery("""
                    update FollowUp f set f.assignedTo = :to where f.assignedTo = :from and f.completedAt is null""")
                    .setParameter("to", to).setParameter("from", from).executeUpdate();
        }
        audit.record(CurrentUser.get().id(), "WORK_REASSIGNED", "USER", from.getId(), "to=" + to.getId() + " leads="
                + leads + " students=" + students + " followUps=" + followUps);
        return new ReassignResult(leads, students, followUps);
    }

    private Map<Long, Long> count(String jpql, Instant since) {
        var q = em.createQuery(jpql, Object[].class);
        if (since != null) {
            q.setParameter("since", since);
        }
        Map<Long, Long> out = new HashMap<>();
        for (Object[] row : q.getResultList()) {
            out.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }
}
