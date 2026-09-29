package com.mbbscrm.crm.document;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.alert.AlertService;
import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.counselling.AlertTexts;
import com.mbbscrm.crm.counselling.AllotmentRepository;
import com.mbbscrm.crm.counselling.AllotmentResult;
import com.mbbscrm.crm.student.Student;

/**
 * Spec 4.7: alert staff and the family when a required document is still missing ahead of reporting, and
 * staff when a certificate is about to expire. Runs with the other scans; dedup keys make it idempotent.
 */
@Component
public class DocumentAlertJob {

    private final AllotmentRepository allotments;
    private final StudentDocumentRepository docs;
    private final DocumentService documents;
    private final AlertService alerts;

    public DocumentAlertJob(AllotmentRepository allotments, StudentDocumentRepository docs, DocumentService documents,
                            AlertService alerts) {
        this.allotments = allotments;
        this.docs = docs;
        this.documents = documents;
        this.alerts = alerts;
    }

    public record Result(int missingAlerts, int expiryAlerts) {
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${app.alerts.scan-interval:PT10M}")
    @Transactional
    public Result run() {
        int missing = 0;
        Instant now = Instant.now();
        // Allotted seats reporting within 7 days: the student will need every required original.
        for (AllotmentResult a : allotments.findReportingBetween(now, now.plus(7, ChronoUnit.DAYS))) {
            Student s = a.getStudentCounselling().getStudent();
            var checklist = documents.buildChecklist(s);
            if (checklist.missingRequired().isEmpty()) {
                continue;
            }
            String list = String.join(", ", checklist.missingRequired());
            String key = "DOCS:" + a.getId();
            int raised = alerts.messageFamily(s, "DOCUMENTS_MISSING", Priority.NORMAL,
                    s.getFullName() + ": please keep these original documents ready for reporting by "
                            + AlertTexts.when(a.getDecisionDeadline()) + ": " + list + ".", key);
            raised += alerts.notifyStaffFor(s, "DOCUMENTS_MISSING", Priority.URGENT,
                    s.getFullName() + ": " + checklist.missingRequired().size() + " documents not verified",
                    "Reporting closes " + AlertTexts.when(a.getDecisionDeadline()) + ". Missing: " + list,
                    "/students/" + s.getId() + "?tab=documents", key);
            if (raised > 0) {
                missing++;
            }
        }
        int expiry = 0;
        for (StudentDocument d : docs.findExpiringBy(LocalDate.now().plusDays(DocumentService.EXPIRY_WARNING_DAYS))) {
            Student s = d.getStudent();
            boolean expired = d.getValidUntil().isBefore(LocalDate.now());
            int raised = alerts.notifyStaffFor(s, "DOCUMENT_EXPIRING", expired ? Priority.URGENT : Priority.NORMAL,
                    s.getFullName() + ": " + d.getDocumentType().getName() + (expired ? " expired" : " expiring"),
                    (expired ? "Expired on " : "Valid until ") + d.getValidUntil() + ". Ask the family to renew it.",
                    "/students/" + s.getId() + "?tab=documents", "EXP:" + d.getId() + ":" + d.getValidUntil());
            if (raised > 0) {
                expiry++;
            }
        }
        return new Result(missing, expiry);
    }
}
