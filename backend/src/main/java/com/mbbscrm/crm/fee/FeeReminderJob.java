package com.mbbscrm.crm.fee;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.alert.AlertService;
import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentRepository;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;

/**
 * Spec 4.8: reminds families 3 days before an instalment is due, and escalates to the counsellor and
 * accounts staff once it is more than 7 days overdue. Each reminder is sent once per instalment.
 */
@Component
public class FeeReminderJob {

    static final int REMIND_DAYS_BEFORE = 3;
    static final int ESCALATE_DAYS_AFTER = 7;

    private final FeeService fees;
    private final StudentRepository students;
    private final AppUserRepository users;
    private final AlertService alerts;

    public FeeReminderJob(FeeService fees, StudentRepository students, AppUserRepository users, AlertService alerts) {
        this.fees = fees;
        this.students = students;
        this.users = users;
        this.alerts = alerts;
    }

    public record Result(int reminders, int escalations) {
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "${app.alerts.scan-interval:PT10M}")
    @Transactional
    public Result run() {
        LocalDate today = LocalDate.now();
        List<AppUser> accountants = users.findByActiveTrueAndRoleInOrderByFullName(List.of(Role.ACCOUNTANT));
        int reminders = 0;
        int escalations = 0;
        for (FeeService.DueRow d : fees.computeDues(today).rows()) {
            Student s = students.findById(d.studentId()).orElse(null);
            if (s == null) {
                continue;
            }
            long daysToDue = ChronoUnit.DAYS.between(today, d.dueDate());
            if (daysToDue >= 0 && daysToDue <= REMIND_DAYS_BEFORE) {
                if (alerts.messageFamily(s, "FEE_DUE", Priority.NORMAL, s.getFullName() + ": your consultancy fee "
                        + "instalment \"" + d.label() + "\" of Rs " + d.balance().toPlainString() + " is due on "
                        + d.dueDate() + ". Please ignore if already paid.", "FEE:" + d.installmentId() + ":DUE") > 0) {
                    reminders++;
                }
            } else if (d.overdueDays() > ESCALATE_DAYS_AFTER) {
                String key = "FEE:" + d.installmentId() + ":OVERDUE";
                String title = s.getFullName() + ": fee overdue " + d.overdueDays() + " days";
                String body = "\"" + d.label() + "\" Rs " + d.balance().toPlainString() + " was due on " + d.dueDate()
                        + ". Follow up with the family.";
                String link = "/students/" + s.getId() + "?tab=fees";
                int raised = alerts.notifyStaffFor(s, "FEE_OVERDUE", Priority.NORMAL, title, body, link, key);
                for (AppUser a : accountants) {
                    raised += alerts.notifyUser(a.getId(), s.getId(), "FEE_OVERDUE", Priority.NORMAL, title, body,
                            link, key);
                }
                if (raised > 0) {
                    escalations++;
                }
            }
        }
        return new Result(reminders, escalations);
    }
}
