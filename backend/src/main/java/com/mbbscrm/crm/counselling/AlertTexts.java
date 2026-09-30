package com.mbbscrm.crm.counselling;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mbbscrm.crm.college.College;
import com.mbbscrm.crm.common.Quota;

/**
 * Message templates for students and parents. English for now; spec 4.25 adds Tamil/Hindi variants chosen by
 * the student's language preference. WhatsApp templates must also be pre-approved by Meta through the BSP.
 */
@Component
public class AlertTexts {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH).withZone(ZoneId.of("Asia/Kolkata"));

    private final String org;

    public AlertTexts(@Value("${app.org-name}") String org) {
        this.org = org;
    }

    public static String when(Instant t) {
        return t == null ? "the deadline" : WHEN.format(t) + " IST";
    }

    String choiceFillingClosing(String studentName, CounsellingRoundEntity round) {
        return "Reminder from " + org + ": " + round.label() + " choice filling closes on "
                + when(round.getChoiceFillingEnd()) + ". " + studentName
                + "'s choices are not locked yet. Please contact your counsellor today.";
    }

    String allotted(String studentName, CounsellingRoundEntity round, College college, Quota quota, Instant deadline) {
        return studentName + ": " + round.label() + " result - seat allotted at " + college.getName() + " ("
                + quota + "). Decide and report by " + when(deadline)
                + ". Please call your counsellor before deciding. - " + org;
    }

    String notAllotted(String studentName, CounsellingRoundEntity round) {
        return studentName + ": no seat was allotted in " + round.label()
                + ". Your counsellor will guide you for the next round. - " + org;
    }

    String decisionDue(String studentName, CounsellingRoundEntity round, College college, Instant deadline) {
        return "Urgent: " + studentName + " must decide on the " + college.getName() + " seat (" + round.label()
                + ") before " + when(deadline) + ". Call your counsellor now. - " + org;
    }
}
