package com.mbbscrm.crm.alert;

/** Delivery state. SIMULATED means no provider is configured and the message was only logged. */
public enum MessageStatus {
    QUEUED, SENT, SIMULATED, FAILED
}
