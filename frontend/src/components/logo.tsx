"use client";

import { useBranding } from "@/lib/branding";

export function Logo({ light }: { light?: boolean }) {
  const { companyName } = useBranding();
  const initial = companyName.trim().charAt(0).toUpperCase() || "A";
  return (
    <div className="flex min-w-0 items-center gap-2.5">
      <span
        className={`grid h-8 w-8 shrink-0 place-items-center rounded-lg text-base font-bold ${light ? "bg-brand-50 text-brand-800" : "bg-brand-600 text-white"}`}
        aria-hidden
      >
        {initial}
      </span>
      <span className={`truncate text-sm font-semibold tracking-tight ${light ? "text-white" : "text-ink"}`}>{companyName}</span>
    </div>
  );
}
