package com.mbbscrm.crm.fee;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeePlanRepository extends JpaRepository<FeePlan, Long> {

    List<FeePlan> findByStudentIdOrderByCreatedAtDesc(Long studentId);

    @EntityGraph(attributePaths = {"student", "student.assignedCounsellor"})
    List<FeePlan> findByStatus(PlanStatus status);
}
