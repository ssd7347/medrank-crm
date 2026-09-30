package com.mbbscrm.crm.portal;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortalAccountStudentRepository extends JpaRepository<PortalAccountStudent, Long> {

    @EntityGraph(attributePaths = "student")
    List<PortalAccountStudent> findByAccountIdOrderByIdAsc(Long accountId);

    @EntityGraph(attributePaths = "account")
    List<PortalAccountStudent> findByStudentIdOrderByIdAsc(Long studentId);

    @EntityGraph(attributePaths = {"student", "student.assignedCounsellor"})
    Optional<PortalAccountStudent> findByAccountIdAndStudentId(Long accountId, Long studentId);

    long countByAccountId(Long accountId);
}
