package com.mbbscrm.crm.voice;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Marks a provider event as handled, so a second delivery of the same event does nothing (spec 18.5.4). */
@Entity
@Table(name = "voice_webhook_event")
public class VoiceWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String providerCallId;
    private String eventType;
    private Instant receivedAt;

    protected VoiceWebhookEvent() {
    }

    public VoiceWebhookEvent(String providerCallId, String eventType) {
        this.providerCallId = providerCallId;
        this.eventType = eventType;
        this.receivedAt = Instant.now();
    }
}
