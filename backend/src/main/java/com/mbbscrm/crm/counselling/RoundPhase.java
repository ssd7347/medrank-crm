package com.mbbscrm.crm.counselling;

import java.time.Instant;

/** Works out which stage a round is in right now, from its deadline windows. */
final class RoundPhase {

    private RoundPhase() {
    }

    static String of(CounsellingRoundEntity r, Instant now) {
        if (r.getReportingEnd() != null && now.isAfter(r.getReportingEnd())) {
            return "CLOSED";
        }
        if (r.getReportingStart() != null && !now.isBefore(r.getReportingStart())) {
            return "REPORTING";
        }
        if (r.getResultAt() != null && !now.isBefore(r.getResultAt())) {
            return "RESULT_OUT";
        }
        if (r.getChoiceFillingEnd() != null && now.isAfter(r.getChoiceFillingEnd())) {
            return "AWAITING_RESULT";
        }
        if (r.getChoiceFillingStart() != null && !now.isBefore(r.getChoiceFillingStart())) {
            return "CHOICE_FILLING";
        }
        if (r.getRegistrationStart() != null && !now.isBefore(r.getRegistrationStart())) {
            return "REGISTRATION";
        }
        return "UPCOMING";
    }
}
