"use client";

import Link from "next/link";
import { useState } from "react";

import { Alert, Card, EmptyState, Field, Input, Loading, PageHeader, Select, Table, Td, cx } from "@/components/ui";
import { formatDate, formatNumber, formatRupees } from "@/lib/format";
import type { AnalyticsOverview, Branch, OutcomeRow } from "@/lib/types-growth";
import { useApi } from "@/lib/use-api";

const monthFmt = new Intl.DateTimeFormat("en-IN", { month: "short" });
const monthLongFmt = new Intl.DateTimeFormat("en-IN", { month: "long", year: "numeric" });
const compactFmt = new Intl.NumberFormat("en-IN", { notation: "compact", maximumFractionDigits: 1 });

export default function AnalyticsPage() {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [branchId, setBranchId] = useState("");
  const branches = useApi<Branch[]>("/api/branches");
  const { data, error, loading } = useApi<AnalyticsOverview>("/api/analytics/overview", { from, to, branchId });

  return (
    <>
      <PageHeader title="Analytics" subtitle="Round-day status, the admissions funnel, revenue, and outcomes by category and state." />

      {error && (
        <div className="mb-4">
          <Alert>{error}</Alert>
        </div>
      )}
      {loading && !data ? (
        <Loading />
      ) : data ? (
        <div className="space-y-6">
          <section aria-label="Right now">
            <h2 className="mb-2 text-sm font-semibold">Right now</h2>
            <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
              <Live label="Awaiting results" value={data.roundDay.awaitingResults} hint="Locked choice lists with no result recorded" href="/counselling/desk" />
              <Live label="Decisions closing in 24 h" value={data.roundDay.decisionsClosing24h} hint="Allotted seats not yet decided" href="/counselling/desk" urgent />
              <Live label="Decisions pending" value={data.roundDay.decisionsPending} hint="All open decision windows" href="/counselling/desk" />
              <Live label="Urgent alerts unacknowledged" value={data.roundDay.unacknowledgedUrgent} hint="Families who have not confirmed, last 14 days" href="/counselling/desk" urgent />
            </div>
          </section>

          <div className="grid gap-3 border-t border-line pt-5 sm:grid-cols-3 lg:max-w-3xl">
            <Field label="Period from">{(id) => <Input id={id} type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />}</Field>
            <Field label="To">{(id) => <Input id={id} type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />}</Field>
            {!!branches.data?.length && (
              <Field label="Branch">
                {(id) => (
                  <Select
                    id={id}
                    value={branchId}
                    onChange={(e) => setBranchId(e.target.value)}
                    placeholder="All branches"
                    options={branches.data!.map((b) => ({ value: String(b.id), label: b.name }))}
                  />
                )}
              </Field>
            )}
          </div>
          <p className="-mt-3 text-sm text-ink-soft">
            {formatDate(data.from)} to {formatDate(data.to)}
            {!from && !to && " (last 12 months)"}. Leads and students are counted by the date they were added.
          </p>

          <div className="grid gap-6 lg:grid-cols-2">
            <Card title="Admissions funnel">
              <Funnel stages={data.funnel} />
              <p className="mt-3 text-xs text-ink-faint">
                Closed without admission: {formatNumber(data.leadsByStatus.CLOSED)} · still new: {formatNumber(data.leadsByStatus.NEW)}
              </p>
            </Card>

            <Card title="Revenue">
              <div className="grid grid-cols-2 gap-3">
                <Money label="Fees agreed" value={data.revenue.billed} hint="Plans created in the period" />
                <Money label="Collected" value={data.revenue.collected} hint="Payments received in the period" />
                <Money label="Refunded" value={data.revenue.refunded} />
                <Money label="Outstanding now" value={data.revenue.outstanding} hint="Unpaid on active plans" href="/fees" />
              </div>
              <h3 className="mt-5 mb-2 text-xs font-medium text-ink-soft">Collected per month, last 12 months</h3>
              <MonthlyBars months={data.revenue.monthly} />
            </Card>
          </div>

          <div className="grid gap-6 lg:grid-cols-2">
            <Outcomes title="Outcomes by category" first="Category" rows={data.byCategory} />
            <Outcomes title="Outcomes by home state" first="Home state" rows={data.byState} />
          </div>

          {data.branches.length > 0 && (
            <Card title="Branches compared">
              <div className="-m-4">
                <Table head={["Branch", "Leads", "Students", "Admissions", "Collected"]}>
                  {data.branches.map((b) => (
                    <tr key={b.branch?.id ?? "none"}>
                      <Td className="font-medium">{b.branch?.name ?? <span className="text-ink-soft">Head office (no branch)</span>}</Td>
                      <Td className="tabular-nums">{formatNumber(b.leads)}</Td>
                      <Td className="tabular-nums">{formatNumber(b.students)}</Td>
                      <Td className="tabular-nums">{formatNumber(b.admissions)}</Td>
                      <Td className="tabular-nums">{formatRupees(b.collected)}</Td>
                    </tr>
                  ))}
                </Table>
              </div>
            </Card>
          )}

          <p className="text-sm text-ink-soft">
            Need the underlying rows?{" "}
            <Link href="/reports" className="text-brand-700 hover:underline">
              Open the report builder
            </Link>
            . Channel costs are under{" "}
            <Link href="/marketing" className="text-brand-700 hover:underline">
              Marketing
            </Link>
            .
          </p>
        </div>
      ) : null}
    </>
  );
}

function Live({ label: l, value, hint, href, urgent }: { label: string; value: number; hint: string; href: string; urgent?: boolean }) {
  const hot = urgent && value > 0;
  return (
    <Link href={href} className={cx("block rounded-xl border bg-surface p-4 shadow-sm hover:bg-muted", hot ? "border-red-300" : "border-line")}>
      <p className="text-xs text-ink-faint">{l}</p>
      <p className={cx("mt-1 text-2xl font-semibold tabular-nums", hot && "text-red-700")}>{formatNumber(value)}</p>
      <p className="mt-0.5 text-xs text-ink-soft">{hint}</p>
    </Link>
  );
}

function Money({ label: l, value, hint, href }: { label: string; value: number; hint?: string; href?: string }) {
  const body = (
    <>
      <p className="text-xs text-ink-faint">{l}</p>
      <p className="mt-0.5 text-lg font-semibold tabular-nums">{formatRupees(value)}</p>
      {hint && <p className="text-xs text-ink-soft">{hint}</p>}
    </>
  );
  return href ? (
    <Link href={href} className="block rounded-lg bg-muted p-3 hover:ring-1 hover:ring-line">
      {body}
    </Link>
  ) : (
    <div className="rounded-lg bg-muted p-3">{body}</div>
  );
}

/** Horizontal bars, one per stage, each labelled with its count and its share of all inquiries. */
function Funnel({ stages }: { stages: { stage: string; count: number }[] }) {
  const top = Math.max(stages[0]?.count ?? 0, 1);
  if (!stages[0]?.count) return <EmptyState title="No leads in this period" />;
  return (
    <ol className="space-y-2.5">
      {stages.map((s) => {
        const share = Math.round((100 * s.count) / top);
        return (
          <li key={s.stage} title={`${s.stage}: ${s.count} (${share}% of inquiries)`}>
            <div className="flex items-baseline justify-between gap-3 text-sm">
              <span>{s.stage}</span>
              <span className="tabular-nums">
                <b>{formatNumber(s.count)}</b>
                <span className="ml-1.5 text-xs text-ink-faint">{share}%</span>
              </span>
            </div>
            <div className="mt-1 h-2.5 rounded-r bg-muted">
              <div className="h-full rounded-r bg-brand-600" style={{ width: `${Math.max((100 * s.count) / top, s.count ? 1 : 0)}%` }} />
            </div>
          </li>
        );
      })}
    </ol>
  );
}

/** Single-series column chart. Each column carries a tooltip; the peak and latest months are labelled. */
function MonthlyBars({ months }: { months: { month: string; collected: number }[] }) {
  const max = Math.max(...months.map((m) => m.collected), 0);
  if (max === 0) return <p className="text-sm text-ink-soft">No payments recorded in the last 12 months.</p>;
  const peak = months.findIndex((m) => m.collected === max);
  return (
    <figure>
      <div className="flex h-36 items-end gap-0.5 border-b border-line" role="img" aria-label="Fees collected per month for the last 12 months">
        {months.map((m, i) => {
          const date = new Date(`${m.month}-01T00:00:00`);
          const labelled = (i === peak || i === months.length - 1) && m.collected > 0;
          return (
            <div key={m.month} className="group flex h-full min-w-0 flex-1 flex-col items-center justify-end" title={`${monthLongFmt.format(date)}: ${formatRupees(m.collected)}`}>
              {labelled && <span className="mb-0.5 text-[10px] whitespace-nowrap text-ink-soft tabular-nums">₹{compactFmt.format(m.collected)}</span>}
              <div
                className="w-full max-w-7 rounded-t bg-brand-600 group-hover:bg-brand-700"
                style={{ height: `${(100 * m.collected) / max}%`, minHeight: m.collected > 0 ? 2 : 0 }}
              />
            </div>
          );
        })}
      </div>
      <div className="mt-1 flex gap-0.5 text-[10px] text-ink-faint">
        {months.map((m, i) => (
          <span key={m.month} className="min-w-0 flex-1 text-center">
            {i % 2 === (months.length - 1) % 2 ? monthFmt.format(new Date(`${m.month}-01T00:00:00`)) : ""}
          </span>
        ))}
      </div>
    </figure>
  );
}

function Outcomes({ title, first, rows }: { title: string; first: string; rows: OutcomeRow[] }) {
  return (
    <Card title={title}>
      <div className="-m-4">
        {!rows.length ? (
          <EmptyState title="No students in this period" />
        ) : (
          <Table head={[first, "Students", "Got a seat", "Admitted", "Admission rate"]}>
            {rows.slice(0, 12).map((r) => (
              <tr key={r.key}>
                <Td className="font-medium">{r.key === "null" ? "Not recorded" : r.key}</Td>
                <Td className="tabular-nums">{formatNumber(r.students)}</Td>
                <Td className="tabular-nums">{formatNumber(r.allotted)}</Td>
                <Td className="tabular-nums">{formatNumber(r.admitted)}</Td>
                <Td className="tabular-nums">{r.students ? `${Math.round((100 * r.admitted) / r.students)}%` : "—"}</Td>
              </tr>
            ))}
          </Table>
        )}
      </div>
    </Card>
  );
}
