package com.mbbscrm.crm.voice;

/** Codes used across the AI voice agent (spec section 18). */
public final class Voice {

    private Voice() {
    }

    /** Why a call is made (spec 18.2). The two inbound purposes cannot be used for a campaign. */
    public enum Purpose {
        DEADLINE_REMINDER, DOC_NUDGE, FEE_REMINDER, LEAD_QUALIFY, MISSED_CALL_FOLLOWUP, INBOUND_STATUS, INBOUND_FAQ;

        public boolean outbound() {
            return this != INBOUND_STATUS && this != INBOUND_FAQ;
        }

        /** Campaigns of this purpose call leads; the rest call students and parents. */
        public boolean forLeads() {
            return this == LEAD_QUALIFY;
        }
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

    /** How a connected call ended (spec 18.9.1). */
    public enum Outcome {
        ACKNOWLEDGED, WILL_ACT, CALLBACK_REQUESTED, HANDED_OFF, NOT_INTERESTED, OPTED_OUT, WRONG_PERSON,
        NO_INTERACTION, VERIFICATION_FAILED
    }

    /** Why the agent passed a caller to a person (spec 18.8.3). */
    public enum HandoffReason {
        DECISION_ADVICE, REFUND_OR_DISPUTE, DISTRESS, CALLER_REQUEST, TOOL_FAILURE, VERIFICATION_FAILED, OUT_OF_SCOPE;

        public boolean high() {
            return this == DECISION_ADVICE || this == REFUND_OR_DISPUTE || this == DISTRESS;
        }
    }

    public enum CampaignStatus {
        DRAFT, RUNNING, PAUSED, DONE
    }

    public enum TargetStatus {
        PENDING, DONE, SKIPPED, FAILED, SIMULATED
    }

    /** Why a number is not being called. The first four end the target; the rest mean "not right now". */
    public enum SkipReason {
        NO_CONSENT, DND, OPTED_OUT, MAX_ATTEMPTS, NO_LONGER_NEEDED, NOT_INTERESTED, WRONG_PERSON,
        OUTSIDE_WINDOW, NOT_DUE_YET, CALLED_RECENTLY, DAILY_LIMIT;

        public boolean permanent() {
            return ordinal() <= WRONG_PERSON.ordinal();
        }
    }

    public enum PersonType {
        LEAD, STUDENT, PARENT
    }

    public enum ConsentSource {
        WEB_FORM, ONBOARDING, VERBAL_ON_CALL, PAPER_FORM
    }

    public enum CallbackStatus {
        OPEN, ASSIGNED, DONE
    }

    public enum DndResult {
        ALLOWED, BLOCKED, UNKNOWN
    }
}
