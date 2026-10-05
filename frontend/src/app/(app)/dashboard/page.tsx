"use client";

import { ArrowUpRight, CalendarClock, FileSpreadsheet, PhoneCall, PhoneIncoming, UserPlus, Users, type LucideIcon } from "lucide-react";
import Link from "next/link";

import { ContactStatusBadge } from "@/components/badges";
import { Alert, ButtonLink, Card, Loading, PageHeader, cx } from "@/components/ui";
import { MANAGER_ROLES, useAuth } from "@/lib/auth";
import { useBranding } from "@/lib/branding";
import { formatNumber } from "@/lib/format";
import { useStages } from "@/lib/stages";
import { type DashboardSummary } from "@/lib/types";
import { useApi } from "@/lib/use-api";

function greeting() {
  const h = new Date().getHours();
  return h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
}

export default function DashboardPage() {
  const { user, hasRole } = useAuth();
  const { plural, contactLabel, agentName, has } = useBranding();
  const { stages } = useStages();
  const manager = hasRole(...MANAGER_ROLES);
  const { data, error, loading } = useApi<DashboardSummary>("/api/dashboard");

  if (loading && !data) return <Loading />;
  if (error || !data) return <Alert>{error ?? "Could not load the dashboard"}</Alert>;

  const max = Math.max(1, ...Object.values(data.contactsByStatus));
  const today = new Date().toLocaleDateString("en-IN", { weekday: "long", day: "numeric", month: "long", year: "numeric" });

  return (
    <>
      <p className="eyebrow mb-2 text-brand-700">{today}</p>
      <PageHeader
        title={`${greeting()}, ${user?.fullName.split(" ")[0] ?? ""}`}
        subtitle={manager ? "The whole company at a glance." : `Your ${plural.toLowerCase()} and calls.`}
        actions={
          manager && data.totalContacts === 0 && has("IMPORT") ? (
            <ButtonLink href="/import">Import your first Excel sheet</ButtonLink>
          ) : undefined
        }
      />

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Stat icon={Users} label={manager ? `${plural}` : `My ${plural.toLowerCase()}`} value={formatNumber(data.totalContacts)} href="/contacts" featured />
        {manager && <Stat icon={UserPlus} label="Added this week" value={formatNumber(data.newThisWeek)} href="/contacts" />}
        {has("FOLLOW_UPS") && (
          <Stat icon={CalendarClock} label="My follow-ups due" value={formatNumber(data.myFollowUpsDue)} href="/follow-ups" tone={data.myFollowUpsDue > 0 ? "amber" : undefined} />
        )}
        {has("AI_CALLS") && (
          <Stat icon={PhoneIncoming} label="Call-backs waiting" value={formatNumber(data.openCallbacks)} href="/voice/callbacks" tone={data.openCallbacks > 0 ? "red" : undefined} />
        )}
        {!manager && has("AI_CALLS") && <Stat icon={PhoneCall} label="AI calls today" value={formatNumber(data.aiCallsToday)} href="/voice/calls" />}
      </div>

      <div className="mt-8 grid gap-6 lg:grid-cols-5">
        <Card title={`${plural} by stage`} className="lg:col-span-3">
          <ul className="space-y-4">
            {stages.map(({ key: s }) => {
              const n = data.contactsByStatus[s] ?? 0;
              return (
                <li key={s}>
                  <div className="flex items-center justify-between gap-3 text-sm">
                    <ContactStatusBadge status={s} />
                    <span className="font-display text-lg font-semibold tabular-nums text-ink">{formatNumber(n)}</span>
                  </div>
                  <div className="mt-2 h-[3px] overflow-hidden rounded-full bg-muted">
                    <div className="h-full rounded-full bg-gradient-to-r from-brand-400 to-brand-600 transition-[width] duration-700" style={{ width: `${(n / max) * 100}%` }} />
                  </div>
                </li>
              );
            })}
          </ul>
        </Card>

        <div className="space-y-6 lg:col-span-2">
          {has("AI_CALLS") && (
            <Card title={`${agentName}, the AI agent, today`}>
              <div className="grid grid-cols-2 divide-x divide-line rounded-md border border-line bg-raised">
                <MiniStat label="Calls made" value={formatNumber(data.aiCallsToday)} />
                <MiniStat label="Simulated" value={formatNumber(data.aiCallsSimulatedToday)} />
              </div>
              <p className="mt-4 text-sm leading-relaxed text-ink-soft">
                Call-backs the agent booked land in{" "}
                <Link href="/voice/callbacks" className="text-brand-700 underline decoration-brand-600/40 underline-offset-4 hover:decoration-brand-600">
                  Call-backs
                </Link>
                .{manager && " Campaigns, scripts and the test console are under AI voice agent."}
              </p>
            </Card>
          )}
          <Card title="Quick actions">
            <div className="grid gap-2">
              <QuickAction href="/contacts/new" icon={UserPlus} label={`Add a ${contactLabel.toLowerCase()}`} />
              {manager && has("IMPORT") && <QuickAction href="/import" icon={FileSpreadsheet} label="Import from Excel" />}
              {manager && has("AI_CALLS") && <QuickAction href="/voice/campaigns" icon={PhoneCall} label="Start a calling campaign" />}
              {has("FOLLOW_UPS") && <QuickAction href="/follow-ups" icon={CalendarClock} label="Review my follow-ups" />}
            </div>
          </Card>
        </div>
      </div>
    </>
  );
}

function Stat({ icon: Icon, label, value, href, tone, featured }: { icon: LucideIcon; label: string; value: string; href: string; tone?: "red" | "amber"; featured?: boolean }) {
  return (
    <Link
      href={href}
      className={cx(
        "group relative block overflow-hidden rounded-lg border bg-surface p-5 shadow-card transition duration-300 hover:-translate-y-0.5 hover:shadow-lift",
        featured ? "border-brand-300" : "border-line hover:border-line-strong",
      )}
    >
      {/* A gold hairline across the top that brightens on hover. */}
      <span
        aria-hidden
        className={cx(
          "absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-brand-600 to-transparent transition-opacity duration-300",
          featured ? "opacity-80" : "opacity-0 group-hover:opacity-60",
        )}
      />
      <div className="flex items-start justify-between gap-3">
        <p className="eyebrow text-ink-faint">{label}</p>
        <span className="grid h-8 w-8 place-items-center rounded-md border border-line bg-raised text-brand-700 transition group-hover:border-brand-400 group-hover:bg-brand-50">
          <Icon className="h-4 w-4" strokeWidth={1.6} aria-hidden />
        </span>
      </div>
      <p
        className={cx(
          "mt-3 font-display text-[2.6rem] leading-none font-semibold tabular-nums",
          tone === "red" ? "text-red-700" : tone === "amber" ? "text-amber-700" : featured ? "text-brand-800" : "text-ink",
        )}
      >
        {value}
      </p>
      <p className="mt-3 flex items-center gap-1 text-xs text-ink-faint transition group-hover:text-brand-700">
        View <ArrowUpRight className="h-3.5 w-3.5" strokeWidth={1.6} aria-hidden />
      </p>
    </Link>
  );
}

function MiniStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="p-4">
      <p className="eyebrow text-ink-faint">{label}</p>
      <p className="mt-2 font-display text-3xl leading-none font-semibold tabular-nums text-ink">{value}</p>
    </div>
  );
}

function QuickAction({ href, icon: Icon, label }: { href: string; icon: LucideIcon; label: string }) {
  return (
    <Link
      href={href}
      className="group flex items-center gap-3 rounded-md border border-line bg-raised px-3.5 py-3 text-sm text-ink transition duration-200 hover:border-brand-300 hover:bg-brand-50"
    >
      <Icon className="h-4 w-4 text-brand-700" strokeWidth={1.6} aria-hidden />
      <span className="flex-1">{label}</span>
      <ArrowUpRight className="h-4 w-4 text-ink-faint transition group-hover:translate-x-0.5 group-hover:-translate-y-0.5 group-hover:text-brand-700" strokeWidth={1.6} aria-hidden />
    </Link>
  );
}
