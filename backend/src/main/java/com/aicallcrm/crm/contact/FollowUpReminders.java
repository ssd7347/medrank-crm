package com.aicallcrm.crm.contact;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aicallcrm.crm.alert.AlertService;
import com.aicallcrm.crm.alert.Priority;

/**
 * Tells the assignee (in the notification bell) when a follow-up is about to fall due, once per follow-up.
 * Runs every few minutes; reminders go out up to {@link #AHEAD} before the due time.
 */
@Component
class FollowUpReminders {

    static final Duration AHEAD = Duration.ofMinutes(15);
    private static final Logger log = LoggerFactory.getLogger(FollowUpReminders.class);

    private final FollowUpRepository followUps;
    private final AlertService alerts;
    private final TransactionTemplate tx;

    FollowUpReminders(FollowUpRepository followUps, AlertService alerts, PlatformTransactionManager txManager) {
        this.followUps = followUps;
        this.alerts = alerts;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${app.follow-up.reminder-interval:PT5M}")
    void sweep() {
        try {
            send();
        } catch (RuntimeException e) {
            log.warn("Follow-up reminders failed: {}", e.toString());
        }
    }

    /** Sends the reminders that are due; returns how many. In its own transaction (also when called directly). */
    public int send() {
        Integer sent = tx.execute(status -> sendInTransaction());
        return sent == null ? 0 : sent;
    }

    private int sendInTransaction() {
        List<FollowUp> due = followUps.findDueForReminder(Instant.now().plus(AHEAD));
        for (FollowUp f : due) {
            Contact c = f.getContact();
            boolean overdue = f.getDueAt().isBefore(Instant.now());
            alerts.notifyUser(f.getAssignedTo().getId(), c.getId(), "FOLLOW_UP_DUE",
                    overdue ? Priority.URGENT : Priority.NORMAL,
                    (overdue ? "Follow-up overdue: " : "Follow-up due: ") + c.getFullName(),
                    f.getPurpose(), "/contacts/" + c.getId(), "follow-up-" + f.getId());
            f.reminded();
        }
        return due.size();
    }
}
