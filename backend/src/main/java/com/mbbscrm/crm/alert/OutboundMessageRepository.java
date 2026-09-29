package com.mbbscrm.crm.alert;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboundMessageRepository extends JpaRepository<OutboundMessage, Long> {

    boolean existsByDedupKey(String dedupKey);

    /** Oldest queued first, urgent before normal. */
    @Query("""
            select m from OutboundMessage m where m.status = com.mbbscrm.crm.alert.MessageStatus.QUEUED
            order by case when m.priority = com.mbbscrm.crm.alert.Priority.URGENT then 0 else 1 end, m.createdAt
            """)
    List<OutboundMessage> findQueued(Pageable pageable);

    /** Urgent messages delivered before {@code cutoff} that nobody has acknowledged or escalated yet. */
    @Query("""
            select m from OutboundMessage m
            where m.priority = com.mbbscrm.crm.alert.Priority.URGENT
              and m.status in (com.mbbscrm.crm.alert.MessageStatus.SENT, com.mbbscrm.crm.alert.MessageStatus.SIMULATED)
              and m.acknowledgedAt is null and m.escalatedAt is null and m.sentAt < :cutoff
            """)
    List<OutboundMessage> findUnacknowledgedUrgent(Instant cutoff);

    List<OutboundMessage> findByStudentIdOrderByCreatedAtDesc(Long studentId, Pageable pageable);

    long countByPriorityAndAcknowledgedAtIsNullAndEscalatedAtIsNotNull(Priority priority);
}
