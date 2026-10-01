package com.aicallcrm.crm.engagement;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CallLogRepository extends JpaRepository<CallLog, Long> {

    @EntityGraph(attributePaths = "calledBy")
    List<CallLog> findByContactIdOrderByCalledAtDesc(Long contactId);

    List<CallLog> findByCalledAtGreaterThanEqualOrderByCalledAtAsc(java.time.Instant since);
}
