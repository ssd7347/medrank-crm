package com.mbbscrm.crm.agreement;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentAgreementRepository extends JpaRepository<StudentAgreement, Long> {

    List<StudentAgreement> findByStudentIdOrderByIssuedAtDesc(Long studentId);
}
