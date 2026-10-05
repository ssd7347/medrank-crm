"use client";

import { Inbox } from "lucide-react";
import Link from "next/link";
import { Children, cloneElement, isValidElement, useEffect, useId, useRef } from "react";

// Small UI kit. Colours, fonts and shadows come from the theme tokens in globals.css: understated luxury,
// charcoal surfaces, ivory text, champagne gold kept for what matters (primary actions, active states).

export function cx(...classes: (string | false | null | undefined)[]) {
  return classes.filter(Boolean).join(" ");
}

type ButtonProps = React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary" | "danger" | "ghost";
  size?: "sm" | "md";
  loading?: boolean;
};

const buttonBase =
  "inline-flex items-center justify-center gap-2 rounded-md font-semibold tracking-[0.01em] whitespace-nowrap transition duration-200 ease-out disabled:cursor-not-allowed disabled:opacity-45 disabled:shadow-none disabled:translate-y-0";

const buttonVariants = {
  // Champagne gold with a soft sheen; lifts a hair on hover.
  primary:
    "bg-gradient-to-b from-brand-500 to-brand-600 text-on-brand shadow-gold ring-1 ring-inset ring-black/5 hover:-translate-y-px hover:from-brand-400 hover:to-brand-600 hover:shadow-[0_12px_24px_-12px_rgb(150_115_50/0.7)] active:translate-y-0",
  secondary: "border border-line-strong bg-surface text-ink shadow-[0_1px_2px_rgb(30_58_110/0.05)] hover:border-brand-400 hover:bg-brand-50 hover:text-brand-900",
  danger: "bg-red-700 text-white hover:bg-red-800",
  ghost: "text-ink-soft hover:bg-muted hover:text-ink",
} as const;

export function Button({ variant = "primary", size = "md", loading, className, children, disabled, ...rest }: ButtonProps) {
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      className={cx(buttonBase, size === "sm" ? "h-8 px-3 text-xs" : "h-10 px-4 text-sm", buttonVariants[variant], className)}
    >
      {loading && <Spinner className="h-3.5 w-3.5" />}
      {children}
    </button>
  );
}

export function ButtonLink({ href, children, variant = "primary" }: { href: string; children: React.ReactNode; variant?: "primary" | "secondary" }) {
  return (
    <Link href={href} className={cx(buttonBase, "h-10 px-4 text-sm", buttonVariants[variant])}>
      {children}
    </Link>
  );
}

export function Spinner({ className }: { className?: string }) {
  return (
    <svg className={cx("animate-spin", className ?? "h-5 w-5")} viewBox="0 0 24 24" fill="none" aria-hidden>
      <circle cx="12" cy="12" r="10" stroke="currentColor" strokeOpacity="0.2" strokeWidth="3" />
      <path d="M22 12a10 10 0 0 0-10-10" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

export function Field({
  label,
  error,
  hint,
  required,
  children,
  className,
}: {
  label: string;
  error?: string;
  hint?: string;
  required?: boolean;
  children: (id: string) => React.ReactNode;
  className?: string;
}) {
  const id = useId();
  return (
    <div className={className}>
      <label htmlFor={id} className="mb-1.5 block text-xs font-semibold tracking-wide text-ink-soft">
        {label}
        {required && <span className="text-brand-700"> *</span>}
      </label>
      {children(id)}
      {error ? <p className="mt-1.5 text-xs text-red-700">{error}</p> : hint ? <p className="mt-1.5 text-xs leading-relaxed text-ink-faint">{hint}</p> : null}
    </div>
  );
}

const inputBase =
  "block w-full rounded-md border border-line-strong bg-surface px-3 py-2.5 text-sm text-ink shadow-[inset_0_1px_2px_rgb(30_58_110/0.04)] transition duration-200 placeholder:text-ink-faint/70 hover:border-brand-400 focus:border-brand-600 focus:ring-3 focus:ring-brand-600/20 focus:outline-none disabled:cursor-not-allowed disabled:bg-muted disabled:opacity-70 aria-[invalid=true]:border-red-500";

export function Input(props: React.InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={cx(inputBase, props.className)} />;
}

export function Textarea(props: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea rows={3} {...props} className={cx(inputBase, "leading-relaxed", props.className)} />;
}

export function Select({
  options,
  placeholder,
  labelFor,
  ...rest
}: React.SelectHTMLAttributes<HTMLSelectElement> & {
  options: readonly string[] | { value: string; label: string }[];
  placeholder?: string;
  labelFor?: (v: string) => string;
}) {
  return (
    <select
      {...rest}
      className={cx(
        inputBase,
        "appearance-none bg-[length:12px] bg-[right_0.75rem_center] bg-no-repeat pr-9",
        "bg-[url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23c9a96e' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E\")]",
        rest.className,
      )}
    >
      {placeholder !== undefined && <option value="">{placeholder}</option>}
      {options.map((o) =>
        typeof o === "string" ? (
          <option key={o} value={o}>
            {labelFor ? labelFor(o) : o}
          </option>
        ) : (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ),
      )}
    </select>
  );
}

export function Checkbox({ label, ...rest }: React.InputHTMLAttributes<HTMLInputElement> & { label: string }) {
  return (
    <label className="inline-flex cursor-pointer items-center gap-2.5 text-sm text-ink">
      <input type="checkbox" {...rest} className="h-4 w-4 shrink-0 rounded-sm border-line-strong accent-brand-600" />
      {label}
    </label>
  );
}

export function Card({ title, actions, children, className }: { title?: React.ReactNode; actions?: React.ReactNode; children: React.ReactNode; className?: string }) {
  return (
    <section className={cx("rounded-lg border border-line bg-surface shadow-card transition-colors duration-300", className)}>
      {(title || actions) && (
        <header className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-3.5">
          <h2 className="font-display text-lg font-semibold tracking-wide text-ink">{title}</h2>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className="p-5">{children}</div>
    </section>
  );
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: React.ReactNode; actions?: React.ReactNode }) {
  return (
    <div className="mb-7">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0">
          <h1 className="font-display text-3xl leading-tight font-semibold tracking-wide text-ink sm:text-[2.125rem]">{title}</h1>
          {subtitle && <p className="mt-1.5 max-w-2xl text-sm leading-relaxed text-ink-soft">{subtitle}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
      <div className="gold-rule mt-5" />
    </div>
  );
}

const BADGE_TONES = {
  gray: "border-line-strong bg-slate-100 text-slate-700",
  blue: "border-sky-200 bg-sky-100 text-sky-800",
  indigo: "border-indigo-100 bg-indigo-100 text-indigo-800",
  green: "border-emerald-200 bg-emerald-100 text-emerald-800",
  amber: "border-amber-200 bg-amber-100 text-amber-800",
  red: "border-red-200 bg-red-100 text-red-800",
  teal: "border-teal-100 bg-teal-100 text-teal-800",
} as const;

export type Tone = keyof typeof BADGE_TONES;

export function Badge({ tone = "gray", children }: { tone?: Tone; children: React.ReactNode }) {
  return (
    <span className={cx("inline-flex items-center rounded-sm border px-2 py-0.5 text-[11px] font-semibold tracking-wide whitespace-nowrap", BADGE_TONES[tone])}>{children}</span>
  );
}

export function Alert({ tone = "red", children }: { tone?: "red" | "amber" | "green" | "blue"; children: React.ReactNode }) {
  return (
    <div
      role={tone === "red" ? "alert" : "status"}
      className={cx(
        "rounded-md border border-l-2 px-4 py-3 text-sm leading-relaxed",
        tone === "red" && "border-red-200 border-l-red-500 bg-red-50 text-red-800",
        tone === "amber" && "border-amber-200 border-l-amber-500 bg-amber-50 text-amber-900",
        tone === "green" && "border-emerald-200 border-l-emerald-500 bg-emerald-50 text-emerald-800",
        tone === "blue" && "border-line-strong border-l-brand-600 bg-raised text-ink-soft",
      )}
    >
      {children}
    </div>
  );
}

export function EmptyState({ title, children }: { title: string; children?: React.ReactNode }) {
  return (
    <div className="flex flex-col items-center px-6 py-14 text-center">
      <span className="mb-4 grid h-12 w-12 place-items-center rounded-full border border-brand-300 bg-brand-50 text-brand-700">
        <Inbox className="h-5 w-5" strokeWidth={1.5} aria-hidden />
      </span>
      <p className="font-display text-xl font-semibold tracking-wide text-ink">{title}</p>
      {children && <div className="mt-1.5 max-w-md text-sm leading-relaxed text-ink-soft">{children}</div>}
    </div>
  );
}

export function Loading() {
  return (
    <div className="flex items-center justify-center gap-3 py-14 text-brand-700" role="status" aria-live="polite">
      <Spinner />
      <span className="eyebrow text-ink-faint">Loading</span>
    </div>
  );
}

export function Modal({ open, onClose, title, children, wide }: { open: boolean; onClose: () => void; title: string; children: React.ReactNode; wide?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);
  return (
    <dialog
      ref={ref}
      onClose={onClose}
      className={cx("m-auto w-[calc(100%-2rem)] rounded-lg border border-line-strong bg-surface p-0 text-ink shadow-lift", wide ? "max-w-3xl" : "max-w-lg")}
    >
      {open && (
        <div>
          <div className="h-px bg-gradient-to-r from-transparent via-brand-600 to-transparent" />
          <header className="flex items-center justify-between border-b border-line px-6 py-4">
            <h2 className="font-display text-xl font-semibold tracking-wide">{title}</h2>
            <button onClick={onClose} className="grid h-8 w-8 place-items-center rounded-md text-ink-faint transition hover:bg-muted hover:text-ink" aria-label="Close">
              ✕
            </button>
          </header>
          <div className="max-h-[75vh] overflow-y-auto px-6 py-5">{children}</div>
        </div>
      )}
    </dialog>
  );
}

export function Pagination({ page, totalPages, totalItems, onPage }: { page: number; totalPages: number; totalItems: number; onPage: (p: number) => void }) {
  if (totalItems === 0) return null;
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-line px-5 py-3 text-sm text-ink-soft">
      <span className="text-xs tracking-wide">
        {totalItems.toLocaleString("en-IN")} {totalItems === 1 ? "record" : "records"}
      </span>
      <div className="flex items-center gap-2">
        <Button variant="secondary" size="sm" disabled={page <= 0} onClick={() => onPage(page - 1)}>
          Previous
        </Button>
        <span className="px-1 text-xs tracking-wide text-ink-faint">
          Page <span className="text-ink">{page + 1}</span> of {Math.max(totalPages, 1)}
        </span>
        <Button variant="secondary" size="sm" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>
          Next
        </Button>
      </div>
    </div>
  );
}

/**
 * Responsive table. On laptops it is a normal table; on phones each row becomes a stacked card with the
 * column name beside each value (see `.rtable` in globals.css). Column labels are passed down to each Td.
 */
export function Table({ head, children }: { head: React.ReactNode[]; children: React.ReactNode }) {
  const labels = head.map((h) => (typeof h === "string" ? h : ""));
  const rows = Children.map(children, (row) => {
    if (!isValidElement<{ children?: React.ReactNode; className?: string }>(row)) return row;
    let col = 0;
    const cells = Children.map(row.props.children, (cell) => {
      if (!isValidElement(cell)) return cell;
      return cloneElement(cell as React.ReactElement<{ label?: string }>, { label: labels[col++] ?? "" });
    });
    return cloneElement(row, { className: cx("transition-colors duration-150 hover:bg-brand-50/50", row.props.className) }, cells);
  });
  return (
    <div className="sm:overflow-x-auto">
      <table className="rtable min-w-full text-sm">
        <thead>
          <tr className="border-b border-line bg-raised text-left text-ink-faint">
            {head.map((h, i) => (
              <th key={i} className="eyebrow px-5 py-3 font-semibold whitespace-nowrap">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-line">{rows}</tbody>
      </table>
    </div>
  );
}

export function Td({ children, className, label }: { children?: React.ReactNode; className?: string; label?: string }) {
  return (
    <td data-label={label ?? ""} className={cx("px-5 py-3 align-middle text-ink", className)}>
      {children}
    </td>
  );
}

export function DescList({ items }: { items: [string, React.ReactNode][] }) {
  return (
    <dl className="grid grid-cols-1 gap-x-8 gap-y-4 sm:grid-cols-2">
      {items.map(([k, v]) => (
        <div key={k} className="min-w-0">
          <dt className="eyebrow text-ink-faint">{k}</dt>
          <dd className="mt-1 text-sm break-words text-ink">{v ?? "—"}</dd>
        </div>
      ))}
    </dl>
  );
}
