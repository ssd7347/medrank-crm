package com.aicallcrm.crm.contact;

/**
 * A contact has just become "Do not call" (on the contact page, by a bulk stage change, by merging duplicates, or
 * from an AI call). Published inside that change's transaction, so whatever listens acts in it too.
 *
 * @param byUserId the staff member who made the change; null when nobody signed in did (an AI call)
 */
public record ContactMarkedDoNotCall(Long contactId, Long byUserId) {
}
