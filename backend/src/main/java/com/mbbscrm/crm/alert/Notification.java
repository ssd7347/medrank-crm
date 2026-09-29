package com.mbbscrm.crm.alert;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An in-app alert for one staff member. */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long recipientId;
    private Long studentId;
    private String type;
    @Enumerated(EnumType.STRING)
    private Priority priority;
    private String title;
    private String body;
    private String link;
    private String dedupKey;
    private Instant createdAt;
    private Instant readAt;

    protected Notification() {
    }

    public Notification(Long recipientId, Long studentId, String type, Priority priority, String title, String body,
                        String link, String dedupKey) {
        this.recipientId = recipientId;
        this.studentId = studentId;
        this.type = type;
        this.priority = priority;
        this.title = title;
        this.body = body;
        this.link = link;
        this.dedupKey = dedupKey;
        this.createdAt = Instant.now();
    }

    public void markRead() {
        if (readAt == null) {
            readAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public Long getRecipientId() { return recipientId; }
    public Long getStudentId() { return studentId; }
    public String getType() { return type; }
    public Priority getPriority() { return priority; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getLink() { return link; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
}
