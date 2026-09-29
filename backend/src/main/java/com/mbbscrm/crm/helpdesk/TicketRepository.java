package com.mbbscrm.crm.helpdesk;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    @EntityGraph(attributePaths = {"student", "student.assignedCounsellor", "assignedTo", "createdBy"})
    @Query("select t from Ticket t where t.status in :statuses order by t.dueAt asc")
    List<Ticket> findByStatuses(Collection<TicketStatus> statuses);

    @EntityGraph(attributePaths = {"student", "assignedTo", "createdBy"})
    List<Ticket> findByStudentIdOrderByCreatedAtDesc(Long studentId);

    /** Open tickets whose response deadline falls before {@code before}. */
    @EntityGraph(attributePaths = {"student", "assignedTo", "createdBy"})
    @Query("""
            select t from Ticket t
            where t.status not in (com.mbbscrm.crm.helpdesk.TicketStatus.RESOLVED,
                                   com.mbbscrm.crm.helpdesk.TicketStatus.CLOSED)
              and t.dueAt < :before
            """)
    List<Ticket> findOpenDueBefore(Instant before);
}
