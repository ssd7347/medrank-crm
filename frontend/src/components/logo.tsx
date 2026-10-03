"use client";

import { useBranding } from "@/lib/branding";

/** The customer's uploaded logo, or the first letter of their name in the brand colour. */
export function Logo({ light }: { light?: boolean }) {
  const { companyName, logoUrl } = useBranding();
  const initial = companyName.trim().charAt(0).toUpperCase() || "A";
  return (
    <div className="flex min-w-0 items-center gap-2.5">
      {logoUrl ? (
        // eslint-disable-next-line @next/next/no-img-element -- served by our own API, any size the customer uploads
        <img src={logoUrl} alt="" className={`h-8 w-auto max-w-24 shrink-0 rounded object-contain ${light ? "bg-white p-0.5" : ""}`} />
      ) : (
        <span
          className={`grid h-8 w-8 shrink-0 place-items-center rounded-lg text-base font-bold ${light ? "bg-brand-50 text-brand-800" : "bg-brand-600 text-white"}`}
          aria-hidden
        >
          {initial}
        </span>
      )}
      <span className={`truncate text-sm font-semibold tracking-tight ${light ? "text-white" : "text-ink"}`}>{companyName}</span>
    </div>
  );
}
