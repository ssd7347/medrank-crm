package com.aicallcrm.crm.voice;

/** Codes used across the AI voice agent. */
public final class Voice {

    private Voice() {
    }

    /** OUTBOUND scripts are used by campaigns; the INBOUND one answers people who ring the company. */
    public enum ScriptKind {
        OUTBOUND, INBOUND
    }

    public enum Direction {
        OUTBOUND, INBOUND
    }

    /** SIMULATED: no provider is connected, so the call was recorded but never placed. */
    public enum CallStatus {
        QUEUED, DIALING, CONNECTED, COMPLETED, NO_ANSWER, FAILED, SIMULATED;

        public boolean live() {
            return this == QUEUED || this == DIALING || this == CONNECTED;
        }
    }

    /** How a connected call ended. */
    public enum Outcome {
        ACKNOWLEDGED, WILL_ACT, INTERESTED, CALLBACK_REQUESTED, HANDED_OFF, NOT_INTERESTED, OPTED_OUT, WRONG_PERSON,
        NO_INTERACTION, VERIFICATION_FAILED
    }

    /** Why the agent passed a caller to a person. */
    public enum HandoffReason {
        COMPLAINT_OR_DISPUTE, DISTRESS, CALLER_REQUEST, TOOL_FAILURE, VERIFICATION_FAILED, OUT_OF_SCOPE;

        public boolean high() {
            return this == COMPLAINT_OR_DISPUTE || this == DISTRESS;
        }
    }

    public enum CampaignStatus {
        DRAFT, RUNNING, PAUSED, DONE
    }

    public enum TargetStatus {
        PENDING, DONE, SKIPPED, FAILED, SIMULATED
    }

    /** Why a number is not being called. The first group ends the target; the rest mean "not right now". */
    public enum SkipReason {
        NO_CONSENT, DND, OPTED_OUT, DO_NOT_CALL, MAX_ATTEMPTS, NO_LONGER_NEEDED, NOT_INTERESTED, WRONG_PERSON,
        OUTSIDE_WINDOW, NOT_DUE_YET, CALLED_RECENTLY, DAILY_LIMIT;

        public boolean permanent() {
            return ordinal() <= WRONG_PERSON.ordinal();
        }
    }

    /** How a campaign narrows contacts down by one custom field. */
    public enum FieldOp {
        /** A date field falls between today and N days from now (N = the filter value). */
        DUE_WITHIN_DAYS,
        /** A date field is before today. */
        OVERDUE,
        /** The field has any value. */
        NOT_EMPTY,
        /** A number or amount field is greater than the filter value. */
        GREATER_THAN,
        /** The field equals the filter value (ignoring case). */
        EQUALS
    }

    public enum ConsentSource {
        WEB_FORM, SIGNUP_FORM, VERBAL_ON_CALL, PAPER_FORM, IMPORTED_LIST
    }

    public enum CallbackStatus {
        OPEN, ASSIGNED, DONE
    }

    public enum DndResult {
        ALLOWED, BLOCKED, UNKNOWN
    }
}
