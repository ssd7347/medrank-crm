package com.mbbscrm.crm.fee;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mbbscrm.crm.document.DocumentServiceAccess;

class FeeAllocationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);

    private static List<FeeInstallment> installments() {
        FeePlan plan = new FeePlan();
        return List.of(
                new FeeInstallment(plan, 1, "Registration", new BigDecimal("10000.00"), TODAY.minusDays(20)),
                new FeeInstallment(plan, 2, "After Round 1", new BigDecimal("20000.00"), TODAY.minusDays(1)),
                new FeeInstallment(plan, 3, "On admission", new BigDecimal("20000.00"), TODAY.plusDays(10)));
    }

    @Test
    void paymentsFillInstalmentsInOrder() {
        var v = FeeService.allocate(installments(), new BigDecimal("15000"), TODAY);
        assertThat(v.get(0).state()).isEqualTo("PAID");
        assertThat(v.get(1).state()).isEqualTo("OVERDUE");
        assertThat(v.get(1).balance()).isEqualByComparingTo("15000");
        assertThat(v.get(2).state()).isEqualTo("DUE");
    }

    @Test
    void partialPaymentOnAFutureInstalmentIsPartial() {
        var v = FeeService.allocate(installments(), new BigDecimal("35000"), TODAY);
        assertThat(v.get(1).state()).isEqualTo("PAID");
        assertThat(v.get(2).state()).isEqualTo("PARTIAL");
        assertThat(v.get(2).balance()).isEqualByComparingTo("15000");
    }

    @Test
    void packageScheduleIsScaledToTheDiscountedAmountAndAddsUpExactly() {
        ServicePackage p = new ServicePackage();
        p.setTotalAmount(new BigDecimal("50000"));
        p.getInstallments().add(new PackageInstallment(p, 1, "A", new BigDecimal("10000"), 0));
        p.getInstallments().add(new PackageInstallment(p, 2, "B", new BigDecimal("20000"), 30));
        p.getInstallments().add(new PackageInstallment(p, 3, "C", new BigDecimal("20000"), 60));
        var list = FeeService.defaultInstallments(p, new BigDecimal("45000.00"), TODAY);
        assertThat(list).hasSize(3);
        assertThat(list.stream().map(FeeService.InstallmentInput::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("45000");
        assertThat(list.get(1).dueDate()).isEqualTo(TODAY.plusDays(30));
    }

    @Test
    void uploadsAreIdentifiedByContentNotName() {
        assertThat(DocumentServiceAccess.sniff("%PDF-1.7".getBytes())).isEqualTo("application/pdf");
        assertThat(DocumentServiceAccess.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo("image/jpeg");
        assertThat(DocumentServiceAccess.sniff(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}))
                .isEqualTo("image/png");
        assertThat(DocumentServiceAccess.sniff("<html>".getBytes())).isNull();
    }
}
