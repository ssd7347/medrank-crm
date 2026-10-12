"use client";

import { useStages } from "@/lib/stages";

/** A contact's stage, in the customer's own name and colour, with a quiet tint. */
export function ContactStatusBadge({ status }: { status: string }) {
  const { label, color } = useStages();
  const c = color(status);
  return (
    <span
      className="inline-flex items-center gap-1.5 rounded-sm border px-2 py-0.5 text-[11px] font-semibold tracking-wide whitespace-nowrap"
      style={{
        backgroundColor: `color-mix(in srgb, ${c} 10%, white)`,
        borderColor: `color-mix(in srgb, ${c} 28%, white)`,
        color: `color-mix(in srgb, ${c} 68%, #1a3278)`,
      }}
    >
      <span aria-hidden className="size-1.5 rounded-full" style={{ backgroundColor: c, boxShadow: `0 0 0 2px color-mix(in srgb, ${c} 18%, white)` }} />
      {label(status)}
    </span>
  );
}
