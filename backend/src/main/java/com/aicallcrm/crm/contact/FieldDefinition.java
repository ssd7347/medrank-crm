package com.aicallcrm.crm.contact;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An extra column the customer keeps about each contact, such as "Amount due", "Due date" or "Customer ID".
 * Usually created from a spreadsheet heading during an import.
 */
@Entity
@Table(name = "field_definition")
public class FieldDefinition {

    public enum Type {
        TEXT, NUMBER, AMOUNT, DATE
    }

    /** What the AI agent may say about this field on a call. */
    public enum AgentAccess {
        /** Never sent to the AI. */
        NONE,
        /** Only once the caller has proved who they are. */
        AFTER_VERIFY,
        /** To anyone who answers the phone. Use only for harmless details. */
        ANYONE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fieldKey;
    private String label;
    @Enumerated(EnumType.STRING)
    private Type fieldType = Type.TEXT;
    @Enumerated(EnumType.STRING)
    private AgentAccess agentAccess = AgentAccess.AFTER_VERIFY;
    /** The caller may be asked to confirm this value to prove who they are (e.g. a customer ID). */
    private boolean verifyWith;
    private int sortOrder = 100;
    private boolean active = true;
    private Instant createdAt;

    protected FieldDefinition() {
    }

    public FieldDefinition(String fieldKey, String label, Type fieldType) {
        this.fieldKey = fieldKey;
        this.label = label;
        this.fieldType = fieldType;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getFieldKey() { return fieldKey; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Type getFieldType() { return fieldType; }
    public void setFieldType(Type fieldType) { this.fieldType = fieldType; }
    public AgentAccess getAgentAccess() { return agentAccess; }
    public void setAgentAccess(AgentAccess agentAccess) { this.agentAccess = agentAccess; }
    public boolean isVerifyWith() { return verifyWith; }
    public void setVerifyWith(boolean verifyWith) { this.verifyWith = verifyWith; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
}
