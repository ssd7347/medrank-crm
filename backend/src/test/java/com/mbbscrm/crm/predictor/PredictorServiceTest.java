package com.mbbscrm.crm.predictor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mbbscrm.crm.college.College;
import com.mbbscrm.crm.common.CollegeType;
import com.mbbscrm.crm.common.CounsellingRound;
import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.Quota;
import com.mbbscrm.crm.predictor.PredictorDtos.Band;
import com.mbbscrm.crm.predictor.PredictorDtos.Prediction;

class PredictorServiceTest {

    private static final College COLLEGE = college();

    /** Round 1 closed at 1,000 (2024) and 1,200 (2025); the last 2025 round closed at 1,800. */
    private static final List<CutoffRow> ROWS = List.of(
            new CutoffRow(COLLEGE, Quota.AIQ, 2024, CounsellingRound.ROUND_1, 1000),
            new CutoffRow(COLLEGE, Quota.AIQ, 2024, CounsellingRound.ROUND_2, 1500),
            new CutoffRow(COLLEGE, Quota.AIQ, 2025, CounsellingRound.ROUND_1, 1200),
            new CutoffRow(COLLEGE, Quota.AIQ, 2025, CounsellingRound.MOP_UP, 1800));

    private static Prediction eval(int rank) {
        return PredictorService.evaluate(rank, Course.MBBS, ROWS, Map.of());
    }

    @Test
    void withinEveryYearsRoundOneIsHigh() {
        assertThat(eval(900).band()).isEqualTo(Band.HIGH);
        assertThat(eval(1000).band()).isEqualTo(Band.HIGH);
    }

    @Test
    void withinLatestFinalClosingIsModerate() {
        assertThat(eval(1001).band()).isEqualTo(Band.MODERATE);
        assertThat(eval(1800).band()).isEqualTo(Band.MODERATE);
    }

    @Test
    void slightlyBeyondIsLowAndFarBeyondIsExcluded() {
        assertThat(eval(1980).band()).isEqualTo(Band.LOW);
        assertThat(eval(1981)).isNull();
    }

    @Test
    void reportsLatestYearAndHistoryNewestFirst() {
        Prediction p = eval(1500);
        assertThat(p.latestYear()).isEqualTo(2025);
        assertThat(p.lastClosingRank()).isEqualTo(1800);
        assertThat(p.history()).hasSize(4);
        assertThat(p.history().get(0).year()).isEqualTo(2025);
    }

    private static College college() {
        College c = new College();
        c.setName("Test Medical College");
        c.setCollegeType(CollegeType.GOVERNMENT);
        c.setState("Tamil Nadu");
        return c;
    }
}
