"use client";

import Link from "next/link";

import { ContactStatusBadge } from "@/components/badges";
import { Alert, ButtonLink, Card, Loading, PageHeader, cx } from "@/components/ui";
import { MANAGER_ROLES, useAuth } from "@/lib/auth";
import { useBranding } from "@/lib/branding";
import { formatNumber } from "@/lib/format";
import { useStages } from "@/lib/stages";
import { type DashboardSummary } from "@/lib/types";
import { useApi } from "@/lib/use-api";

export default function DashboardPage() {
  const { user, hasRole } = useAuth();
  const { plural, agentName, has } = useBranding();
  const { stages } = useStages();
  const manager = hasRole(...MANAGER_ROLES);
  const { data, error, loading } = useApi<DashboardSummary>("/api/dashboard");

  if (loading && !data) return <Loading />;
  if (error || !data) return <Alert>{error ?? "Could not load the dashboard"}</Alert>;

  const max = Math.max(1, ...Object.values(data.contactsByStatus));

  return (
    <>
      <PageHeader
        title={`Hello, ${user?.fullName.split(" ")[0] ?? ""}`}
        subtitle={manager ? "The whole company at a glance." : `Your ${plural.toLowerCase()} and calls.`}
        actions={
          manager && data.totalContacts === 0 && has("IMPORT") ? (
            <ButtonLink href="/import">Import your first Excel sheet</ButtonLink>
          ) : undefined
        }
      />

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label={manager ? `${plural}` : `My ${plural.toLowerCase()}`} value={formatNumber(data.totalContacts)} href="/contacts" />
        {manager && <Stat label="Added this week" value={formatNumber(data.newThisWeek)} href="/contacts" />}
        {has("FOLLOW_UPS") && <Stat label="My follow-ups due" value={formatNumber(data.myFollowUpsDue)} href="/follow-ups" tone={data.myFollowUpsDue > 0 ? "amber" : undefined} />}
        {has("AI_CALLS") && <Stat label="Call-backs waiting" value={formatNumber(data.openCallbacks)} href="/voice/callbacks" tone={data.openCallbacks > 0 ? "red" : undefined} />}
        {!manager && has("AI_CALLS") && <Stat label="AI calls today" value={formatNumber(data.aiCallsToday)} href="/voice/calls" />}
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-2">
        <Card title={`${plural} by status`}>
          <ul className="space-y-2">
            {stages.map(({ key: s }) => (
              <li key={s}>
                <div className="flex items-center justify-between gap-2 text-sm">
                  <ContactStatusBadge status={s} />
                  <span className="tabular-nums text-ink-soft">{formatNumber(data.contactsByStatus[s] ?? 0)}</span>
                </div>
                <div className="mt-1 h-1.5 rounded-full bg-muted">
                  <div className="h-1.5 rounded-full bg-brand-600" style={{ width: `${((data.contactsByStatus[s] ?? 0) / max) * 100}%` }} />
                </div>
              </li>
            ))}
          </ul>
        </Card>
        {has("AI_CALLS") && (
        <Card title={`${agentName}, the AI agent, today`}>
          <div className="grid grid-cols-2 gap-3">
            <Stat label="Calls made" value={formatNumber(data.aiCallsToday)} href="/voice/calls" />
            <Stat label="Simulated (not dialled)" value={formatNumber(data.aiCallsSimulatedToday)} href="/voice/calls" />
          </div>
          <p className="mt-4 text-sm text-ink-soft">
            Call-backs the agent booked land in{" "}
            <Link href="/voice/callbacks" className="text-brand-700 hover:underline">
              Call-backs
            </Link>
            .{manager && " Campaigns, scripts and the test console are under AI voice agent."}
          </p>
        </Card>
        )}
      </div>
    </>
  );
}

function Stat({ label, value, href, tone }: { label: string; value: string; href: string; tone?: "red" | "amber" }) {
  return (
    <Link href={href} className="block rounded-xl border border-line bg-surface p-4 shadow-sm hover:bg-muted">
      <p className="text-xs text-ink-faint">{label}</p>
      <p className={cx("mt-1 text-2xl font-semibold tabular-nums", tone === "red" && "text-red-700", tone === "amber" && "text-amber-700")}>{value}</p>
    </Link>
  );
}
