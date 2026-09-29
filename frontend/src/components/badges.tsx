import { label } from "@/lib/format";
import type { ChangeStatus, LeadStatus } from "@/lib/types";

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
