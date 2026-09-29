package com.mbbscrm.crm.refund;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRuleRepository extends JpaRepository<RefundRule, Long> {

    List<RefundRule> findByAcademicYear(int academicYear);

    List<RefundRule> findByAcademicYearOrderByAuthorityIdAscCollegeIdAscRoundTypeAsc(int academicYear);
}
