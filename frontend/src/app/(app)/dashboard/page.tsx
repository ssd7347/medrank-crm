"use client";

import Link from "next/link";

import { LeadStatusBadge } from "@/components/badges";
import { LeadPriorityList } from "@/components/priorities";
import { Alert, Card, EmptyState, Loading, PageHeader, cx } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { countdown, formatDateTime, formatNumber, label, relativeTime } from "@/lib/format";
import { LEAD_SOURCES, LEAD_STATUSES, type DashboardSummary, type FollowUp } from "@/lib/types";
import type { Priorities } from "@/lib/types-advanced";
import type { Deadline } from "@/lib/types-counselling";
import { useApi } from "@/lib/use-api";
import { useNow } from "@/lib/use-now";

function Stat({ title, value, href, tone }: { title: string; value: number | string; href?: string; tone?: "red" | "amber" }) {
  const body = (
    <div className="group relative overflow-hidden rounded-lg border border-line bg-surface p-5 shadow-card transition duration-300 hover:border-brand-300 hover:shadow-lift">
      <p className="eyebrow text-ink-faint">{title}</p>
      <p className={cx("mt-2.5 font-display text-[2.4rem] leading-none font-semibold", tone === "red" ? "text-red-700" : tone === "amber" ? "text-amber-700" : "text-ink")}>
        {value}
      </p>
    </div>
  );
  return href ? <Link href={href}>{body}</Link> : body;
}

export default function DashboardPage() {
  const { user } = useAuth();
  const { data, error, loading } = useApi<DashboardSummary>("/api/dashboard");
  const followUps = useApi<FollowUp[]>(data?.showsLeads ? "/api/follow-ups/mine" : null, { days: 1 });
  const priorities = useApi<Priorities>(data?.showsLeads ? "/api/priorities" : null);

  const firstName = user?.fullName.split(" ")[0];

  return (
    <>
      <PageHeader title={`Hello, ${firstName}`} subtitle="Here is where things stand today." />
      {error && <Alert>{error}</Alert>}
      {loading && !data && <Loading />}
      {data && (
        <div className="space-y-6">
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            {data.showsLeads && (
              <>
                <Stat title="Leads in pipeline" value={formatNumber(data.totalLeads)} href="/leads" />
                <Stat title="New in last 7 days" value={formatNumber(data.newLeadsLast7Days)} href="/leads" />
                <Stat title="My follow-ups due today" value={data.followUpsDueToday} href="/follow-ups" tone={data.followUpsDueToday ? "amber" : undefined} />
                <Stat title="My overdue follow-ups" value={data.followUpsOverdue} href="/follow-ups" tone={data.followUpsOverdue ? "red" : undefined} />
              </>
            )}
            {data.students !== null && <Stat title="Students" value={formatNumber(data.students)} href="/students" />}
            <Stat title="Colleges in database" value={formatNumber(data.colleges)} href="/colleges" />
            {data.pendingApprovals !== null && (
              <Stat title="Data changes awaiting approval" value={data.pendingApprovals} href="/approvals" tone={data.pendingApprovals ? "amber" : undefined} />
            )}
          </div>

          <DeadlinesCard />

          {data.showsLeads && !!priorities.data?.leads.length && (
            <Card
              title="What to do next"
              actions={
                <Link href="/priorities" className="text-xs font-medium text-brand-700 hover:underline">
                  View all
                </Link>
              }
            >
              <LeadPriorityList leads={priorities.data.leads.slice(0, 5)} showAction />
            </Card>
          )}

          {data.showsLeads && (
            <div className="grid gap-6 lg:grid-cols-5">
              <Card title="Pipeline" className="lg:col-span-3">
                <PipelineBars summary={data} />
              </Card>
              <Card
                title="Due today & overdue"
                actions={
                  <Link href="/follow-ups" className="text-xs font-medium text-brand-700 hover:underline">
                    View all
                  </Link>
                }
                className="lg:col-span-2"
              >
                {followUps.loading && !followUps.data ? (
                  <Loading />
                ) : !followUps.data?.length ? (
                  <EmptyState title="Nothing due">You are all caught up.</EmptyState>
                ) : (
                  <ul className="-my-2 divide-y divide-line">
                    {followUps.data.slice(0, 8).map((f) => (
                      <li key={f.id} className="py-2">
                        <Link href={`/leads/${f.leadId}`} className="group block">
                          <div className="flex items-center justify-between gap-2">
                            <span className="truncate text-sm font-medium group-hover:text-brand-700">{f.leadName}</span>
                            <span className={cx("text-xs whitespace-nowrap", f.overdue ? "font-medium text-red-700" : "text-ink-faint")}>
                              {relativeTime(f.dueAt)}
                            </span>
                          </div>
                          <p className="truncate text-xs text-ink-soft">{f.purpose}</p>
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </Card>
              <Card title="Where leads come from" className="lg:col-span-5">
                <SourceList summary={data} />
              </Card>
            </div>
          )}
        </div>
      )}
    </>
  );
}

function PipelineBars({ summary }: { summary: DashboardSummary }) {
  const max = Math.max(1, ...LEAD_STATUSES.map((s) => summary.leadsByStatus[s] ?? 0));
  return (
    <ul className="space-y-2.5">
      {LEAD_STATUSES.map((s) => {
        const n = summary.leadsByStatus[s] ?? 0;
        return (
          <li key={s}>
            <Link href={`/leads?status=${s}`} className="grid grid-cols-[10.5rem_1fr_3rem] items-center gap-3 rounded-lg hover:bg-muted">
              <LeadStatusBadge status={s} />
              <div className="h-2.5 overflow-hidden rounded-full bg-muted">
                <div className="h-full rounded-full bg-brand-500" style={{ width: `${(n / max) * 100}%` }} />
              </div>
              <span className="text-right text-sm tabular-nums">{n}</span>
            </Link>
          </li>
        );
      })}
    </ul>
  );
}

function SourceList({ summary }: { summary: DashboardSummary }) {
  const rows = LEAD_SOURCES.map((s) => [s, summary.leadsBySource[s] ?? 0] as const)
    .filter(([, n]) => n > 0)
    .sort((a, b) => b[1] - a[1]);
  if (!rows.length) return <EmptyState title="No leads yet">Add a lead or import a CSV to get started.</EmptyState>;
  return (
    <div className="flex flex-wrap gap-2">
      {rows.map(([s, n]) => (
        <Link key={s} href={`/leads?source=${s}`} className="rounded-lg border border-line px-3 py-2 hover:border-brand-300">
          <span className="text-sm">{label(s)}</span>
          <span className="ml-2 text-sm font-semibold tabular-nums">{n}</span>
        </Link>
      ))}
    </div>
  );
}

function DeadlinesCard() {
  const { data } = useApi<Deadline[]>("/api/counselling/deadlines", { days: 7 });
  const now = useNow();
  if (!data?.length) return null;
  return (
    <Card
      title="Counselling deadlines this week"
      actions={
        <Link href="/counselling/calendar" className="text-xs font-medium text-brand-700 hover:underline">
          Calendar
        </Link>
      }
    >
      <ul className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
        {data.slice(0, 6).map((d) => {
          const soon = new Date(d.at).getTime() - now < 24 * 3_600_000;
          return (
            <li key={`${d.roundId}-${d.kind}`} className={cx("rounded-lg border p-3", soon ? "border-red-200 bg-red-50" : "border-line")}>
              <p className="text-sm font-medium">{d.kind}</p>
              <p className="text-xs text-ink-soft">{d.roundLabel}</p>
              <p className={cx("mt-1 text-xs", soon ? "font-semibold text-red-700" : "text-ink-faint")}>
                {countdown(d.at)} · {formatDateTime(d.at)}
              </p>
            </li>
          );
        })}
      </ul>
    </Card>
  );
}
