package com.mbbscrm.crm.loan;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanApplicationRepository extends JpaRepository<LoanApplication, Long> {

    @EntityGraph(attributePaths = {"partner", "handledBy", "student"})
    List<LoanApplication> findByStudentIdOrderByCreatedAtDesc(Long studentId);

    @EntityGraph(attributePaths = {"partner", "handledBy", "student", "student.assignedCounsellor", "student.branch"})
    List<LoanApplication> findByStatusInOrderByNeededByAsc(Collection<LoanApplication.Status> statuses);
}
