package com.mbbscrm.crm.fee;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeeRefundRepository extends JpaRepository<FeeRefund, Long> {

    @EntityGraph(attributePaths = {"requestedBy", "decidedBy"})
    List<FeeRefund> findByPlanIdInOrderByRequestedAtDesc(Collection<Long> planIds);

    @EntityGraph(attributePaths = {"plan", "plan.student", "requestedBy"})
    List<FeeRefund> findByStatusInOrderByRequestedAtAsc(Collection<RefundStatus> statuses);
}
