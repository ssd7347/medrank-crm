package com.mbbscrm.crm.fee;

/** Refund workflow: requested, then approved or rejected by an admin, then paid by accounts. */
public enum RefundStatus {
    REQUESTED, APPROVED, REJECTED, PAID
}
