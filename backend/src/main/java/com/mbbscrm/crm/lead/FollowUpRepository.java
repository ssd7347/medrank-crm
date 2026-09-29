package com.mbbscrm.crm.lead;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FollowUpRepository extends JpaRepository<FollowUp, Long> {

    @EntityGraph(attributePaths = {"assignedTo", "lead"})
    List<FollowUp> findByLeadIdOrderByDueAtAsc(Long leadId);

    /** Open follow-ups for one user due before {@code before}, oldest first. */
    @EntityGraph(attributePaths = {"assignedTo", "lead"})
    @Query("""
            select f from FollowUp f
            where f.assignedTo.id = :userId and f.completedAt is null and f.dueAt < :before
            order by f.dueAt asc
            """)
    List<FollowUp> findOpenForUserDueBefore(Long userId, Instant before);

    @Query("""
            select count(f) from FollowUp f
            where f.assignedTo.id = :userId and f.completedAt is null and f.dueAt >= :from and f.dueAt < :to
            """)
    long countOpenForUserBetween(Long userId, Instant from, Instant to);
}
