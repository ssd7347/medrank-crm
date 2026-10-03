"use client";

import { useStages } from "@/lib/stages";

/** A contact's stage, in the customer's own name and colour. */
export function ContactStatusBadge({ status }: { status: string }) {
  const { label, color } = useStages();
  const c = color(status);
  return (
    <span
      className="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap"
      style={{ backgroundColor: `color-mix(in srgb, ${c} 14%, white)`, color: `color-mix(in srgb, ${c} 70%, var(--color-ink))` }}
    >
      <span aria-hidden className="size-1.5 rounded-full" style={{ backgroundColor: c }} />
      {label(status)}
    </span>
  );
}
