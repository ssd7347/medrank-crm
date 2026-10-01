package com.aicallcrm.crm.alert;

/** Urgent alerts are dispatched first and immediately, never batched. */
public enum Priority {
    NORMAL, URGENT
}
