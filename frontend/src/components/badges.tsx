import { label } from "@/lib/format";
import type { ChangeStatus, LeadStatus } from "@/lib/types";
import type { RoundPhase, TrackStatus } from "@/lib/types-counselling";

import { Badge, type Tone } from "./ui";

const LEAD_TONE: Record<LeadStatus, Tone> = {
  NEW: "blue",
  QUALIFIED: "indigo",
  FEE_PAID: "teal",
  ACTIVELY_COUNSELLED: "amber",
  ADMISSION_CONFIRMED: "green",
  CLOSED: "gray",
};

export function LeadStatusBadge({ status }: { status: LeadStatus }) {
  return <Badge tone={LEAD_TONE[status]}>{label(status)}</Badge>;
}

const CHANGE_TONE: Record<ChangeStatus, Tone> = { PENDING: "amber", APPROVED: "green", REJECTED: "red" };

export function ChangeStatusBadge({ status }: { status: ChangeStatus }) {
  return <Badge tone={CHANGE_TONE[status]}>{label(status)}</Badge>;
}

export const PHASE_TONE: Record<RoundPhase, Tone> = {
  UPCOMING: "gray",
  REGISTRATION: "blue",
  CHOICE_FILLING: "amber",
  AWAITING_RESULT: "indigo",
  RESULT_OUT: "red",
  REPORTING: "teal",
  CLOSED: "gray",
};

export function PhaseBadge({ phase }: { phase: RoundPhase }) {
  return <Badge tone={PHASE_TONE[phase]}>{label(phase)}</Badge>;
}

const TRACK_TONE: Record<TrackStatus, Tone> = {
  NOT_REGISTERED: "gray",
  REGISTERED: "blue",
  CHOICES_FILLED: "indigo",
  ALLOTTED: "amber",
  ADMITTED: "green",
  EXITED: "gray",
};

export function TrackStatusBadge({ status }: { status: TrackStatus }) {
  return <Badge tone={TRACK_TONE[status]}>{label(status)}</Badge>;
}
