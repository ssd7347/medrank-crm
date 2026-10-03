package com.aicallcrm.crm.contact;

/**
 * Keys of the built-in stages. A contact's status is a stage key; customers add and rename stages themselves
 * (see {@link StageService}), but these five always exist because the AI agent moves people between them.
 * DO_NOT_CALL is never dialled by the AI agent, whatever the consent says.
 */
public final class ContactStatus {

    public static final String NEW = "NEW";
    public static final String CONTACTED = "CONTACTED";
    public static final String INTERESTED = "INTERESTED";
    public static final String NOT_INTERESTED = "NOT_INTERESTED";
    public static final String DO_NOT_CALL = "DO_NOT_CALL";

    private ContactStatus() {
    }
}
