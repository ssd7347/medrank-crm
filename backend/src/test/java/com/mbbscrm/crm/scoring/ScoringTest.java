package com.mbbscrm.crm.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mbbscrm.crm.common.LeadStatus;
import com.mbbscrm.crm.engagement.CallLog.Outcome;
import com.mbbscrm.crm.scoring.Scoring.Band;
import com.mbbscrm.crm.scoring.Scoring.LeadFacts;
import com.mbbscrm.crm.scoring.Scoring.LeadScore;
import com.mbbscrm.crm.scoring.Scoring.Risk;
import com.mbbscrm.crm.scoring.Scoring.RiskScore;
import com.mbbscrm.crm.scoring.Scoring.StudentFacts;

class ScoringTest {

    private static final Instant NOW = Instant.parse("2026-07-01T06:00:00Z");

    @Test
    void engagedQualifiedLeadIsHot() {
        Instant created = NOW.minus(3, ChronoUnit.DAYS);
        LeadScore s = Scoring.lead(new LeadFacts(LeadStatus.QUALIFIED, created, true, created.plus(2, ChronoUnit.HOURS),
                NOW.minus(1, ChronoUnit.DAYS), List.of(Outcome.CONNECTED), null, null, null), NOW);
        assertThat(s.band()).isEqualTo(Band.HOT);
        assertThat(s.score()).isEqualTo(85);
        assertThat(s.nextAction()).isNull();
        assertThat(s.reasons()).contains("Answered the last call", "First contact was within a day");
    }

    @Test
    void untouchedLeadIsColdAndAsksForACall() {
        LeadScore s = Scoring.lead(new LeadFacts(LeadStatus.NEW, NOW.minus(4, ChronoUnit.DAYS), false, null, null,
                List.of(), null, null, null), NOW);
        assertThat(s.band()).isEqualTo(Band.COLD);
        assertThat(s.score()).isEqualTo(15);
        assertThat(s.nextAction()).isEqualTo("Not contacted yet: call now");
    }

    @Test
    void overdueFollowUpWinsAsNextActionAndUnreachableCallsLowerTheScore() {
        Instant created = NOW.minus(30, ChronoUnit.DAYS);
        LeadScore s = Scoring.lead(new LeadFacts(LeadStatus.NEW, created, true, created.plus(3, ChronoUnit.DAYS),
                NOW.minus(25, ChronoUnit.DAYS), List.of(Outcome.NO_ANSWER, Outcome.BUSY, Outcome.SWITCHED_OFF),
                NOW.minus(1, ChronoUnit.DAYS), null, null), NOW);
        assertThat(s.nextAction()).isEqualTo("Follow-up overdue");
        assertThat(s.score()).isEqualTo(30 + 10 - 15 - 10 - 10);
        assertThat(s.band()).isEqualTo(Band.COLD);
    }

    @Test
    void sourceHistoryMovesTheScoreWithinLimits() {
        Instant created = NOW.minus(1, ChronoUnit.HOURS);
        LeadFacts better = new LeadFacts(LeadStatus.NEW, created, false, null, null, List.of(), null, 0.9, 0.1);
        LeadFacts worse = new LeadFacts(LeadStatus.NEW, created, false, null, null, List.of(), null, 0.0, 0.5);
        assertThat(Scoring.lead(better, NOW).score()).isEqualTo(45);
        assertThat(Scoring.lead(worse, NOW).score()).isEqualTo(15);
    }

    @Test
    void studentRiskAddsUpAndIsCapped() {
        RiskScore calm = Scoring.student(new StudentFacts(0, 0, 0, 0, List.of(Outcome.CONNECTED), 0));
        assertThat(calm.risk()).isEqualTo(Risk.LOW);
        assertThat(calm.score()).isZero();

        RiskScore late = Scoring.student(new StudentFacts(5, 1, 0, 0, List.of(), 0));
        assertThat(late.score()).isEqualTo(30);
        assertThat(late.risk()).isEqualTo(Risk.MEDIUM);

        RiskScore bad = Scoring.student(new StudentFacts(20, 2, 2, 1,
                List.of(Outcome.NO_ANSWER, Outcome.NO_ANSWER, Outcome.BUSY), 1));
        assertThat(bad.score()).isEqualTo(100);
        assertThat(bad.risk()).isEqualTo(Risk.HIGH);
        assertThat(bad.reasons()).hasSize(5);
    }
}
