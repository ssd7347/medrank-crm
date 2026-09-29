package com.mbbscrm.crm.alert;

/** Urgent alerts are dispatched first and immediately, never batched (spec 4.9). */
public enum Priority {
    NORMAL, URGENT
}
