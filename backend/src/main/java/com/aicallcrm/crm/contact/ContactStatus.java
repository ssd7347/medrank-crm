package com.aicallcrm.crm.contact;

/** Where a contact stands. DO_NOT_CALL is never dialled by the AI agent, whatever the consent says. */
public enum ContactStatus {
    NEW, CONTACTED, INTERESTED, CUSTOMER, NOT_INTERESTED, DO_NOT_CALL
}
