package com.mbbscrm.crm.helpdesk;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketCommentRepository extends JpaRepository<TicketComment, Long> {

    @EntityGraph(attributePaths = "author")
    List<TicketComment> findByTicketIdOrderByCreatedAtAsc(Long ticketId);
}
