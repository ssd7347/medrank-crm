package com.mbbscrm.crm.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.approval.DataChangeRequest;
import com.mbbscrm.crm.approval.DataChangeRequestRepository;
import com.mbbscrm.crm.college.CollegeRepository;
import com.mbbscrm.crm.common.LeadSource;
import com.mbbscrm.crm.common.LeadStatus;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.lead.FollowUpRepository;
import com.mbbscrm.crm.lead.LeadRepository;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.StudentRepository;
import com.mbbscrm.crm.student.StudentService;

/** Phase 1 dashboard: the pipeline at a glance, plus today's follow-up load for the viewer (spec 4.13). */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    /** Counselling runs on Indian time; "today" is an IST calendar day. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LeadRepository leads;
    private final FollowUpRepository followUps;
    private final StudentRepository students;
    private final CollegeRepository colleges;
    private final DataChangeRequestRepository changeRequests;

    public DashboardController(LeadRepository leads, FollowUpRepository followUps, StudentRepository students,
                               CollegeRepository colleges, DataChangeRequestRepository changeRequests) {
        this.leads = leads;
        this.followUps = followUps;
        this.students = students;
        this.colleges = colleges;
        this.changeRequests = changeRequests;
    }

    public record Summary(boolean showsLeads, Map<LeadStatus, Long> leadsByStatus, Map<LeadSource, Long> leadsBySource,
                          long totalLeads, long newLeadsLast7Days, long followUpsDueToday, long followUpsOverdue,
                          Long students, long colleges, Long pendingApprovals) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Summary summary() {
        CurrentUser me = CurrentUser.get();
        boolean showsLeads = me.role() == Role.SUPER_ADMIN || me.role() == Role.COUNSELLOR
                || me.role() == Role.TELECALLER;

        Map<LeadStatus, Long> byStatus = new EnumMap<>(LeadStatus.class);
        Map<LeadSource, Long> bySource = new EnumMap<>(LeadSource.class);
        long total = 0;
        long newLeads = 0;
        long dueToday = 0;
        long overdue = 0;
        if (showsLeads) {
            for (LeadStatus s : LeadStatus.values()) {
                byStatus.put(s, 0L);
            }
            fill(byStatus, me.isAdmin() ? leads.countByStatus() : leads.countByStatusScoped(me.id()));
            fill(bySource, me.isAdmin() ? leads.countBySource() : leads.countBySourceScoped(me.id()));
            total = byStatus.values().stream().mapToLong(Long::longValue).sum();
            Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
            newLeads = me.isAdmin() ? leads.countByCreatedAtGreaterThanEqual(weekAgo)
                    : leads.countCreatedSinceScoped(weekAgo, me.id());

            Instant startOfToday = LocalDate.now(IST).atStartOfDay(IST).toInstant();
            Instant startOfTomorrow = startOfToday.plus(1, ChronoUnit.DAYS);
            Instant now = Instant.now();
            dueToday = followUps.countOpenForUserBetween(me.id(), now, startOfTomorrow);
            overdue = followUps.countOpenForUserBetween(me.id(), Instant.EPOCH, now);
        }

        Long studentCount = null;
        if (StudentService.READ_ROLES.contains(me.role())) {
            studentCount = me.role() == Role.COUNSELLOR ? students.countByAssignedCounsellorId(me.id())
                    : students.count();
        }
        Long pending = me.role() == Role.SUPER_ADMIN || me.role() == Role.DATA_EXEC
                ? changeRequests.countByStatus(DataChangeRequest.Status.PENDING) : null;

        return new Summary(showsLeads, byStatus, bySource, total, newLeads, dueToday, overdue, studentCount,
                colleges.count(), pending);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Enum<E>> void fill(Map<E, Long> target, List<Object[]> rows) {
        for (Object[] row : rows) {
            target.put((E) row[0], ((Number) row[1]).longValue());
        }
    }
}
