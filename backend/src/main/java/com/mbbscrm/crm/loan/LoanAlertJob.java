package com.mbbscrm.crm.loan;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.alert.AlertService;
import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.student.Student;

/**
 * Spec 4.26: a loan that is still waiting on the lender a week before the college reporting deadline is a
 * known cause of lost seats. This raises one urgent alert per application and deadline to the student's
 * counsellor and to whoever is handling the loan.
 */
@Component
public class LoanAlertJob {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LoanService loans;
    private final AlertService alerts;

    public LoanAlertJob(LoanService loans, AlertService alerts) {
        this.loans = loans;
        this.alerts = alerts;
    }

    /** Returns how many applications new alerts were raised for. */
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "${app.alerts.loan-scan-interval:PT1H}")
    @Transactional
    public int run() {
        LocalDate today = LocalDate.now(IST);
        int raised = 0;
        for (LoanApplication a : loans.atRisk(today)) {
            Student s = a.getStudent();
            String key = "LOAN:" + a.getId() + ":" + a.getNeededBy();
            boolean late = a.getNeededBy().isBefore(today);
            String title = s.getFullName() + ": loan " + (late ? "not approved, deadline passed" : "approval running late");
            String body = a.getPartner().getName() + " has not sanctioned the loan yet. Money is needed by "
                    + a.getNeededBy() + ". Chase the lender or plan another way to pay.";
            String link = "/students/" + s.getId() + "?tab=loans";
            int n = alerts.notifyStaffFor(s, "LOAN_AT_RISK", Priority.URGENT, title, body, link, key);
            if (a.getHandledBy() != null && a.getHandledBy().isActive()) {
                n += alerts.notifyUser(a.getHandledBy().getId(), s.getId(), "LOAN_AT_RISK", Priority.URGENT, title, body,
                        link, key);
            }
            if (n > 0) {
                raised++;
            }
        }
        return raised;
    }
}
