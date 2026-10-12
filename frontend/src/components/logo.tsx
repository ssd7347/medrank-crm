"use client";

import { useBranding } from "@/lib/branding";

/** The customer's uploaded logo, or a monogram of their name in a fine royal-blue frame. */
export function Logo({ light, onDark }: { light?: boolean; onDark?: boolean }) {
  const { companyName, logoUrl } = useBranding();
  const initial = companyName.trim().charAt(0).toUpperCase() || "A";
  return (
    <div className="flex min-w-0 items-center gap-3">
      {logoUrl ? (
        // eslint-disable-next-line @next/next/no-img-element -- served by our own API, any size the customer uploads
        <img src={logoUrl} alt="" className="h-9 w-auto max-w-24 shrink-0 rounded object-contain" />
      ) : (
        <span
          className="relative grid h-9 w-9 shrink-0 place-items-center rounded-md border border-brand-600 bg-gradient-to-br from-brand-50 via-brand-100 to-brand-300 font-display text-xl leading-none font-semibold text-brand-900 shadow-gold"
          aria-hidden
        >
          {initial}
          <span className="absolute inset-[3px] rounded-[4px] border border-brand-600/30" />
        </span>
      )}
      <span className="min-w-0">
        <span className={`block truncate font-display text-[1.2rem] leading-tight font-semibold tracking-wide ${onDark ? "text-white" : light ? "text-navy" : "text-ink"}`}>
          {companyName}
        </span>
        <span className={`eyebrow block text-[0.58rem] ${onDark ? "text-white/65" : "text-brand-700"}`}>Business suite</span>
      </span>
    </div>
  );
}
