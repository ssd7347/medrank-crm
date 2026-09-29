package com.mbbscrm.crm.alert;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A WhatsApp/SMS/email message to a student or parent. The table doubles as the communication log. */
@Entity
@Table(name = "outbound_message")
public class OutboundMessage {

    public static final int MAX_ATTEMPTS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long studentId;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    private String recipient;
    /** STUDENT or PARENT. */
    private String recipientLabel;
    private String template;
    @Enumerated(EnumType.STRING)
    private Priority priority;
    private String body;
    @Enumerated(EnumType.STRING)
    private MessageStatus status = MessageStatus.QUEUED;
    private int attempts;
    private String providerRef;
    private String lastError;
    private String dedupKey;
    private Instant createdAt;
    private Instant sentAt;
    private Instant acknowledgedAt;
    private Long acknowledgedBy;
    private Instant escalatedAt;

    protected OutboundMessage() {
    }

    public OutboundMessage(Long studentId, Channel channel, String recipient, String recipientLabel, String template,
                           Priority priority, String body, String dedupKey) {
        this.studentId = studentId;
        this.channel = channel;
        this.recipient = recipient;
        this.recipientLabel = recipientLabel;
        this.template = template;
        this.priority = priority;
        this.body = body.length() <= 1000 ? body : body.substring(0, 1000);
        this.dedupKey = dedupKey;
        this.createdAt = Instant.now();
    }

    void markDelivered(MessageStatus status, String providerRef) {
        this.status = status;
        this.providerRef = providerRef;
        this.sentAt = Instant.now();
        this.attempts++;
        this.lastError = null;
    }

    void markFailedAttempt(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 300));
        if (attempts >= MAX_ATTEMPTS) {
            this.status = MessageStatus.FAILED;
        }
    }

    void acknowledge(Long userId) {
        if (acknowledgedAt == null) {
            acknowledgedAt = Instant.now();
            acknowledgedBy = userId;
        }
    }

    void markEscalated() {
        escalatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getStudentId() { return studentId; }
    public Channel getChannel() { return channel; }
    public String getRecipient() { return recipient; }
    public String getRecipientLabel() { return recipientLabel; }
    public String getTemplate() { return template; }
    public Priority getPriority() { return priority; }
    public String getBody() { return body; }
    public MessageStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getProviderRef() { return providerRef; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public Instant getEscalatedAt() { return escalatedAt; }
}
