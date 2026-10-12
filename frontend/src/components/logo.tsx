/** The product mark: a fine royal-framed monogram and the name in the display serif. */
export function Logo({ light, onDark }: { light?: boolean; onDark?: boolean }) {
  return (
    <div className="flex min-w-0 items-center gap-3">
      <span
        className="relative grid h-9 w-9 shrink-0 place-items-center rounded-md border border-brand-600 bg-gradient-to-br from-brand-50 via-brand-100 to-brand-300 font-display text-xl leading-none font-semibold text-brand-900 shadow-gold"
        aria-hidden
      >
        M
        <span className="absolute inset-[3px] rounded-[4px] border border-brand-600/30" />
      </span>
      <span className="min-w-0">
        <span className={`block truncate font-display text-[1.08rem] leading-tight font-semibold tracking-wide ${onDark ? "text-white" : light ? "text-navy" : "text-ink"}`}>
          Counselling CRM
        </span>
        <span className={`eyebrow block text-[0.58rem] ${onDark ? "text-white/65" : "text-brand-700"}`}>MBBS · BDS</span>
      </span>
    </div>
  );
}
