package com.mbbscrm.crm.refund;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mbbscrm.crm.common.CounsellingRound;
import com.mbbscrm.crm.refund.RefundRuleEngine.Assessment;
import com.mbbscrm.crm.refund.RefundRuleEngine.Seat;

class RefundRuleEngineTest {

    private static final Instant ALLOTTED = Instant.parse("2026-08-01T10:00:00Z");
    private static final Seat ROUND2_SEAT = new Seat(2026, 1L, 50L, CounsellingRound.ROUND_2, ALLOTTED);

    private static RefundRule rule(Long authority, Long college, CounsellingRound round, Integer maxDays,
                                   boolean forfeit, int refundPct, String source) {
        RefundRule r = new RefundRule();
        r.setAcademicYear(2026);
        r.setAuthorityId(authority);
        r.setCollegeId(college);
        r.setRoundType(round);
        r.setMaxDaysAfterAllotment(maxDays);
        r.setDepositForfeited(forfeit);
        r.setDepositAmount(BigDecimal.valueOf(200000));
        r.setTuitionRefundPercent(BigDecimal.valueOf(refundPct));
        r.setSource(source);
        return r;
    }

    @Test
    void noRuleSaysSoInsteadOfGuessing() {
        Assessment a = RefundRuleEngine.assess(List.of(), ROUND2_SEAT, ALLOTTED);
        assertThat(a.ruleFound()).isFalse();
        assertThat(a.lines().get(0)).contains("No withdrawal rule");
    }

    @Test
    void mostSpecificRuleWins() {
        List<RefundRule> rules = List.of(
                rule(1L, null, null, null, false, 100, "authority-wide"),
                rule(1L, null, CounsellingRound.ROUND_2, null, true, 0, "round 2 rule"),
                rule(1L, 50L, CounsellingRound.ROUND_2, null, true, 50, "college rule"),
                rule(1L, 99L, null, null, false, 100, "other college"));
        Assessment a = RefundRuleEngine.assess(rules, ROUND2_SEAT, ALLOTTED);
        assertThat(a.source()).isEqualTo("college rule");
        assertThat(a.depositForfeited()).isTrue();
        assertThat(a.tuitionRefundPercent()).isEqualByComparingTo("50");
    }

    @Test
    void timeTiersPickTheFirstBoundNotYetPassed() {
        List<RefundRule> rules = List.of(
                rule(1L, null, CounsellingRound.ROUND_2, 7, false, 90, "within a week"),
                rule(1L, null, CounsellingRound.ROUND_2, 30, true, 50, "within a month"),
                rule(1L, null, CounsellingRound.ROUND_2, null, true, 0, "after that"));
        assertThat(RefundRuleEngine.assess(rules, ROUND2_SEAT, ALLOTTED.plus(3, ChronoUnit.DAYS)).source())
                .isEqualTo("within a week");
        assertThat(RefundRuleEngine.assess(rules, ROUND2_SEAT, ALLOTTED.plus(10, ChronoUnit.DAYS)).source())
                .isEqualTo("within a month");
        assertThat(RefundRuleEngine.assess(rules, ROUND2_SEAT, ALLOTTED.plus(60, ChronoUnit.DAYS)).source())
                .isEqualTo("after that");
    }

    @Test
    void otherYearsAndRoundsDoNotMatch() {
        RefundRule other = rule(1L, null, CounsellingRound.ROUND_1, null, false, 100, "round 1 only");
        RefundRule lastYear = rule(1L, null, null, null, false, 100, "2025");
        lastYear.setAcademicYear(2025);
        assertThat(RefundRuleEngine.assess(List.of(other, lastYear), ROUND2_SEAT, ALLOTTED).ruleFound()).isFalse();
    }
}
