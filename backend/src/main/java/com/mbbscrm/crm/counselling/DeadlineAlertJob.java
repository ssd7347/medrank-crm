package com.mbbscrm.crm.counselling;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.alert.AlertService;
import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.student.Student;

/**
 * Spec 4.9 "never miss a deadline": periodically looks ahead and raises urgent alerts for
 * (a) students whose choice list for a round is not locked as choice filling is about to close, and
 * (b) allotted seats with no decision as the reporting deadline approaches.
 * Dedup keys make every alert fire once, however often the job runs.
 */
@Component
public class DeadlineAlertJob {

    private static final EnumSet<CounsellingStatus> NEEDS_CHOICES =
            EnumSet.of(CounsellingStatus.REGISTERED, CounsellingStatus.CHOICES_FILLED, CounsellingStatus.ALLOTTED);

    private final RoundRepository rounds;
    private final StudentCounsellingRepository tracks;
    private final ChoiceListRepository choiceLists;
    private final AllotmentRepository allotments;
    private final AlertService alerts;
    private final AlertTexts texts;
    private final Duration lookAhead;

    public DeadlineAlertJob(RoundRepository rounds, StudentCounsellingRepository tracks,
                            ChoiceListRepository choiceLists, AllotmentRepository allotments, AlertService alerts,
                            AlertTexts texts, @Value("${app.alerts.look-ahead:PT24H}") Duration lookAhead) {
        this.rounds = rounds;
        this.tracks = tracks;
        this.choiceLists = choiceLists;
        this.allotments = allotments;
        this.alerts = alerts;
        this.texts = texts;
        this.lookAhead = lookAhead;
    }

    /** Counts students for whom new alerts were raised in this run. */
    public record ScanResult(int choiceFillingAlerts, int decisionAlerts) {
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "${app.alerts.scan-interval:PT10M}")
    @Transactional
    public ScanResult run() {
        Instant now = Instant.now();
        Instant until = now.plus(lookAhead);
        int choice = 0;
        for (CounsellingRoundEntity round : rounds.findChoiceFillingClosingBetween(now, until)) {
            Set<Long> locked = new HashSet<>(choiceLists.findLockedTrackIds(round.getId()));
            for (StudentCounselling t : tracks.findActive(round.getAuthority().getId(), round.getAcademicYear(),
                    NEEDS_CHOICES)) {
                if (locked.contains(t.getId())) {
                    continue;
                }
                Student s = t.getStudent();
                String key = "CFC:" + round.getId() + ":" + t.getId();
                int raised = alerts.messageFamily(s, "CHOICE_FILLING_CLOSING", Priority.URGENT,
                        texts.choiceFillingClosing(s, round), key);
                raised += alerts.notifyStaffFor(s, "CHOICE_FILLING_CLOSING", Priority.URGENT,
                        s.getFullName() + ": choices not locked",
                        round.label() + " choice filling closes " + AlertTexts.when(round.getChoiceFillingEnd())
                                + ".", "/students/" + s.getId() + "?tab=counselling", key);
                if (raised > 0) {
                    choice++;
                }
            }
        }
        int decisions = 0;
        for (AllotmentResult a : allotments.findUndecidedDueBetween(now, until)) {
            Student s = a.getStudentCounselling().getStudent();
            String key = "DD:" + a.getId();
            int raised = alerts.messageFamily(s, "DECISION_DUE", Priority.URGENT,
                    texts.decisionDue(s, a.getRound(), a.getCollege(), a.getDecisionDeadline()), key);
            raised += alerts.notifyStaffFor(s, "DECISION_DUE", Priority.URGENT,
                    s.getFullName() + ": decision due on " + a.getCollege().getName(),
                    "Freeze / float / withdraw not recorded. Reporting closes "
                            + AlertTexts.when(a.getDecisionDeadline()) + ".",
                    "/students/" + s.getId() + "?tab=counselling", key);
            if (raised > 0) {
                decisions++;
            }
        }
        return new ScanResult(choice, decisions);
    }
}
