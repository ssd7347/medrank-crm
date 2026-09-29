package com.mbbscrm.crm.helpdesk;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.alert.AlertService;
import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;

/**
 * SLA reminders (spec 4.14): the assignee is reminded an hour before a ticket's response deadline, and the
 * assignee plus admins are alerted once it is breached.
 */
@Component
public class TicketSlaJob {

    private final TicketRepository tickets;
    private final AppUserRepository users;
    private final AlertService alerts;

    public TicketSlaJob(TicketRepository tickets, AppUserRepository users, AlertService alerts) {
        this.tickets = tickets;
        this.users = users;
        this.alerts = alerts;
    }

    @Scheduled(initialDelayString = "PT3M", fixedDelayString = "${app.alerts.scan-interval:PT10M}")
    @Transactional
    public int run() {
        Instant now = Instant.now();
        List<AppUser> admins = users.findByActiveTrueAndRoleInOrderByFullName(List.of(Role.SUPER_ADMIN));
        int raised = 0;
        for (Ticket t : tickets.findOpenDueBefore(now.plus(Duration.ofHours(1)))) {
            Long studentId = t.getStudent() == null ? null : t.getStudent().getId();
            String link = "/tickets/" + t.getId();
            boolean breached = t.getDueAt().isBefore(now);
            if (!breached && t.getAssignedTo() != null) {
                raised += alerts.notifyUser(t.getAssignedTo().getId(), studentId, "TICKET_DUE_SOON", Priority.NORMAL,
                        "Ticket due within an hour: " + t.getSubject(), null, link, "SLA:" + t.getId() + ":SOON");
            } else if (breached) {
                String title = "Ticket overdue: " + t.getSubject();
                Priority p = t.getPriority() == TicketPriority.URGENT || t.isDeadlineLinked()
                        ? Priority.URGENT : Priority.NORMAL;
                if (t.getAssignedTo() != null) {
                    raised += alerts.notifyUser(t.getAssignedTo().getId(), studentId, "TICKET_OVERDUE", p, title,
                            null, link, "SLA:" + t.getId() + ":BREACH");
                }
                for (AppUser a : admins) {
                    raised += alerts.notifyUser(a.getId(), studentId, "TICKET_OVERDUE", p, title,
                            t.getAssignedTo() == null ? "Unassigned" : "Assigned to " + t.getAssignedTo().getFullName(),
                            link, "SLA:" + t.getId() + ":BREACH");
                }
            }
        }
        return raised;
    }
}
