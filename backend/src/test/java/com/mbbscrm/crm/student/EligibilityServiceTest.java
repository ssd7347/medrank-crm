package com.mbbscrm.crm.student;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.DomicileStatus;
import com.mbbscrm.crm.common.Nationality;
import com.mbbscrm.crm.common.Quota;
import com.mbbscrm.crm.student.EligibilityService.Eligibility;
import com.mbbscrm.crm.student.EligibilityService.Input;

class EligibilityServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private final EligibilityService service = new EligibilityService();

    private static Map<Quota, Boolean> flags(Eligibility e) {
        return e.quotas().stream().collect(Collectors.toMap(EligibilityService.QuotaFlag::quota,
                EligibilityService.QuotaFlag::eligible));
    }

    @Test
    void domiciledIndianGetsAiqStateDeemedManagementButNotNri() {
        Eligibility e = service.evaluate(new Input(true, Nationality.INDIAN, false, "Tamil Nadu",
                DomicileStatus.DOMICILED, Category.GEN, false, null), TODAY);
        Map<Quota, Boolean> f = flags(e);
        assertThat(f).containsEntry(Quota.AIQ, true).containsEntry(Quota.STATE, true)
                .containsEntry(Quota.DEEMED, true).containsEntry(Quota.MANAGEMENT, true)
                .containsEntry(Quota.NRI, false);
        assertThat(e.warnings()).isEmpty();
        assertThat(e.disclaimer()).isNotBlank();
    }

    @Test
    void notQualifiedMeansNothingIsAvailable() {
        Eligibility e = service.evaluate(new Input(false, Nationality.INDIAN, false, "Kerala",
                DomicileStatus.DOMICILED, Category.OBC, false, null), TODAY);
        assertThat(flags(e).values()).containsOnly(false);
    }

    @Test
    void nriIsNotEligibleForAiqOrStateButIsForNriQuota() {
        Map<Quota, Boolean> f = flags(service.evaluate(new Input(true, Nationality.NRI, false, null,
                DomicileStatus.UNKNOWN, Category.GEN, false, null), TODAY));
        assertThat(f).containsEntry(Quota.AIQ, false).containsEntry(Quota.STATE, false)
                .containsEntry(Quota.NRI, true).containsEntry(Quota.DEEMED, true);
    }

    @Test
    void nriSponsoredIndianKeepsAiqAndGainsNriQuota() {
        Map<Quota, Boolean> f = flags(service.evaluate(new Input(true, Nationality.INDIAN, true, "Karnataka",
                DomicileStatus.NON_DOMICILED, Category.GEN, false, null), TODAY));
        assertThat(f).containsEntry(Quota.AIQ, true).containsEntry(Quota.STATE, false)
                .containsEntry(Quota.NRI, true);
    }

    @Test
    void unknownDomicileWarns() {
        Eligibility e = service.evaluate(new Input(true, Nationality.INDIAN, false, "Bihar",
                DomicileStatus.UNKNOWN, Category.GEN, false, null), TODAY);
        assertThat(flags(e)).containsEntry(Quota.STATE, false);
        assertThat(e.warnings()).anyMatch(w -> w.contains("domicile"));
    }

    @Test
    void expiredAndExpiringCategoryCertificatesWarn() {
        Eligibility expired = service.evaluate(new Input(true, Nationality.INDIAN, false, "TN",
                DomicileStatus.DOMICILED, Category.OBC, false, TODAY.minusDays(1)), TODAY);
        assertThat(expired.warnings()).anyMatch(w -> w.contains("expired"));

        Eligibility soon = service.evaluate(new Input(true, Nationality.INDIAN, false, "TN",
                DomicileStatus.DOMICILED, Category.SC, false, TODAY.plusDays(30)), TODAY);
        assertThat(soon.warnings()).anyMatch(w -> w.contains("renewal"));

        Eligibility missing = service.evaluate(new Input(true, Nationality.INDIAN, false, "TN",
                DomicileStatus.DOMICILED, Category.EWS, false, null), TODAY);
        assertThat(missing.warnings()).anyMatch(w -> w.contains("not recorded"));
    }
}
