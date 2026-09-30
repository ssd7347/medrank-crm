package com.mbbscrm.crm.engagement;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CounsellingSessionRepository extends JpaRepository<CounsellingSession, Long> {

    @EntityGraph(attributePaths = {"host", "student"})
    List<CounsellingSession> findByStudentIdOrderByScheduledAtDesc(Long studentId);

    @EntityGraph(attributePaths = {"host", "student"})
    List<CounsellingSession> findByHostIdAndScheduledAtBetweenOrderByScheduledAtAsc(Long hostId, Instant from, Instant to);
}
