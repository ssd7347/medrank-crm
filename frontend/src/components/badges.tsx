import { label } from "@/lib/format";
import type { ContactStatus } from "@/lib/types";

import { Badge, type Tone } from "./ui";

const STATUS_TONE: Record<ContactStatus, Tone> = {
  NEW: "blue",
  CONTACTED: "indigo",
  INTERESTED: "amber",
  CUSTOMER: "green",
  NOT_INTERESTED: "gray",
  DO_NOT_CALL: "red",
};

export function ContactStatusBadge({ status }: { status: ContactStatus }) {
  return <Badge tone={STATUS_TONE[status]}>{label(status)}</Badge>;
}
