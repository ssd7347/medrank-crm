package com.mbbscrm.crm.engagement;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CallLogRepository extends JpaRepository<CallLog, Long> {

    @EntityGraph(attributePaths = "calledBy")
    List<CallLog> findByLeadIdOrderByCalledAtDesc(Long leadId);

    @EntityGraph(attributePaths = "calledBy")
    List<CallLog> findByStudentIdOrderByCalledAtDesc(Long studentId);
}
