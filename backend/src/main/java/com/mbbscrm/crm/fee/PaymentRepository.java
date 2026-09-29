package com.mbbscrm.crm.fee;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @EntityGraph(attributePaths = "receivedBy")
    List<Payment> findByPlanIdInOrderByPaidOnAscCreatedAtAsc(Collection<Long> planIds);

    @Query("""
            select coalesce(sum(p.amount), 0) from Payment p
            where p.voided = false and p.paidOn >= :from and p.paidOn <= :to
            """)
    BigDecimal sumCollectedBetween(LocalDate from, LocalDate to);
}
