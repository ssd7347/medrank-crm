package com.aicallcrm.crm.common;

/**
 * Staff roles. There is exactly one SUPER_ADMIN (the account owner). Managers see every contact and can
 * import; agents work the contacts assigned to them (and unassigned ones) and their call-backs.
 */
public enum Role {
    SUPER_ADMIN, MANAGER, AGENT
}
