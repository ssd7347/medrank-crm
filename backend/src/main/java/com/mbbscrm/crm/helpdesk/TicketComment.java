package com.mbbscrm.crm.helpdesk;

import java.time.Instant;

import com.mbbscrm.crm.user.AppUser;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "ticket_comment")
public class TicketComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long ticketId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private AppUser author;

    private String body;
    private Instant createdAt;

    protected TicketComment() {
    }

    public TicketComment(Long ticketId, AppUser author, String body) {
        this.ticketId = ticketId;
        this.author = author;
        this.body = body;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getTicketId() { return ticketId; }
    public AppUser getAuthor() { return author; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
