export function Logo({ light }: { light?: boolean }) {
  return (
    <div className="flex items-center gap-2.5">
      <span
        className={`grid h-8 w-8 place-items-center rounded-lg text-base font-bold ${light ? "bg-brand-50 text-brand-800" : "bg-brand-600 text-white"}`}
        aria-hidden
      >
        +
      </span>
      <span className={`text-sm font-semibold tracking-tight ${light ? "text-white" : "text-ink"}`}>Counselling CRM</span>
    </div>
  );
}
